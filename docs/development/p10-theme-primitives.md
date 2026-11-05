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
