# P05: common `.linka` codec

## Статус и область

Реализован `LinkaCodec(media, files, inspection, ids)` с frozen public read/write
handoff. Base `0816394017adefd3fc77aeebca2a1a052493206e`, ветка
`implementation/p05-linka-codec`. Ранее зафиксированный policy blocker снят
явным решением пользователя. Разрешённые frozen updates интегрированы отдельно:
`601291e` → `6a71cc8`, `01e2adb` → `bef656c`. Остальные изменения — только
commonMain/commonTest `portability/linka/` и этот документ.

Это conditional common implementation, **не G1/G3/native adapter acceptance**.
Trust-boundary review COORD обязателен перед интеграцией. Нет bindings к live
library, новых dependencies, изменений ports/domain/Gradle/platform, private
runtime files или чужих media.

## Реализация и владение

- ZIP читает EOCD и central index через повторно открываемые Source, затем
  последовательно сверяет local records, payload и descriptors. В памяти —
  ограниченный index/config и небольшие chunks, не весь архив/медиа.
- Проверяются store/deflate, flags, CRC32, compressed/uncompressed sizes,
  local/central paths, offsets, truncation, archive/count/aggregate/actual-inflate
  budgets; ZIP64/multidisk/encryption/symlink/special records отвергаются.
  Non-ASCII ZIP names требуют UTF8 flag; недоказанные legacy encodings не угадываются.
  Parser memory budgets: имя <=4096 UTF8 bytes, сумма central names <=1MiB,
  включая directories/config. Проверка declared name size и checked Long суммы
  выполняется до read/allocation имени; local names и media reference paths тоже
  ограничены 4096 bytes. Превышение — LIMIT_EXCEEDED без truncation/переименования.
- Payload staging использует только созданные `PlatformFilesPort` handles с
  фиксированным suggested name, никогда ZIP paths для filesystem extraction.
  Каждый chunk ограничен до записи. Даже unused entries проходят ZIP validation.
- `safePath` запрещает traversal, drive/backslash/NUL/device/trailing dot-space;
  `UUID..wav` и nested media допустимы. Collision key — существующий internal
  `text.archivePathCollisionKey` (NFC, lowercase); file-directory prefixes тоже
  проверяются. Media references совпадают с точным canonical entry name.
- JSON до DOM ограничен bytes/depth/decoded UTF16 strings/field names; duplicate
  object keys отвергаются. Проверяются все raw pages/cards до первого
  `media.prepare`. Root quiz, unsupported mode/version/card schema не скрываются
  нормализацией. `1.0/1.2/2.0` — только flat; `3.0` — только pages.
- Непосредственно root/page/card keys `action/actions/command/commands/event/
  events/script/scripts/navigation` case-sensitive зарезервированы: любое
  значение, включая null/empty, даёт UNSUPPORTED_FEATURE. Внутри opaque metadata
  ключевые слова не интерпретируются. Остальные unknown fields сохраняются.
  В passive string values запрещены sandbox/provider URI schemes `file:`,
  `content:`, `ph:`, `assets-library:`; никаких local handles в extensions.
- Root/page/card unknown JSON хранится на соответствующем domain object.
  Codec-controlled passive source `id` находится в extensions того же уровня;
  cell extensions содержат normalized width=height=1 (и passive данные non-content
  card). Это не внутренние IDs: новые уникальные IDs выдаёт IdSource один раз на
  read, одинаковые source IDs не объединяют карточки. Writer экспортирует
  внутренние unique page/card IDs, не внешние numeric/duplicate IDs из extensions;
  source IDs остаются пассивными в исходном imported document, write их не меняет.
  Повторный read — новый import с новыми IDs. Known fields не могут быть подменены
  extensions; EMPTY wire IDs детерминированы page ID и позицией, без IdSource.
- По принятому COORD R04 canonical geometry — uniform 1×1. Входные width/height
  проверяются как finite numeric, затем нормализуются в 1; original span !=1,
  placeholder или overflow выставляют layoutNormalized. Каждый existing slot
  сохраняет input sequence/holes; overflow переносится на следующие pages без
  span-packing/auto-sort и без потери content/space/empty metadata. Writer не
  реанимирует старую opaque геометрию после move/resize, всегда пишет 1×1.
  Nontrailing sparse slots становятся explicit type2 (Electron фильтрует null).
  Неявный trailing padding не генерируется: array заканчивается последней explicit
  позицией; explicit EMPTY с metadata сохраняется. Type3 становится EMPTY; write
  не вызывает IdSource. Non-content с непустыми image/audio references отклоняется,
  поскольку domain не может владеть таким содержимым без потери.
- withoutSpace задаёт LITERAL и title, directSet — DIRECT. audioPath даёт Recording;
  audioText/audioVoice — editor metadata, никогда fallback. None не превращается в
  Tts. Writer Tts возвращает SPEECH_MATERIALIZATION_REQUIRED, materialization — P12.
- Вызов injected inspection получает ограниченный owned file; image side/pixels
  и audio duration дополнительно проверяются codec. Сам inspector обязан соблюдать
  frozen decoder budgets до allocation: fake inspection не доказывает этого.
- Imported bytes хэшируются при staging и сравниваются с metadata MediaStore.
  Prepared leases удерживаются до успешного acquire финальной независимой lease.
  Failure/cancellation освобождают собственные leases и временные handles; codec
  никогда не удаляет immutable assets и не вызывает GC. UnconfirmedCommit
  распространяется без retry, final PreparedBoard или подмены на Failed/Cancelled.
  Cleanup пытается освободить все свои handles даже при ошибке одного cleanup;
  transfer финальной lease происходит только после успешного normal cleanup.
  После последнего suspend acquire и непосредственно перед handoff выполняется
  ensureActive: поздний Completed lease отменённого owner закрывается в finally,
  PreparedBoard не передаётся, cancellation не превращается в Failed/Cancelled.
  Ошибка cleanup не маскирует primary UnconfirmedCommit/cancellation. Физическое
  удаление temp при отказе OS discard не гарантируется кодеком — это native gate.
- Writer не потребляет caller lease: удерживает retain, проверяет все assets,
  inspection, streamed hash/size, пишет store/3.0 с CRC descriptors в новый owned
  temporary, повторно валидирует ZIP и только затем отдаёт handle caller-у.

## Воспроизводимые synthetic проверки

`LinkaCodecTest` использует реальные ZIP streams, независимый fixture builder,
fixed raw DEFLATE bytes и контролируемые file/media/lease seams. Native inspection
fake явно обозначен. `generate_deflate_fixtures.py` — host stdlib generator с
decompress self-check, только печатает fixture expressions, никуда не пишет.

Проверяются все 9 reserved keys × 3 structural levels × null/[]/{}, late rejection
без prepare, opaque nested actions round-trip, versions/legacy canonical strings,
all-field semantic round-trip, repeated source IDs/fresh internal IDs, placeholder
stability, spans/overflow/sparse positions, recording-vs-editor-text, Tts refusal,
CRC/false sizes/truncated deflate/archive, symlinks/flags/ZIP64/multidisk/offsets,
Unicode/case duplicates/prefix conflicts, nested paths/UUID..wav, missing config/
media, count/aggregate/per-file/archive-actual/content-count/JSON/decoder limits,
exact accepted boundaries, writer IO/hash/cancel cleanup и UnconfirmedCommit
после успешной первой preparation без final publication.

Проверка исходного implementation commit `974d875`: `:shared:jvmTest` — **35/35**, из
них прежние 14 и новые 21; failures/errors/skipped = 0 по XML. Также PASS
`:shared:compileKotlinIosSimulatorArm64` и
`:shared:compileTestKotlinIosSimulatorArm64` (компиляция, не запуск Native tests).
При финальной проверке identity mapping writer исправлен на internal IDs вместо
passive source IDs; добавлена regression для cleanup errors и сохранения
UnconfirmedCommit. Затронутые JVM/Native compile checks повторены и прошли.
Ни один test не объявляет fake inspection decoder proof.

## Corrections по trust review COORD

Review base: `974d8752f749aa622dd5f4b77354d6f1ea102236`. Добавлены только
два согласованных исправления: name-memory budgets и cancellation после final
acquire. Production diff: `CodecBoundary.kt` (constants/safePath), `LinkaZip.kt`
(early central/local limits и тот же writer name budget), `LinkaCodec.kt`
(две cancellation checks); test seam, четыре regression tests и этот документ.
Ports/deps/domain/storage/audio/platform не менялись.

Новые actual synthetic ZIP cases: ровно 4096/4097 UTF8 bytes, включая non-ASCII и
directory slash; UTF8 flag; local/central name headers; declared oversized central
name без достаточных bytes (LIMIT до EOF); ровно 1MiB/1MiB+1 central names,
directories включены; oversized reference paths. Отказы происходят без prepare/
inspection. Некоперативный suspend acquire возобновляется с новой lease после
cancel owner: наблюдается CancellationException, нет handoff, все собственные
leases/temp закрыты, immutable media и source bytes сохранены. Прежние writer
caller-lease/cancellation regressions сохранены.

Итог corrections: focused codec **25/25**, полный `:shared:jvmTest` **39/39**
(прежние 35 + четыре новых), failures/errors/skipped = 0 по XML. Main и test
`:shared:compileKotlinIosSimulatorArm64` /
`:shared:compileTestKotlinIosSimulatorArm64` — PASS. Повторный peer oracle на
Node v22.22.2 / E `c540646e…` — PASS: strict flat2.0, writer ZIP3.0,
sparse/span visibility. Native tests не запускались. Production correction
diff — три файла, 13 additions / 2 deletions; остальное — test seam/regressions
и evidence, без изменения frozen declarations.

### Ограничение deflate canonicality

`raw.remaining == 0` не доказывает exact end самого DEFLATE stream: public Okio
inflater может prefetch compressed trailer во внутренний buffer. Проверены
compressed-region budgets, actual output size и CRC, но отсутствие таких trailing
compressed bytes **не доказано**. Эти bytes не исполняются. Public common API
даёт bytesWritten, не bytesRead; one-byte feeding, internal API, собственный
deflate decoder и новые библиотеки не добавлялись. Решение о необходимости более
строгой canonicality остаётся COORD, это не заявленный PASS.

## R04 uniform geometry correction перед P11

Base `9c0003262988d23f23f8db8425f2bf22662a020a`. Из production изменён только
`LinkaWire.kt`: удалены occupied arrays, поиск свободного span rectangle и overlap
rejection writer. Read размещает existing элементы по исходному индексу modulo
capacity, неизвестные поля/source IDs/editor speech metadata остаются пассивными.
Оригинальные finite numeric размеры нужны только для layoutNormalized и не
становятся canonical geometry. Writer валидирует старые known size values, но
не использует их для размещения/границ и всегда экспортирует 1×1. Новых domain
полей, span model, UI/layout engine, ports/deps/migrations нет. Name budgets,
cancellation guards, ZIP/media/audio/passive/ID policies не изменялись.

В том же writer loop реализован bonus: отсутствие implicit trailing padding
(64×64 с последним explicit EMPTY на позиции6 экспортирует 7 элементов, не4096;
полностью пустая страница — cards=[]). Промежуточные holes и explicit EMPTY
metadata сохраняются. Прежний overlap-rejection test обновлён на успешный uniform
export при old opaque width2, а защита от known-field overrides сохранена.
Ни один существующий regression не удалён/пропущен.

Три новых regression tests: move/resize без разбора opaque geometry (включая
старые width2/height8), finite/invalid span values и layoutNormalized, compact
trailing padding с holes/explicit EMPTY metadata. Прежние span/sparse/overflow
tests усилены: covered content не теряется, SPACE/EMPTY и их metadata остаются
на исходных uniform slots; source ZIP bytes неизменны; page overflow bounds и
UUID..wav tests сохранены. Pure Node oracle читает четыре фактических новых
Kotlin writer ZIP: standard/audio fixture, uniform span import, edited board,
64×64 holes/EMPTY. Проверки count/text/mode/metadata/visibility не ослаблены.

После focused tests и correctness review полный локальный `:shared:jvmTest` —
**42/42** (прежние 35 + 4 name/cancellation regressions + 3 новых), codec suite
**28/28**, failures/errors/skipped = 0 по XML. Native main/test compilation
(`:shared:compileKotlinIosSimulatorArm64`,
`:shared:compileTestKotlinIosSimulatorArm64`) — PASS. Повторный pure Node
v22.22.2 oracle на E `c540646e…` для всех четырёх новых writer-output — PASS,
включая actual uniform placements, edited recording metadata/bytes и compact
padding. Production diff: только `LinkaWire.kt`, +16/−37. Native executor не
запускался: integration/actual Native execution остаётся COORD. Исходные spans
намеренно не round-trip по принятому R04; strict deflate canonicality и native
decoder/device limits остаются ранее описанными непроверенными gates.

Команда focused проверки:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
ANDROID_HOME=/Users/aacidov/Library/Android/sdk \
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer \
./gradlew :shared:jvmTest --tests '*LinkaCodecTest*' --console=plain
```

## Реально исполненный peer oracle

Peer E: `c540646e1bf06d5f4ea76d6db6b2ab1b82bfe89b`, root
`/Volumes/data/linka/linka.looks-electron`. Сначала прочитан его AGENTS.md.
`peer_oracle.cjs` читает ровно `src/common/interfaces/ConfigFile.ts` и
`src/common/utils/linkaZip.ts`, transpile выполняется установленным peer TypeScript
в памяти. Peer source не копируется, app/storage/TTS не импортируются и не
запускаются. Node — установленный `v22.22.2`. Проверяется HEAD и неизменность git
status. Synthetic ZIP создаётся с `wx` только в собственном allowed test directory
и удаляется в finally, без media из peer.

Исполнены pure peer `normalizeConfigFile` для strict synthetic flat2.0 (тот же
input, что в common test), `openLinkaZip` для фактического Kotlin writer-output3.0
с synthetic image/audio bytes, mapping standard/description/direct/literal и
recording metadata. Отдельный writer-output проверяется через
`getCardGridPlacements`: span import нормализован в uniform slots 0/1/2, после
move/resize — 0/1/3; все content имеют placement 1×1 и не covered. Дополнительно
проверены sparse slot3, explicit EMPTY metadata на6 и отсутствие trailing
padding в raw writer array. Root/page/card passive metadata, recording editor
metadata и synthetic audio bytes проверены независимо от нормализации геометрии.
Peer normalizer намеренно теряет некоторые passive metadata; их сохранность
доказывает common codec round-trip, а не чужой normalizer.

После focused JVM test oracle воспроизводится так:

```sh
python3 -c 'import json,xml.etree.ElementTree as E; r=E.parse("shared/build/test-results/jvmTest/TEST-su.linka.pictures.portability.linka.LinkaCodecTest.xml").getroot(); s=r.findtext("system-out", "").splitlines(); markers={"writer":"P05_WRITER_ZIP_HEX=","sparse":"P05_SPARSE_ZIP_HEX=","edited":"P05_EDITED_ZIP_HEX=","holes":"P05_HOLES_ZIP_HEX="}; print(json.dumps({k:next(x.split("=",1)[1] for x in s if x.startswith(v)) for k,v in markers.items()}))' \
  | /Users/aacidov/.nvm/versions/node/v22.22.2/bin/node \
    shared/src/commonTest/kotlin/su/linka/pictures/portability/linka/peer_oracle.cjs
```

Это **не** полный Electron runtime read-save round-trip, не actual decoder proof
и не Android↔iOS↔Electron device acceptance. Реальные WAV/OGG/MP3/3GPP/AMR-NB,
physical targets/ARMv7, native inspector allocation limits, P12 materialization и
native executor integration остаются у соответствующих gates/COORD.

## COORD integration verification

Trust review прочитал все4 production files и затем точную дельту review fixes.
Интеграция: `974d8752` → `22fffdb`, `9c000326` → `ad9ceaa`.
Main aggregate gate (P02+P03+P05) прошёл: **66/66 JVM**, Native test link,
оба static Shared.framework и Android debug assembly; BUILD SUCCESSFUL in47s.
На проверенном owned iOS17.0/21A328 через public simctl spawn: **66/66 PASS**,
24506ms. Raw log `p05-integrated-native-ios17.log` находится только в approved
session temp. Ни один test не фильтровался/отключался для итогового прогона.

Из main также повторён pure Node22.22.2 peer oracle на E `c540646…`:
legacy2-flat + фактический Kotlin writer ZIP3 reader/normalizer + sparse/span
visibility PASS; peer status неизменен, собственный synthetic ZIP удалён.
Это принимает common codec и его bounded-payload trust checks, не доказывает
полный Electron runtime/media round-trip, native inspector или ARMv7.
Ограничение strict deflate canonicality выше принято COORD явно, не скрыто
под проверками CRC/size, которые такого свойства не доказывают.

Uniform correction интегрирована отдельно: `0d95e42c` → `5b58e2f`.
COORD просмотрел production simplification и semantic regressions; combined
main gate: **105/105 JVM**, оба Shared.framework, Native test link и Android
assembly, BUILD SUCCESSFUL in49s. На owned iOS17.0/21A328 — **105/105 PASS**,
26099ms; raw log `p05-uniform-integrated-native-ios17.log` только в approved temp.
Четыре actual Kotlin writer fixtures повторно прошли pure peer oracle из main:
uniform import/edit/sparse visibility, metadata/audio bytes, compact padding.
Original spans намеренно нормализованы по R04, прочее содержимое не обрезано.
