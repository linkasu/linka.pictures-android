# Стартовые материалы

## Источники

- Карточки/раскладка/речь: `docs/development/starter-pack-recordings.csv` от корня
  проекта. `key` стабилен; `exact_phrase` — подпись; `cell_kind` — CONTENT/LITERAL/SPACE;
  `speech_text`, `voice`, `expected_filename` задают запись относительно `audio/`;
  `used_in` — `seedKey:pN:cN`, индексы с 1, несколько мест через `;`.
  77 строк дают 90 позиций: 74 записи и 3 беззвучных действия. «Есть»/`ru.eat` — еда.
- Листы: `sheets.csv`, row-major `keys` через `;`, пустой элемент — пропуск;
  `reference_files` относительно этой папки. Промпт — точная строка, включая Unicode.
  `prompt_status=exact_historical` допустим только при совпадении с acquisition hash;
  отредактированный запрос помечай `editable_regeneration`.
- `source-manifest.json` и `audio/manifest.json` — acquisition receipts, не второй
  редактируемый словарь. PNG находятся в `previews/`, MP3 — в `audio/`.

## Подготовка при явно разрешённой генерации

1. Изменения карточек/режимов согласуй с пользователем; правь CSV, а не derived JSON.
2. Изображения готовь через `execute` → `tools.codex.generate_image` с CSV prompt и
   reference files. Прочитай результат. Сохрани фактические source hash/bytes,
   prompt/reference/layout hashes и technical review в source receipt.
   Model ID записывай только если его сообщил инструмент. Старый PNG не становится
   результатом нового промпта: CSV/receipt drift останавливает build до нового review.
3. Аудио — отдельный разрешённый POST `https://tts.linka.su/tts`, JSON
   `{"text":"<speech_text>","voice":"anton"}`. Проверяй bytes/receipt и strict decode
   через `scripts/prepare-starter-audio.py`; 401/402/429 останавливают подготовку.
   Существующие записи повторно не синтезируй; при CSV/receipt conflict нужен review.

## Офлайн build и admission

Запуск от корня: `python3 -B scripts/build-starter-resources.py --output-dir shared/build/generated/starter-resources`.
Только `files/starter/{5 .linka,catalog.json}` попадает в output; crops/derived receipts
живут в sibling work-dir. App build — Python stdlib, без сети, ffmpeg и новых пакетов.
При изменении build-процесса прочитай `docs/development/starter-pack-packer.md`:
там проверки, контракт outputs и список Gradle inputs.

Generated crops/archives оставляй в build/, source of truth — CSV + raw PNG/MP3 +
acquisition receipts. `shipping=false`, technical review не равен human/clinical/
usability approval. Буквы LITERAL требуют native TTS для целых слов; четыре основных
набора используют записи офлайн. Gradle вызывает `:shared:buildStarterResources`
автоматически. Пользовательские библиотеки не заменять: изменение bootstrap и
включение новых runtime defaults требуют отдельной проверки сохранности данных.
