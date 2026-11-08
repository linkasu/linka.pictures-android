# Стартовые ресурсы: CSV → offline Compose resources

## Одна build-команда

```sh
python3 -B scripts/build-starter-resources.py --output-dir shared/build/generated/starter-resources
```

Output directory содержит **только** поддерживаемую Compose структуру:

```text
shared/build/generated/starter-resources/
  files/starter/
    ru.core.v1.linka
    ru.food.v1.linka
    ru.activities.v1.linka
    ru.feelings.v1.linka
    ru.letters.v1.linka
    catalog.json
shared/build/generated/starter-resources-work/
  images/{41 .png, manifest.json}
```

Work-dir — sibling, не Compose resource root. Его можно удалить как generated output;
исходники не меняются. Явный output-dir обязателен. Внутри проекта разрешён только
путь с компонентом `build`; symlink output/ancestors отклоняются. Вне проекта можно
передать отдельную явную temporary/build папку. Неизвестные top-level элементы в
Compose output отклоняются, а не удаляются. Скрипт заменяет только свои generated
имена после проверки входов. Прерванная запись — failed build; повторный запуск
восстанавливает outputs. Gradle/Compose wiring и native admission делает primary.

**App build:** Python stdlib; нет HTTP, ffmpeg, native SDK, генерации или pip.
PNG bounded RGB/RGBA8 parser проверяет CRC/IHDR/IDAT/IEND/raster; unfilter 0–4,
целочисленный crop с CSV inset, filter0 + zlib9 encode. Без rescale. Максимум
8192 на сторону / 16M pixels / 64MiB файла, без palette/interlace/animation.
Для тех же inputs и Python/zlib получается тот же byte output; новый zlib может
изменить сжатые bytes, но не пиксели. 41 реальный stdlib crop проверен побайтно по
RGB-пикселям против прежнего ffmpeg crop: все совпали.

## Authoring truth и acquisition receipts

- `docs/development/starter-pack-recordings.csv`: единственный источник карточек,
  подписей, speech text/voice/MP3 basename и всех 90 позиций. 77 уникальных ключей:
  41 основных слов, 33 letter references и 3 беззвучных действия. `used_in` —
  `seedKey:pN:cN`, с 1; несколько мест через `;`. `cell_kind` CONTENT/LITERAL/SPACE.
  Для SPACE/./? speech/voice/file пустые. «Есть»/`ru.eat` означает еду.
- `assets/starter-pack/sheets.csv`: пять точных historical prompts, reference paths,
  grid, row-major recording keys, inset. Последняя пустая ячейка body — trailing `;`.
  Prompts сохранены ровно из переданных primary вызовов Codex, без добавленного LF.
- `assets/starter-pack/source-manifest.json`: фактические source PNG hashes/sizes,
  dimensions, original prompt/layout/reference hashes и provenance. Это acquisition
  receipt, не второй словарь/layout. CSV drift останавливает build; изменение prompt
  само по себе не переатрибутирует старый PNG. После редактирования prompt ставится
  `editable_regeneration`; новая генерация/review и новый receipt — отдельный шаг.
- `assets/starter-pack/previews/{5 .png}`: raw editable image sources, bytes сохранены.
- `assets/starter-pack/audio/{74 .mp3,manifest.json}`: сохранённые записи и acquisition
  receipts. MP3 bytes не в CSV. Исторические used_in в receipt — snapshot, не layout
  truth. Build сверяет key/label/speechText/file/voice, SHA/size, HTTP receipt,
  `strictDecode=passed`, `nonSilent=true`, bounded duration/peak/sample metadata.
- `docs/development/starter-pack-manifest.json` (`authoring-v2`): названия/seed keys,
  rows/columns/pageCount/optional и история решений; **без повторения CSV страниц**.

Для четырёх основных наборов в архивы входят 41 PNG и 41 prerecorded MP3,
переиспользуемые на 54 позициях. Буквы: 33 glyph CONTENT без attachments, точка,
вопросительный знак и настоящий SPACE type1; input style LITERAL. Их целые слова
зависят от штатного native TTS/доступного русского голоса. 33 reference MP3 букв не
вкладываются в `.linka` и не объявляются синтезом произвольных слов.

## Входы/выходы для TaskProvider

Inputs — ровно перечисленные файлы и glob-ы:

```text
docs/development/starter-pack-manifest.json
docs/development/starter-pack-recordings.csv
assets/starter-pack/sheets.csv
assets/starter-pack/source-manifest.json
assets/starter-pack/previews/*.png
assets/starter-pack/audio/*.mp3
assets/starter-pack/audio/manifest.json
scripts/build-starter-resources.py
scripts/prepare-starter-images.py
scripts/pack-starter-sets.py
scripts/prepare-starter-audio.py
scripts/starter_png.py
```

Outputs: Compose directory и sibling `<output-dir>-work`. Tests, docs, AGENTS и
контакт-лист не нужны Gradle inputs. Generated crops/archives удалены из source tree.
Entry также отключает локальную запись `.pyc`: без `-B` source tree остаётся прежним.
`catalog.json` содержит names/seed keys/size/SHA/pages/slots/capability и input hashes;
`shipping=false`, `nativeCodecReview=pending`, technical actor и humanVisualApproval.
Эти поля не являются release approval; runtime/bootstrap не подключается скриптом.

## Отдельная preparation/check-команда с установленными codecs

```sh
python3 -B scripts/prepare-starter-audio.py
```

Она offline выполняет ffprobe/strict ffmpeg decode/non-silent для всех 74 записей;
при отсутствии codecs останавливается. App build **не повторяет decode** и не
выдаёт проверку hash/receipt за native validation. Изменённый MP3 требует отдельного
acquisition review/receipt, а не правки статуса вслепую. Исторический pilot Помоги
остаётся GET с исходным SHA; 73 других — POST JSON anton. Новых запросов не было.
Source PNG review — primary assistant technical only; human/clinical/usability
approval остаётся false. Model ID не сообщён, внешняя media license не выдумана.
История failed experimental API отдельно от успешного Codex пути в preview manifest.

## Проверки

```sh
python3 -B scripts/test-starter-png.py
python3 -B scripts/test-prepare-starter-images.py
python3 -B scripts/test-pack-starter-sets.py
python3 -B scripts/test-prepare-starter-audio.py
```

PNG tests покрывают RGB/RGBA и все filters0–4, bounds/CRC/truncation/crop. Synthetic
pipeline tests проверяют build без subprocess/tool lookup/network, обе output paths,
Compose-only top-level, idempotence, source immutability, CSV/acquisition drift,
unsafe path/hash/missing asset и audio evidence. Синтетика — только temp root.
Wire/ZIP tests: public canonical3.0, 5 наборов/10 страниц/90 позиций, media SHA namespace,
slot/SPACE/punctuation, recording metadata и deterministic ZIP_STORED без ZIP64.

Wire основан на `shared/src/commonMain/.../portability/linka/{LinkaWire,LinkaCodec}.kt`
и `portability/zip/{CodecBoundary,CodecIO,ZipProfile,CheckedZip}.kt`. Actual Kotlin
codec/native media и human/listening review ещё обязательны до shipping.

Последняя проверка: PNG 2/2, pipeline/CSV 5/5, ZIP/wire 5/5, acquisition/audio 7/7 —
19/19 PASS. На настоящих пяти PNG сборка и повторный запуск с заблокированными
subprocess/tool lookup/network дали 41 crop и 5 архивов/10 страниц/90 позиций;
исходные файлы не изменились, outputs побайтно совпали. Это не native codec PASS.
