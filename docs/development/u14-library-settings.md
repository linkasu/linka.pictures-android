# U1.4: библиотека и настройки

Статус: **реализация visual slice, не визуальная приёмка**. Родительская сессия
снимает actual PNG после интеграции; compile/JVM PASS не закрывает U1.4.

## Проверенный baseline

Прочитан полный [контракт](ui-1.4-parity.md), просмотрены
`docs/redesign-audit/screenshots/tablet/02b-library-landscape.png` (2000×1250)
и `04-settings.png` (1250×2000). Через `git show v1.4:…` проверены
`MainActivity.kt`, `SetsAdapter.kt`, `activity_main.xml`, `set_grid_button.xml`,
`main_activity_menu.xml`, `settings_activity.xml`, `root_preferences.xml`,
`values/colors.xml`; тег соответствует `2073d912`.

- GridView: **3 колонки**, без orientation override в `activity_main.xml`;
  наружный padding 8dp, изображение 128dp по высоте, подпись по центру,
  margin 1dp, подложка `#99000000`, белый текст 14dp в старом XML.
  В Compose используется масштабируемый 14sp: крупный системный текст не обрезается.
- Toolbar: заголовок «Ваши наборы», overflow справа; New через FAB снизу справа,
  16dp относительно padded content (24dp от края окна).
- Настройки: toolbar с Back, обычный preference list; начало текста 88dp
  от края (8dp content + 80dp preference inset), категории и разделители.
- Цвета исходного тега: primary `#197377`, accent `#fbcc30`. Экраны используют
  общий MaterialTheme: окончательное соответствие цветов зависит от общего theme
  slice, который здесь не менялся.

## Изменения

Библиотека — lazy grid, tap открывает доску; видимый overflow каждой плитки
содержит Open/Edit/Rename/Export/Delete. Long-press больше не единственный путь.
Нечитаемый архив показывает причину, открыть/редактировать его нельзя; неизвестный
cardCount не подменяется нулём. Ошибки listing/restart/операции остаются видимыми.
Сохранённые черновики вынесены в диалог через меню, включая незавершённую форму,
Resume и подтверждаемое удаление. Import/backup/recovery используют прежний
transfer callback; новые pickers или native I/O в этом slice не добавлены.

`onAssets` питается `VisibleImageDemand` только от composed lazy tiles, по одной
обложке на доску, с удалением demand при disposal/смене epoch. Сохранён прежний
read-only `library.board()` для metadata обложки: проверяются epoch/revision,
его временный lease закрывается в finally. Декодирование изображений, исходные
файлы и media-loader leases остаются в root loader; UI их не открывает.

Настройки — обычные строки, switches для существующих bool, модальные числовой
и языковой ввод, dropdown фактически установленных голосов. Выбор голоса задаёт
существующий portable languageTag, не выдумывает persisted voice ID. Тема остаётся
сеансовой. Analytics/telemetry switch не добавлен: portable setting отсутствует.
Сохранены portable scale range, high contrast, кнопки страниц и IME/safe area.

## Обязательный handoff родительской сессии

Старые positional callbacks совместимы. Добавлены trailing параметры:

```kotlin
LibraryScreen(/* прежние аргументы */, gate = access)
SettingsScreen(/* прежние аргументы */, gate = access,
    onAccess = { navigate(ProductPage.ACCESS) })
```

`gate` — заимствованный реальный `AssistantGate`; UI проверяет **конкретное**
RENAME_BOARD / DELETE_BOARD / DELETE_DRAFT / CHANGE_SETTINGS с текущими owner и
capability. Completed при unprotected + null capability не вызывает unlock.
UNAUTHORIZED показывает borrowed `access()` только в контекстном диалоге.
Unlock не запускает действие: нужна отдельная явная Execute/Save/Delete.
Сохраняются исходные entry revision/epoch, draft version или settings snapshot;
CONFLICT/STALE_REQUEST не заменяют захваченные данные свежими.

`gate == null` — только совместимость старого caller, **не разрешение**:
canonical LibraryController неизменно проверяет guard непосредственно перед
записью; UNAUTHORIZED от него также открывает контекстную авторизацию.
New/Edit/Resume идут прежними callbacks; authoring authorization — parent.
Никаких выдачи capability, обхода gate или application/storage изменений здесь нет.

Для управления защитой Settings использует `onAccess`, а не borrowed unlock-only
panel с `manage=false`. Без handoff показывается честная подсказка о доступе к
управлению через меню библиотеки, не фиктивная настройка. Parent передаёт в ACCESS
`manage=true`, а в action dialogs `manage=false`; OS21/22 warning не должен
появляться безусловно на библиотеке или всех настройках.

## Проверки и оставшиеся gaps

Focused JVM на JDK17, `--max-workers=1`:
`ui.library.LibraryPresentationTest`, `ui.settings.SettingsPresentationTest`,
`ui.app.ProductPresentationTest`, `ui.images.VisibleImageDemandTest`,
`application.library.LibraryControllerTest`.

Результат локального запуска: **24 tests, 0 failures**, `git diff --check` PASS.

Проверки покрывают 3 колонки, геометрию локальных Material path icons, selection
обложки без recording assets, retained/unknown descriptions, auth readiness,
отсутствие завершения stale/conflicting/denied decisions, переносимые настройки,
voice labels и существующие canonical guards / composition demand ownership.
Material icons artifact отсутствует: три стандартных векторных path локальны,
новая dependency не нужна.

Не выполнены device install, native/FFmpeg, full gate, Compose instrumentation,
actual PNG/diff, TalkBack/VoiceOver/keyboard, phone/tablet и iOS orientation matrix.
Точные rasterization, цвета общей темы, line wrapping, large-text/focus и действие
интегрированных route callbacks требуют проверки primary; visual PASS не заявлен.
LibraryListing не имеет loading flag: новый фиктивный loading/zero-count state
не придуман; startup readiness остаётся ответственностью parent.

## Коррекция после 97a4332: повторное сохранение настроек

Подтверждённый дефект: после known Completed форма оставалась `committed=true`
на старом snapshot до ручного обновления. Теперь только known Completed вызывает
`onSaved(value)`, затем ровно один `readSettingsSnapshot`: новый epoch/revision
и значение заменяют key формы, разрешая следующую правку. Проверка текущего
snapshot и этот read переиспользуются при начальной/явной загрузке; writer не
повторяется. При ошибке post-commit read сохранённая форма остаётся заблокирована,
показано «Настройки сохранены. Не удалось обновить параметры…»; следующий read —
только явный через меню. Ошибка чтения не объявляет известную запись неуспешной.

Conflict/Unauthorized/Cancelled/Unconfirmed не вызывают автоматический read и не
заменяют захваченный snapshot/ввод. Native-safe проверка languageTag использует
существующий `text.isNativeText`: NUL и одиночные UTF-16 суррогаты отклоняются;
неизвестные корректные языки и граница 128 символов не ограничены whitelist/trim.

Добавлены `SettingsRefreshTest` с настоящими LibraryController/SQLite: два
последовательных known saves с новой revision, один read после каждого, callback
до read, отсутствие reload на ошибках, post-commit read failure и explicit read
без повторной записи, stale read. В `SettingsPresentationTest` native-text
регрессия проверена RED до исправления (4 tests / 1 failed), затем GREEN.
Прежние 24 теста остаются в focused наборе; итоговый запуск JDK17/max-workers=1 —
**29 tests, 0 failures/errors/skipped**, `git diff --check` PASS.
Compose instrumentation не добавлен; UI key/reset wiring дополнительно требует
проверки primary/actual PNG. Visual gate остаётся OPEN.
