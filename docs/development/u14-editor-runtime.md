# U1.4: actual Android editor / card / settings evidence, 2026-11-06

**Evidence, не визуальная приёмка. U1.4 OPEN.** Сняты и просмотрены 11 настоящих
PNG через MCP `adb.take_screenshot`, без генерации, монтажа или подмены UI.
Источник изображений: [2026-11-06-ui14-editor](../progress-screenshots/2026-11-06-ui14-editor/).

## Неизменяемая привязка

- Только `emulator-5556`; MCP list_devices первым действием, перед навигацией
  проверены AVD `linka_audit_tablet`, SDK 35 / Android 15, density 320, fontScale 1.0.
  `wm size`: physical 1600×2560; actual rotation=1, все raw PNG **2560×1600**,
  окно 1280×800 dp (включая system chrome). Ориентация не менялась.
- Только `su.linka.pictures.development`, явный запуск
  `su.linka.pictures.activity.DevelopmentActivity`. Установленный `base.apk`
  проверен read-only `sha256sum` на устройстве:
  `3700456b8e5ab931d73a15a073881daf57265ba2a0904f2ae9cd92415deae1f2`.
  По handoff primary это code d617 + WIP, позднее captured 477; точный полный
  source-diff этой сборки здесь заново не устанавливался. HEAD evidence-worktree
  `6a1c8784f83b25296faff598d66d925e7ee8bb7b` **не объявляется source APK**.
- Уже импортированный primary fixture «ХОЧУ», 3×3, девять разных изображений и
  recordings; переданный SHA fixture
  `a0589a029f77bfff7307d6ee3c4dbd41e1b2badd52ca90896ca5c835ccfb293b`.
  Архив повторно не импортировался и здесь не хешировался. Начальная сохранённая
  общая фраза по handoff: **ХОЧУ КУШАТЬ**; финально проверена actual UI.
- Unprotected / no password по handoff; capability не подставлялась. Обычный Edit
  открыл authoring без unlock. OS verification UNAVAILABLE по handoff не проверялась
  созданием PIN. Никаких SQL/run-as data writes; единственный SQLite writer — app.
- Phone `5554`, baseline package `su.linka.pictures`, iOS не изменялись. До явного
  запуска development foreground был baseline: никаких касаний в нём не сделано.

## Журнал состояний и действий

Все пути ниже относительно каталога PNG выше; каждое изображение открыто и
визуально проверено. Bounds — raw pixels, подтверждены UI dump перед touch.

| PNG | Реальный путь / pictureState | Показано / результат |
|---|---|---|
| `01-development-launch-library.png` | Explicit DevelopmentActivity → библиотека | «Ваши наборы», плитка ХОЧУ, её overflow, общий menu/FAB |
| `02-hochu-editor-3x3-durable-draft.png` | Overflow «Действия с набором «ХОЧУ»» [785,202–833,250] → «Редактировать» [793,410–1024,458] | Автовход без фиктивной авторизации; заголовок ХОЧУ, Back/Save/menu, checkbox, строки/столбцы 3/3, девять изображений/подписей, pager 1/1 |
| `03-kushat-content-modal-over-grid.png` | Tap первой КУШАТЬ внутри [437,320–994,704], не overflow | CONTENT modal поверх **той же** затемнённой сетки; type/title/image, image actions, play/TTS/record, audio parameters, Отмена/Готово |
| `04-content-title-tap-before-ime.png` | Tap неизменённого title [824,471–1736,599] | Переходный кадр ещё **без** IME; не доказательство клавиатуры |
| `05-content-ime-visible-footer.png` | После установления фокуса, без ввода текста | Настоящая IME; focused КУШАТЬ; footer Отмена/Готово над клавиатурой, body частично скрыт/прокручиваем; grid под модалью сжалась с окном |
| `06-card-space-form-only-unpublished.png` | Native Back закрывает только IME → SPACE | Выбран Пробел; content-поля скрыты, объяснение и footer видны; фон всё ещё КУШАТЬ |
| `07-card-empty-form-only-unpublished.png` | SPACE → EMPTY в открытой форме | Выбрана Пустая карточка, объяснение; content-поля скрыты; никаких SaveCard/Apply |
| `08-content-restored-label-image-audio.png` | EMPTY → CONTENT | Название КУШАТЬ, исходное изображение и audio UI вернулись; медиа не проигрывались |
| `09-settings-preference-list-readonly.png` | Отмена [995,1241–1109,1274] → editor Back [8,56–104,152] → library menu → Настройки | Нормальный Close вернул библиотеку; настройки preference-list с категориями, switches и Back; никаких изменений параметров |
| `10-published-hochu-message-preserved-after-cancel-close-settings.png` | Settings Back → library → плитка ХОЧУ | Девять опубликованных карточек видны; exact content-desc **«Общее сообщение: ХОЧУ КУШАТЬ»**, обе карточки фразы видны. Не выполнялись Clear, InsertText или tap карточек общения |
| `11-retained-hochu-draft-list-no-delete.png` | Board Back → library menu → Сохранённые черновики | Сохранённый ХОЧУ · версия 6 остался; не Resume/не Delete. «Закрыть» вернул стабильную development-библиотеку |

Проверены все четыре запрошенных сценария; 11 кадров, из них один переходный,
а CONTENT до/после переключения намеренно повторён для проверки возврата полей.
Ни SaveCard («Готово»), ни Apply (верхний Save) не нажимались. После CancelCard
dump снова показал исходные девять ячеек редактора; после Close и Settings
actual опубликованная доска и exact фраза проверены кадром 10. Бинарная идентичность
published snapshot/recording bytes не утверждается: прямого чтения БД не было.
Черновик оставлен приложением, не удалён ради «чистого» скриншота.

## Сопоставление с проверенным baseline

Прочитаны `ui-1.4-parity.md`, `u14-editor.md`, `u14-library-settings.md`;
просмотрены `p00-followup/tablet/04-own-editor-confirmed.png`,
`05-standard-card-form.png`, `18-pre-save-board-with-card.png` (source tag
`2073d912f6c70ab0165823f37e4b5704cf72e953`) и `tablet/04-settings.png`.
`03-own-actual-editor.png` **не использован** как editor-reference: там библиотека.

- Сохранена основная иерархия editor: toolbar → checkbox → компактные параметры →
  основная сетка между pager. Все 3×3 видны без прокрутки. В actual grid bounds
  [437,320–2123,1488], одна ячейка [437,320–994,704], pager 1/1 снизу.
- Отличия требуют приёмки, не объявлены паритетом: baseline 04/18 — 3×4 draft
  с пустыми «+» / одной тестовой карточкой, actual — заполненный ХОЧУ 3×3.
  Это не одинаковый fixture для pixel-diff. В actual параметры отображаются текстом
  и действием «Размер сетки», а не двумя открытыми числовыми полями и широкой
  заполненной кнопкой baseline; меньше вертикального пространства над сеткой.
  Actual cells белые, с image/обычной подписью и дополнительным overflow;
  baseline empty/test cells имеют серый фон. Состояние пустой «+» здесь не снято.
- Card modal действительно поверх сетки, порядок type/title/image/audio/footer
  сохранён. Actual ширина 960px, bounds без IME примерно [800,255–1760,1329];
  baseline 05 ниже/компактнее, с underline input и иными размерами кнопок.
  Actual outlined title, remove-image/audio params и явные Отмена/Готово +
  объяснение draft semantics отличаются от baseline. С IME footer виден, body
  обрезается доступной высотой; scroll body и hardware Tab отдельно не испытаны.
- Settings сохраняет preference-list, inset 176px=88dp, цветные категории и
  разделители. Baseline settings portrait 1600×2560, actual landscape 2560×1600:
  это проверка иерархии, **не** геометрически одинаковая пара. Содержимое защиты
  и portable settings отличается от legacy списка, analytics switch не придуман.

## Ограничения и передача primary

Критических runtime crashes/ANR или SaveFailed/UNCONFIRMED/CONFLICT в этом сценарии
не наблюдалось; их пути не вызывались и не обходились. Несколько немедленных
`dump_ui_hierarchy` после перехода вернули `null root node`; screenshot показывал
нормальный app UI, последующий dump того же состояния работал. Это не объявлено
app crash и не потребовало restart/root/clear.

Не проверены: Apply/SaveCard, запись/проигрывание/TTS/pickers и permissions,
реальные recording bytes, multi-page, empty/new board, ошибки сохранения,
large font/contrast, TalkBack, hardware keyboard, phone/iOS/portrait matrix,
точный pixel-diff на одинаковом fixture. Native/JVM/build/JDK gate не запускались.
Source/build/manifest/Gradle/core/auth/root не менялись; APK не переустанавливался.
Нужны отдельные matched-fixture пары и решение пользователя по отличиям выше;
данный журнал **не закрывает U1.4 и не означает пользовательского одобрения**.
