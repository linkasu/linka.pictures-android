# U1.4: тема и общие карточки

Статус: локальный visual slice, **не визуальное одобрение и не закрытие U1.4**.
Контракт: [ui-1.4-parity.md](ui-1.4-parity.md). Основа ветки — `a008cd8`.

## Происхождение оформления

Источник — тег `v1.4`, commit `2073d912f6c70ab0165823f37e4b5704cf72e953`:

| Источник в теге | Значение / перенос |
|---|---|
| `app/src/main/res/values/colors.xml` | primary `#197377`, primaryDark `#BED64F`, accent `#FBCC30`. Не кандидат `#006A6A` |
| `app/src/main/res/values/themes.xml` | `Theme.MaterialComponents.Light.NoActionBar`, primary toolbar, accent для secondary. Сохраняется M2, без новой типографической системы |
| `app/src/main/res/layout/grid_button.xml` | margin `0.2dp`, `@android:drawable/btn_default`, вертикальные веса image/caption `4:1` |
| `app/src/main/java/su/linka/pictures/components/GridButton.kt` | В теге подпись — bitmap; здесь намеренно настоящий `Text`, normal sans-serif, без ellipsis и без обрезки изображения |
| `shared/src/androidMain/kotlin/su/linka/pictures/Utils.kt` | `textAsBitmap` использует `Paint`, обычный шрифт и **чёрный** текст. Чёрный foreground карточки — baseline, не произвольная контрастная коррекция |
| `app/src/main/java/su/linka/pictures/components/CardGrid.kt` | Размер ячеек определяется весами родительских строк/столбцов, не константой 96 dp |
| `app/src/main/res/layout/output_line.xml` | Кнопки/фраза `1:3:1:1`, output-grid padding `6dp`; это контракт communication-lane, здесь экран фразы не меняется |
| `app/src/main/res/layout/set_grid_button.xml` | Подпись библиотеки `14dp`, white поверх `#99000000`; реализация принадлежит library-lane |

Просмотрены phone/10, tablet/11, tablet/02b и p00-followup/tablet/04,05
из атласа. Точные размеры исходных PNG: phone `2400×1080`, tablet `2560×1600`.
Не следует снимать координаты с уменьшенной картинки в просмотрщике.

У `btn_default` нет собственного XML радиуса/цветов в теге: это Android drawable.
Для воспроизведения хрома использованы sRGB-пиксели PNG, а не преобразованный
`NSColor.deviceRGB`: toolbar `RGBA(25,115,119,255)`; нижняя поверхность карточки
tablet/11 `(128,832..841)` — `(209,209,209,255)`, нижний контур `(128,846)` —
`(119,119,119,255)`, верхний контур `(128,500)` — `(215,215,215,255)`.
Светлый конец градиента `#E8E8E8` также есть в phone/10 `(120,435)`.
У disabled-кнопки формы p00-followup/tablet/05 фон `#E0E0E0` (например,
`(896,973)`), самый частый цвет полных штрихов disabled-текста — `#8B8B8B`.
`Linka14` хранит эти нейтральные цвета, а не ещё один бренд.

В локальном Android SDK API35 `drawable/btn_default.xml` выбирает
`btn_default_normal.9.png`. Его xhdpi content markers дают padding
`21/13/23/23px` (left/top/right/bottom), то есть `10.5/6.5/11.5/11.5dp`.
Оба baseline-устройства используют xhdpi asset (tablet 320 dpi, phone 420 dpi).
Он переносится как content padding; focus-ring больше не добавляет 6 dp снаружи.
Compose-контур 0.5 dp и скругление 2 dp воспроизводят компактный drawable,
**но линейный градиент и контур не являются побитовой копией nine-patch/его тени**:
их окончательная точность требует PNG-пары, не вывода из unit tests.

## Геометрия и совместимость

- `CommunicationCard`, `LabeledActionButton`, `LinkaTheme`, `LinkaPalette` сохраняют
  параметры, defaults параметров и типы. В конструктор palette поля не добавлены.
- `LinkaTargets.communicationCard/actionButton` теперь **48 dp**, общий минимум
  Android 48 dp / iOS ≥44 pt. Это не рекомендуемый размер сетки: больший размер
  задаёт родитель из baseline. Нет жёсткого 3×3 или самостоятельного расширения
  ширины/высоты в ограниченной родителем ячейке.
- Общая measure policy сначала измеряет переносимую подпись, затем отдаёт остаток
  изображению. При ограниченном height подпись получает примерно 1/5 content area
  в одну строку при обычном масштабе; перенос/large text забирает место у image.
  В неограниченном по высоте прежнем caller остаётся квадрат изображения от ширины,
  без прежнего cap 160 dp. `ContentScale.Fit` сохранён.
- Нет отдельной постоянной строки ✓, промежутка 8 dp и дополнительного ring-gap.
  Выделение видно по контуру и `selected` semantics, не меняет hit geometry.
- Для очень крупного текста, которому уже недостаточно целой ячейки, parent обязан
  предоставить адаптивный/прокручиваемый бюджет. Эта ветка не меняет parent-grid.
- `systemFontScale × portableTextScale`, ограничение только portable scale,
  dark/high-contrast palettes и непрозрачный disabled foreground сохранены.
  Pressed композится поверх каждого конца серого градиента, не поверх белого.
- `clickable`, interaction source, keyboard/cancel, labels, merged semantics и
  управление фокусом не переписаны. IconButton wrappers/зависимости не добавлены.
- Действия используют M2 button typography и видимый uppercase, как MaterialButton
  тега; исходный accessibility label и текст данных не преобразуются.

Затронутые, но **не изменённые** callers: `CommunicationGrid`, `EditorPageCells`,
`CommunicationMessageActions`, `CommunicationControls`, `CommunicationScreen`,
`EditorScreen`, `AuthoringFeedback`, `CardEditorScreen`, app `ProductScaffold`/
`ProductDialog`/`Action`, а также `LinkaApp` и потребители palette (field colours).
Их layout/toolbar/icon действия интегрируют отдельные lanes. `RootStatusScreen.kt`
в этом base отсутствует и не создавался.

## Конкретные противоречия baseline / доступности — решение OPEN

Все пары ниже — непрозрачный sRGB (`alpha=255`), WCAG relative luminance:

| Foreground / background | Контраст | Решение в этой ветке |
|---|---:|---|
| `#FFFFFF / #197377` | 5.585:1 | Exact primary + white сохранены, обычный текст проходит 4.5 |
| `#000000 / #FBCC30` | 13.782:1 | Для accent используется black, не white |
| `#FFFFFF / #BED64F` | 1.625:1 | Исторический primaryDark сохранён; **не назначать ему белый текст/иконки status bar без отдельного решения** |
| `#777777 / #D1D1D1` | 2.932:1 | Exact нижний контур; ниже 3:1 для единственной границы контрола |
| `#D7D7D7 / #E8E8E8` | 1.175:1 | Исторический светлый верхний контур также не даёт 3:1 |
| `#8B8B8B / #E0E0E0` | 2.581:1 | Слабый disabled текст формы baseline **не воспроизведён**: существующий доступный `disabledText=#435B5B` сохранён (5.512:1) |

Сохранение доступного disabled foreground — сохранение прежнего a11y-контракта,
не отключение alpha/contrast tests. Высококонтрастный режим не получает серый
градиент и использует прежний контрастный контур. Пограничный серый контур в DEFAULT
**не исправлен самовольно** и не замаскирован ослаблением существующих tests.
Если для DEFAULT требуется 3:1 по всему контуру во всех состояниях, минимальное
отличие — только единый outline `#696969`: 3.026:1 на самом тёмном pressed-конце
`#D1D1D1` с black overlay 8%; следующий светлее `#6A6A6A` уже 2.982:1.
**Эта коррекция не применена, требуется решение primary/пользователя**, не замена
палитры. Yellow accent `#FBCC30` на white — 1.524:1;
его также нельзя использовать как единственную информативную границу без contrast
review в caller.

## Проверка

Focused JVM (JDK17, offline, один worker):

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  ./gradlew :shared:jvmTest \
  --tests 'su.linka.pictures.ui.theme.*' \
  --tests 'su.linka.pictures.ui.components.*' --offline --max-workers=1
```

Существующий тест normal/disabled/pressed contrast всех четырёх palettes не ослаблен.
Результат focused JVM: **9 tests, 0 failures/errors**.
Добавлены exact baseline colors и opaque pressed gradient; прежняя статическая
assert96 заменена platform-minimum и тестом **production measure policy** с реальным
остатком после content padding, разными пропорциями ячеек, wrapped caption heights,
неограниченной высотой/parent minimum и загрузкой изображения.
Это проверка layout-budget, **не screenshot render или физическая a11y-приёмка**.

Не запускались full gate, native/FFmpeg, загрузки, сборки устройств или установка.
PNG-пары Android/iOS, фактические шрифты, large text, keyboard/TalkBack/VoiceOver и
визуальное решение по DEFAULT-outline остаются за основной интеграцией U1.4.
