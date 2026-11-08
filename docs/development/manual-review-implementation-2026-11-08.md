# Реализация замечаний ручной приёмки — Android / iOS

База: d1493b26edd2651806a9d61305fe62a7a18d5ad1. Пользователь разрешил реализацию
и отдельный коммит каждого завершённого этапа; push/публикация не разрешены.

| Этап | Объём | Состояние |
|---|---|---|
| S0 | Frozen schema1 fixture, контракты | PASS: 1 JVM characterization; визуальные/content gates открыты |
| S1 | Schema2, старые данные и настройки | PASS: focused storage/backup; native old-binary gate ещё не выполнен |
| S2 | Папки и guarded операции | PASS: storage/controller focused; UI и backup в S3/S4 |
| S3 | Snapshot, backup/recovery | PASS: storage/backup/integration/transfer focused; native cross-platform pending |
| S4 | UI папок и меню по удержанию | PASS: JVM presentation/back; native gestures pending |
| S5 | Пароль до открытия настроек набора | PASS: admission/access JVM; native background verification pending |
| S6 | Фраза 32% с адаптацией, боковые стрелки | Focused JVM/Android compile PASS; integrated/native acceptance pending |
| S7 | Безопасное переключение карточек с автосохранением | PASS: actor/card JVM, compact selector approved/implemented; Android lint and both iOS framework links PASS; native IME pending |
| S8 | Перетаскивание: обмен ячеек текущей страницы | PASS: focused actor/gesture JVM; native gestures pending |
| S9 | Опциональный drag в COMPOSE, append во фразу | PASS: focused actor/gesture JVM; native gestures pending |
| S10 | Новый единый стартовый комплект Android/iOS | CSV/offline build-time pipeline готов; 5 архивов включаются в ресурсы Android/iOS, runtime/defaults и native/shipping gates открыты |

Опции листания/drag сохраняются для каждого набора. DIRECT остаётся tap-only.
Папки удаляются только пустыми. Пользовательские данные при обновлении сохраняются.
Пользователь выбрал компактный селектор ячейки в форме; он реализован.
Стартовый словарь 5 наборов / 10 страниц / 90 позиций утверждён. Первоначальное
решение о собственных записях заменено пользовательским LINKa TTS POST с голосом
Anton. Подготовка источников сама по себе не включает S10 в runtime defaults.

План: /Users/aacidov/.opencode/plan/linka-manual-review-2026-11-08-coding-plan.md.
Нативные/визуальные PASS не предполагаются по результатам JVM-тестов.

## Итог интеграционной проверки

- Полный host gate на `1bbb8f4`: 559 JVM + 26 debug + 26 release unit, без
  failures/errors/skipped; Android lint/safety/50 selectors/APK и оба iOS
  framework link PASS. Localization, 384 workflow cases и упаковка iOS test
  resources PASS. После компактного селектора пересобраны затронутые цели.
- На iOS17 выполнены 358 нативных тестов ядра/presentation, все PASS. Это
  не 358 XCUITest и не доказательство drag/IME/VoiceOver.
- Настоящее iOS-приложение собрано/установлено/запущено, но UI BLOCKED:
  Keychain OSStatus -34018. Проверки защиты не ослаблялись, credentials не
  сбрасывались. Исходная библиотека сохранилась schema1: 1 entry, 18 assets,
  integrity_check=ok. Есть host backup БД/медиа до установки.
- На Android-планшете обновление без wipe действительно выполнило schema1→2:
  9 наборов, 1 черновик v4, 169 assets / 6425872 bytes и оба исходных фрагмента
  сообщения сохранены. integrity_check=ok. Версия сообщения выросла при
  переоткрытии communication session, это не потеря/пересоздание текста.
- Меню по удержанию наблюдалось на экране. Затем был InputDispatcher ANR:
  main ожидал HWUI/RenderProxy::loadSystemProperties; рядом Calendar ANR,
  высокая нагрузка compositor и ошибки host EGL/DisplaySurfaceGl. Это сильное
  указание на среду, не доказательство отсутствия ошибок нового UI.
- После guest reboot без завершения выполнен cold restart AVD без wipe-data;
  данные повторно проверены. Нового ANR после boot не было, но UI automation
  продолжила давать timeout/null root. Полная жестовая приёмка остаётся OPEN.
- Второй Android-эмулятор не изменялся. Host pull приватного Android backup
  получил Permission denied; права не менялись и запрет не обходился.
- Финальный correctness review нашёл дополнительный writer: общий checkbox
  листания пересоздавал BoardPresentation. `c594686` сохраняет drag/SIDE;
  точный regression прошёл RED→GREEN.

Подробные результаты: `evidence/manual-review-2026-11-08-host.json`.
Словарь: `starter-pack-proposal.md`; записи: `starter-pack-recordings.csv`.
Первоначальный experimental image API не дал результата. Позже пользователь
предоставил рабочий `execute` → `codex.generate_image`: созданы и технически
просмотрены 5 листов / 41 пиктограмма. Это не human/clinical/usability approval.

## Коммиты этапов

S0 `79831f3`; S1 `b75938c`; S2 `17ef245`; S3 `6677255`;
S4 `9de9935`; S6 `7716c9e`; S5 `6e045ec`; S7 `84f0159`;
S8 `ae5e41f`; S9 `1bbb8f4`; compact S7 `f753d63`;
S10 proposal `28e2e1a`, словарь/CSV `d441151`; дополнительный settings fix `c594686`.

Push, production signing, публикация и замена стартовых shipping assets не выполнялись.

Источники S10: аудио `1ff7830`, public `.linka 3.0` packer `bfbd0f2`, исходные
листы PNG `9c6c590`, CSV/pipeline `20fb3f5`. Аудио: 73 POST JSON и один сохранённый GET-пилот «Помоги»
с реальным provenance. Все 74 MP3 прошли офлайн decode/non-silent проверки;
прослушивание и Android/iOS native playback остаются отдельными gates.

### Подключение build-time ресурсов

Gradle producer подключён через общий Compose resource root. Поскольку
`customDirectory` заменяет исходный каталог, Sync объединяет новые файлы с обеими
существующими локализациями и отклоняет совпадающие пути.

Полная проверка после интеграции CSV-producer, без его пропуска: 19 Python,
561 JVM + 26 debug + 26 release unit, lint/safety/50 selectors, APK и оба iOS
framework link PASS. В APK и staged iOS resources найдены все 5 архивов с точными
байтами каталога и сохранённые переводы. Новый JVM-тест читает ресурсы штатным
public Kotlin-кодеком, проверяет 90 позиций, LITERAL/SPACE и озвучку Anton; PNG
действительно декодируются ImageIO.

Реальный producer дополнительно запущен с запрещёнными socket/HTTP/subprocess/
tool lookup: все 41 crop и 5 архивов собраны, исходники неизменны, повторный запуск
даёт те же байты. Повторный Gradle build получает UP-TO-DATE. Все outputs — в
`shared/build/generated/`; generation/TTS не вызываются app build.
Это не Android/iOS native media, listening или human/usability PASS; bootstrap,
существующие библиотеки и runtime defaults не изменялись.

## Patch и артефакт UI-проверки до S10

После `c594686` повторены затронутые проверки: settings/board-settings JVM,
Android lint/APK, обе iOS framework links, native test binary и Xcode app — PASS.
На iOS17 повторены 10 settings tests, все PASS (частично пересекаются с прежними
358; это не 368 уникальных тестов). В linker остаётся предупреждение ICU object
17.2 против app minimum17.0; выпуск этим прогоном не одобрен.

Android APK этой проверки (не последующих сборок S10), SHA256:
`348863d71899c31e9a367849533ac46aed0a9c51ce9c9b5080cb4842e3325e65`.
Установка через MCP вернула timeout, но установленный `base.apk` имеет ровно этот
hash: установка подтверждена чтением, не повторялась вслепую. Launch также дал
timeout; готовность UI не заявляется. После установки БД повторно проверена:
schema2, 9 entries, 1 draft, 169 assets/6425872 bytes, integrity_check=ok.
