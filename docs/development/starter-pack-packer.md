# Сборка стартовых .linka 3.0

Пакер готов; **production archives не созданы**, пока нет реальных утверждённых
41 PNG. Runtime catalogue, `shippingResources`, bootstrap и storage не изменяются.
Изображения не генерируются этим скриптом, запросов к сети нет.

## Входной контракт для primary

Файлы положить в `assets/starter-pack/images/`. Manifest рекомендуется сохранить
как `assets/starter-pack/images/manifest.json`:

```json
{
  "schema": "linka-starter-images-v1",
  "purpose": "production",
  "status": "approved",
  "images": {
    "ru.yes": {
      "file": "ru-yes.png",
      "size": 12345,
      "sha256": "<64 lowercase hex characters of actual file>",
      "provenance": "<actual generation/source evidence; no invented media license>",
      "visualApproval": "approved"
    }
  }
}
```

Пример намеренно неполный и не исполнимый: никаких фиктивных manifest/PNG в assets.
Нужны **ровно 41 ключ**, все CSV `key`, кроме начинающихся с `ru.letter.`.
Повторяющиеся слова имеют один recording/image key, включая `ru.eat` — приём пищи.
`file` — только basename относительно `assets/starter-pack/images`, regex
`[a-z0-9][a-z0-9-]*\.png`; подпапки, absolute paths, `..`, symlink запрещены.
Поля `size`, `sha256`, непустой `provenance` и `visualApproval=approved` обязательны.
Статус approved выставляется **после** визуального review, не по факту загрузки.
Не переносить автоматически сторонние copyright media. Не присваивать CC0/GPL
сгенерированным изображениям без основания; provenance — не юридическая лицензия.

Поддерживается строгий поднабор PNG: 8-bit RGB или RGBA, без interlace/animation/
palette; ширина/высота 2–8192, площадь ≤16000000, файл ≤64MiB. Проверяются signature,
IHDR, chunk CRC, порядок IDAT, IEND без хвоста, bounded zlib raster и filter bytes.
Это не семантическая оценка рисунка и не native media inspection. Другой формат
PNG отклоняется, а не молча перекодируется. Если генератор выдаёт другой поднабор,
нужна явная подготовка подходящего исходника, новые hash/size и review.

## Запуск после готовности картинок

Из корня проекта; Python stdlib + уже установленные ffprobe/ffmpeg, без pip:

```sh
python3 -B scripts/test-pack-starter-sets.py
python3 -B scripts/pack-starter-sets.py --images-manifest assets/starter-pack/images/manifest.json
```

Входы также: `docs/development/starter-pack-manifest.json`,
`docs/development/starter-pack-recordings.csv`, `assets/starter-pack/audio/manifest.json`
и проверенные MP3. Переиспользуется validator аудио: 74 CSV ключа/87 speaking slots,
receipt POST JSON anton, единственный исторический GET pilot Помоги, hashes/bytes,
strict MP3 decode и non-silent. Для четырёх основных наборов в ZIP войдут только
41 уникальная запись/54 позиции; 33 letter reference clips в архивы не включаются.

Выход только `assets/starter-pack/archives/`:

- `ru.core.v1.linka` — 3 страницы, DIRECT по умолчанию;
- `ru.food.v1.linka`, `ru.activities.v1.linka`, `ru.feelings.v1.linka` — по странице,
  COMPOSE по умолчанию;
- `ru.letters.v1.linka` — 4 страницы, COMPOSE + LITERAL, 33 буквы, SPACE, точка, вопрос;
- `catalog.json` — seedKey, displayName, filename, sizes/SHA, pages/slots,
  capability и hashes четырёх входных manifest/CSV. Это **не runtime catalogue**.

Запуск повторяемый: JSON sorted keys, стабильные page/card IDs, порядок ZIP,
timestamp 1980-01-01, STORED entries без ZIP64/extra. Старый результат, отличающийся
байтами, не перезаписывается; primary должен явно проверить/убрать свои устаревшие
артефакты. Все входы проверяются до создания каталога выхода. Прерывание записи
не делает pack shipping-ready; частичные/отличающиеся результаты требуют review.

## Источник wire-контракта и ограничения проверки

Пакер следует текущим исходникам, не private legacy 1.3:

- `shared/src/commonMain/kotlin/su/linka/pictures/portability/linka/LinkaWire.kt`,
  parse 31–80 и encodeWire 146–215: `version=3.0`, pages с `mode=standard`, rows/columns,
  CONTENT=0, SPACE=1, EMPTY=2, width/height=1, title/imagePath/audioPath/audioText/audioVoice;
- `LinkaCodec.kt:84–119`: `config.json`, assets `media/<sha256>`, дедупликация;
- `portability/zip/{CodecBoundary,ZipProfile,CheckedZip}.kt`: config ≤8MiB,
  media ≤64MiB, ZIP ≤500MiB, ≤2000 entries;
- `portability/zip/CodecIO.kt:22–30`: image bounds ≤8192 / ≤16M pixels;
- `shared/src/commonTest/.../portability/linka/LinkaCodecTest.kt` и `peer_oracle.cjs`:
  отсутствие вложений у SPACE/EMPTY, metadata speech, сохранность позиций.

Каждая из 54 основных карточек получает настоящие PNG+MP3, `audioText` и `audioVoice`
из receipt; общие assets имеют одинаковый hash/path внутри разных архивов.
Корневое `withoutSpace=true` задаёт LITERAL **только для букв**. Буквы и пунктуация —
CONTENT с title, но без image/audio; SPACE — тип 1 без literal title/вложений.
Новых EMPTY нет: все 90 утверждённых позиций заполнены, включая SPACE.
Whole-word речь букв — штатный native TTS, требует доступного русского голоса;
это не блокирует prerecorded offline общение четырёх основных наборов.

Unittest создаёт 1px PNG только в отдельном temporary root, копирует проверенные
MP3 туда, проверяет 5 ZIP/10 страниц/90 позиций, namespace, metadata, asset reuse,
детерминизм, unsafe filenames, missing/hashless/wrong-hash images, PNG CRC/bounds,
несовместимый MP3 provenance и отказ production для 1px. Test-only режим недоступен
в CLI и отказывается работать в проектном корне. Никаких тестовых архивов в Git.

**Не заявлено:** чтение будущих реальных архивов штатным Kotlin codec, native media
inspection и listening/visual approval. После получения картинок primary должен
выполнить эти gate до S10; `catalog.shipping=false`, nativeCodecReview=pending.
