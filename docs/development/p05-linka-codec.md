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
  cell extensions содержат normalized width/height (и passive данные non-content
  card). Это не внутренние IDs: новые уникальные IDs выдаёт IdSource один раз на
  read, одинаковые source IDs не объединяют карточки. Writer экспортирует
  внутренние unique page/card IDs, не внешние numeric/duplicate IDs из extensions;
  source IDs остаются пассивными в исходном imported document, write их не меняет.
  Повторный read — новый import с новыми IDs. Known fields не могут быть подменены
  extensions; EMPTY wire IDs детерминированы page ID и позицией, без IdSource.
- Spans clamped к доступной области, covered content/space переносится в свободные
  slots/дополнительные pages; content не теряется. Sparse slots при writer становятся
  explicit type2: Electron иначе фильтрует null и сдвигает индексы. Type3 становится
  EMPTY; write не вызывает IdSource. Non-content с непустыми image/audio references
  отклоняется, поскольку domain не может владеть таким содержимым без потери.
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

Итоговая проверка после correctness review: `:shared:jvmTest` — **35/35**, из
них прежние 14 и новые 21; failures/errors/skipped = 0 по XML. Также PASS
`:shared:compileKotlinIosSimulatorArm64` и
`:shared:compileTestKotlinIosSimulatorArm64` (компиляция, не запуск Native tests).
При финальной проверке identity mapping writer исправлен на internal IDs вместо
passive source IDs; добавлена regression для cleanup errors и сохранения
UnconfirmedCommit. Затронутые JVM/Native compile checks повторены и прошли.
Ни один test не объявляет fake inspection decoder proof.

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
`getCardGridPlacements`: sparse/span content остаётся на 0/2/3 и не covered.
Peer normalizer намеренно теряет некоторые passive metadata; их сохранность
доказывает common codec round-trip, а не чужой normalizer.

После focused JVM test oracle воспроизводится так:

```sh
python3 -c 'import json,xml.etree.ElementTree as E; r=E.parse("shared/build/test-results/jvmTest/TEST-su.linka.pictures.portability.linka.LinkaCodecTest.xml").getroot(); s=r.findtext("system-out", "").splitlines(); get=lambda prefix:next(x.split("=",1)[1] for x in s if x.startswith(prefix)); print(json.dumps({"writer":get("P05_WRITER_ZIP_HEX="),"sparse":get("P05_SPARSE_ZIP_HEX=")}))' \
  | /Users/aacidov/.nvm/versions/node/v22.22.2/bin/node \
    shared/src/commonTest/kotlin/su/linka/pictures/portability/linka/peer_oracle.cjs
```

Это **не** полный Electron runtime read-save round-trip, не actual decoder proof
и не Android↔iOS↔Electron device acceptance. Реальные WAV/OGG/MP3/3GPP/AMR-NB,
physical targets/ARMv7, native inspector allocation limits, P12 materialization и
native executor integration остаются у соответствующих gates/COORD.
