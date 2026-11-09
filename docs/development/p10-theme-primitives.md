# P10 early handoff: theme и house primitives

Дата: 5 ноября 2026. Base: `2f04e58`, ветка `implementation/p10-shared-ui`.
Только ранний presentation-пакет из `code-contracts.md:180–188`, не завершение P10/G5/G6.

## Передача другим UI lanes

- `ui/theme/LinkaTheme.kt`: `LinkaTheme(darkTheme, highContrast, textScale, content)`, Material2; `LocalLinkaPalette` и `LinkaTargets`.
- `ui/theme/LinkaPalette.kt`: четыре точные frozen палитры. Светлая regular — C4 §3.10; остальные — code-contracts. Disabled — непрозрачные заданные пары, без Material disabled alpha. Pressed — content overlay 8%, high-contrast 4%, composited поверх соответствующего фона.
- `ui/components/CommunicationCard.kt`: frozen label/accessibleName/activationLabel/image/selected/enabled/onActivate/modifier. Изображение уже декодировано caller-ом; `ContentScale.Fit`, отдельная текстовая подпись. Выбранность — checkmark плюс внутренняя рамка.
- `ui/components/LabeledActionButton.kt`: frozen label/enabled/destructive/onClick/modifier. Destructive использует danger/onDanger; название последствия задаёт caller, цвет его не заменяет.
- `PrimitiveSurface.kt` — внутреннее переиспользование feedback/семантики, не дополнительный публичный интерфейс.

Минимальная внешняя цель карточки 96dp, действия 56dp, без фиксированного максимума высоты/числа строк/ellipsis/уменьшения font. Родитель обязан выделять достаточно места: минимальные tokens не отменяют жёстких constraints или clipping ancestor. Внешняя focus-рамка 3dp имеет постоянный резерв 6dp с зазором до внутренней selected-рамки; получение фокуса не меняет геометрию.

`textScale` ограничивает только portable множитель 0.5..3. Системный `fontScale` умножается без ограничения; плотность dp сохраняется. Не-конечные аргументы и неположительный системный масштаб отклоняются. Масштаб не сохраняется здесь; theme содержит только presentation, не затрагивает сеанс/черновик/операции/речь.

Кнопки используют foundation `clickable` (native touch/key/focus), без ручных gestures/key handlers. Pointer/focus сами не вызывают callback. Родительская семантика задаёт имя/роль/selected; семантика дочернего изображения/подписи/checkmark очищена. Disabled не устанавливает clickable вообще, оставляет имя/роль/disabled, без action. Callback не делает ничего кроме переданного caller-ом действия. IO/audio/access/controllers/composition roots и зависимости не менялись.

## Проверки

Новый API до patch отсутствовал; это добавление компонентов, не исправление ранее воспроизводимого падения. Pure regression: `LinkaThemeTest` (3 теста) проверяет все четыре палитры: text/secondary/action/disabled/pressed пары >=4.5, high-contrast >=7; borders/focus/selected >=3, непрозрачность; portable bounds и системный множитель; targets 96/56 и ring3.

После correctness review явно убрана неявная Material `LocalContentAlpha` для текста primitives: используемые пары остаются непрозрачными, как в расчётах.

Выполнена команда:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
ANDROID_HOME=/Users/aacidov/Library/Android/sdk \
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer \
./gradlew :shared:jvmTest :shared:compileDebugKotlinAndroid \
  :shared:compileKotlinIosArm64 :shared:compileKotlinIosSimulatorArm64 --console=plain
```

Результат: **BUILD SUCCESSFUL**. JVM: 217 тестов, 15 suites, 0 failures/errors; новые 3 теста PASS. Android debug Kotlin и обе ARM Native compilations PASS. Только прежние warnings `StoragePinsLock` expect/actual Beta. Default `iosSimulatorArm64Test`/`allTests` не запускались, устройства не затрагивались.

## Оставшиеся gates

Pure tests доказывают только математику палитры/множителя и значения tokens, не реальную геометрию, native semantic tree или физическую доступность. Не проверены native TalkBack/VoiceOver, клавиатурный traversal/autorepeat, pointer cancellation, focus/selected screenshots, большой системный шрифт/узкие окна на Android+iOS. Это обязательные последующие P10/P15 проверки; они не объявлены PASS. Продуктовые экраны, навигация и binding не входят в этот early handoff. Изменения frozen интерфейса или dependencies не потребовались; блокеров COORD нет.

## Продолжение: presentation экрана общения

Следующая разрешённая COORD итерация после интеграции early handoff в main `6052a06`. Base этой итерации — локальный `8dc9824554cfc69c854e4ffdc32a172869870c47`. Это **presentation**, не закрытие полного P10: real vertical binding/storage/audio/root/navigation/lifecycle остаются COORD; G5/G6 OPEN.

Публичный API (COORD явно добавил обязательный `voices`):

```kotlin
@Composable
fun CommunicationScreen(
    state: CommunicationState,
    ids: IdSource,
    voices: VoiceCapability,
    onCommand: (CommunicationCommand) -> Unit,
    onOpenLibrary: () -> Unit,
    images: Map<AssetId, ImageBitmap> = emptyMap(),
    modifier: Modifier = Modifier,
)
```

### Реализовано и проверено по коду

- `ui/communication/CommunicationScreen.kt` — presentation exact session state. `renderedText` выводится без собственного spacing/reducer. Каждая явная команда получает новый `CommandId(ids.next())`; нет dispatch из composition/hover/focus/восстановления. `onOpenLibrary` — только callback; root отвечает за navigation/lifecycle и остановку актуального сеанса при закрытии доски.
- `CommunicationControls.kt` — compose/direct с индикатором, Backspace/Clear/Undo, предыдущая/следующая страница без пропуска пустых страниц, явный InsertText. Speak/Stop находится в самом `CommunicationScreen` вне обеих областей прокрутки. В direct обычный Speak/Backspace/Clear/Undo недоступны; активное воспроизведение всё равно даёт Stop. Save Loading/Saving/Saved/Failed/Unconfirmed, все audio phases, реальные voice statuses и обе группы ошибок видимы; из Problem читается только code, не subjectId/systemCode/пути.
- Локальный ещё не отправленный ввод находится выше адаптивной ветки layout, `rememberSaveable` привязан к epoch. Он сохраняется при compose/direct и перестройке представления, не заменяет MessageDraft. Поле не очищается автоматически после dispatch: frozen API не содержит acknowledgement принятия InsertText, отказ/лимит не должен терять введённый текст. Есть отдельная явно названная очистка поля, не сообщения. Пустая строка не отправляется; пробелы/Unicode не trim/normalize-ятся. Сохранение этого поля системой не является доказательством durable сохранения MessageDraft.
- VoiceCapability обязателен, readiness не угадывается. Missing/initializing/unsupported voice — информационный статус, не запрет Speak: recording-only план не требует TTS; окончательную проверку плана выполняет session/coordinator. VOICE_UNAVAILABLE и SPEECH_UNAVAILABLE имеют разные безопасные сообщения.
- UNCONFIRMED или COMMIT_OUTCOME_UNKNOWN блокирует все commands и переход в библиотеку; сброс прокрутки панели показывает инструкцию полностью закрыть и заново открыть приложение для авторитетной проверки данных, не повторять операцию и не считать исход сохранённым/отменённым. Сам экран reopen не выполняет и фиктивного Saved/Retry не предлагает. STALE_REQUEST также блокирует устаревший сеанс с отдельным reopen сообщением. Saving и обычный Failed не превращаются в UNCONFIRMED.
- `CommunicationGrid.kt`/`CommunicationPresentation.kt` — страница только по совпадающим board/page cursor IDs, никакого fallback на первую страницу при несовпадении. Логические rows/columns и все capacity slots сохраняются, включая implicit holes, EMPTY, SPACE и последнюю позицию64×64. Нормализованный ранее overflow доступен через все страницы; UI не обрезает/не переупаковывает domain cells. Только CONTENT/SPACE активируются. Selected marker относится к снимку текущих board/revision/page/card, в direct выключен.
- Портрет/узкое окно — две независимые прокручиваемые области, landscape при ширине>=480dp — соседние панели. Сообщение имеет собственную прокрутку полного текста и read-only Fit-изображения его снимков. Grid имеет native horizontal scroll и LazyColumn по логическим строкам, min96dp, без уменьшения целей для вмещения. `firstCell` восстанавливает начальную строку/горизонтальное смещение. Родительские hard constraints и крайне малые окна требуют native проверки, а не обещаний по source.
- Применяются safeDrawing insets/IME padding, wrap без ellipsis и без ограничения системного fontScale. OutlinedTextField прокручивает длинный ввод, а не обрезает его содержимое. Foundation focusGroup/FocusManager.moveFocus обслуживают стрелки в grid, Tab/activation остаются native. Модифицированные Ctrl/Alt/Meta-сочетания не перехватываются; нет собственного gesture/key activation engine.
- COORD разрешил внутреннюю правку `CommunicationCard.kt` без изменения сигнатуры: квадратный image slot всегда резервируется, максимум160dp, сжатие по доступной ширине внутри narrow card; Fit внутри слота. Место под selected checkmark также резервируется. Поступление пикселей/выбранность не меняют intrinsic размер этих слотов. Actual layout/screenshot proof ещё нужен.
- Экран не декодирует/не создаёт bitmap, не приобретает/не закрывает media leases, не делает IO, не вызывает audio/access/platform ports. `images` — только lookup уже подготовленных пикселей current page/message; root обязан владеть ограниченной картой видимых assets и их lifetime, не передавать всю библиотеку.

### Autorepeat evidence и проверки

Осмотрен bytecode фактически установленного `foundation-desktop-1.9.3.jar`: `AbstractClickableNode.onKeyEvent` хранит `currentKeyPressInteractions`, повторный KeyDown не создаёт новую press interaction, KeyUp удаляет её и вызывает `onClickKeyUpEvent` только при существующем press. У `ClickableNode.onClickKeyDownEvent` callback отсутствует, `onClickKeyUpEvent` вызывает onClick. Поэтому дополнительный повторный обработчик в primitives не добавлен по явному решению COORD. Это evidence для отсутствия самодельного workaround, **не** native keyboard acceptance.

Focused: `:shared:jvmTest --tests 'su.linka.pictures.ui.communication.CommunicationPresentationTest'` — PASS (6 тестов). Проверены sparse holes/SPACE/EMPTY/последний slot4095 и последняя страница, exact cursor без fallback, revision-aware selected, compose/direct/Stop phases, отсутствие TTS-блокировки для всех VoiceStatus, command freeze для всех источников UNCONFIRMED и stale/loading, Failed/Saving отдельно, safe errors без раскрытия subject/systemCode. Новый screen API до этой итерации отсутствовал; это не исправление воспроизведённого native дефекта.

После correctness review (hoist literal input выше layout-ветки, UNCONFIRMED статус без fake Saved, pinned Speak/Stop, постоянная image geometry) выполнен gate с теми же JAVA_HOME/ANDROID_HOME/DEVELOPER_DIR, что выше:

```sh
./gradlew :shared:jvmTest :shared:compileDebugKotlinAndroid \
  :shared:compileKotlinIosArm64 :shared:compileKotlinIosSimulatorArm64 \
  :shared:compileTestKotlinIosArm64 :shared:compileTestKotlinIosSimulatorArm64 --console=plain
```

**BUILD SUCCESSFUL**. JVM:223 теста/16 suites, failures0/errors0; Android main, обе ARM Native main/test compilations PASS. `git diff --check` PASS. Native tests не исполнялись, default iosSimulatorArm64Test/allTests не запускались, devices/permissions/composition roots/dependencies/session не менялись.

### Точные открытые native/integration gates этой итерации

1. Реальный root binding session/state/voices/ограниченных visible images/leases, navigation/lifecycle interruption и полный communication vertical path Android+iOS.
2. Native semantic tree: одно имя/роль/selected/action на карточку, disabled без action, live-region ошибки, snapshots/read-only message, правильные SPACE/пустая подпись; TalkBack/VoiceOver не дублируют подпись/изображение и не конфликтуют с речью.
3. Tab/пространственный обход через lazy beyond-bounds и horizontal scroll, возврат/видимость focus, отсутствие ловушек, key hold/autorepeat на Android/iOS, pointer cancellation и отсутствие команд при hover/focus.
4. Визуально просмотренные screenshots: portrait/landscape/narrow/IME, большие системные шрифты×portable multiplier, четыре темы, длинные/mixed/image-only сообщения и подписи, стабильность targets при image arrival и selected/focus. Проверка доступности Speak/следующего выбора на фактических малых viewports.
5. Real persistence/reopen UNCONFIRMED/stale, failure/Stop/direct/междосочные сценарии, offline voice/missing-voice и recording-only на обеих платформах; AAC/физическая приёмка P15.

Pure tests и compilation не закрывают ни один из этих native gates. Других изменений frozen интерфейсов или новых dependencies не потребовалось.

## Correction после COORD review `c8f6183`

Подтверждённая причина: `CommunicationMessage` и Backspace/Clear/Undo были дочерними узлами `CommunicationControls.Column.verticalScroll`. Поэтому прокрутка формы/статусов уводила и сообщение/его действия. Для этого уже подтверждённого containment-дефекта дополнительные гипотезы/instrumentation не требовались; native воспроизведение не подменяется проверкой исходников.

Исправление отдельным commit, без amend: `CommunicationScreen` выделяет соседние ограниченные viewport для сообщения и secondary controls, а grid остаётся собственной weighted рабочей областью. Сообщение целиком имеет собственную прокрутку текста/картинок; secondary scroll не является его ancestor. Backspace/Clear/Undo вынесены в `CommunicationMessageActions`, закреплённый вне body/secondary scroll непосредственно над Speak/Stop. В узком окне toolbar прокручивается **горизонтально**, сохраняя min targets и полный wrap подписей, а не растёт в несколько вертикальных рядов за счёт grid. Навигация/board/mode/page не скрыты disclosure-панелью; страницы перенесены выше формы и длинного voice status. Все команды, UNCONFIRMED freeze и API остались прежними.

Новая nontrivial pure layout-policy не вводилась: используются прежняя portrait/landscape ветка, стандартные weights и foundation scroll. Pure математический тест не доказал бы ошибку ancestor scroll, поэтому такое доказательство не заявляется. Перед patch agent-runnable source-containment assert упал: `FAIL: message is a child of secondary controls verticalScroll`. После patch тот же assert (нет `CommunicationMessage(`/Backspace/Clear/Undo в secondary controls; message/toolbar принадлежат screen) PASS. Это структурная проверка причины, **не** runtime layout acceptance.

Focused gate после correctness review:

```sh
./gradlew :shared:jvmTest --tests 'su.linka.pictures.ui.*' \
  :shared:compileDebugKotlinAndroid :shared:compileKotlinIosArm64 \
  :shared:compileKotlinIosSimulatorArm64 --console=plain
```

С теми же JAVA_HOME/ANDROID_HOME/DEVELOPER_DIR: **BUILD SUCCESSFUL**; UI JVM9 тестов PASS (6 presentation +3 theme), Android и обе ARM Native main compilations PASS. Только прежние StoragePinsLock expect/actual Beta warnings. Source containment PASS, `git diff --check` PASS. Controller/state/ports/primitives/dependencies не менялись.

Открытый native regression scenario: на portrait/landscape/narrow и с IME/large-font прокрутить secondary controls от начала до конца — viewport сообщения, toolbar исправления и Speak/Stop не должны уезжать; отдельно прокручиваются длинное сообщение/снимки и grid. Проверить доступность всех трёх toolbar actions через touch, horizontal scroll, Tab/VoiceOver/TalkBack, особенно возврат focus и большой шрифт. Screenshots/runtime/semantic tree и физическая приёмка по-прежнему OPEN; устройства не запускались.
