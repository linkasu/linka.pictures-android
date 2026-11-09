# U1.4 — калибровка геометрии общения по same-device pair

Статус: локальная доводка принятого **направления**, не визуальное одобрение U1.4.
База worktree действительно `6a1c878`; исходно detached, создана ветка
`implementation/u14-geometry-calibration`. Контракт — `ui-1.4-parity.md`;
прочитаны также `u14-communication.md` и `u14-theme.md`.

## Измерения до изменения

Использованы только два новых исходных PNG из основного проекта:
`docs/progress-screenshots/2026-11-06-ui14/`:

| PNG | SHA-256 |
|---|---|
| `android-api35-baseline-compose-filled.png` | `a62db35756518bd4165714f25364617ae19c40de53a34a33583de74429e2bd27` |
| `android-api35-development-compose-filled.png` | `358acb881b29c665d662ff43dfbfcdf25d3b188873134f097e8cd0c17f680347` |

Оба **raw 2560×1600**, один API35 AVD, density320 (2 px/dp), fontScale1.0.
По переданному parent протоколу: неизменённая синтетическая «ХОЧУ» 3×3,
fixture SHA начинается `a0589…`, сообщение «ХОЧУ → КУШАТЬ», scoped data,
без пользовательских данных. Полный fixture SHA здесь повторно не проверялся.
В baseline showOutput был временно включён для пары и затем возвращён в false;
это не изменение принятой семантики DIRECT/COMPOSE в новом приложении.

Координаты ниже — включительные raw pixel coordinates. Цвета читались из
декодированных PNG scanlines без масштабирования/цветового преобразования;
независимая проверка ImageIO сообщает `kCGColorSpaceSRGB`, RGBA8, alpha255.
Viewer preview 2000px не использован для измерений. Это наблюдения, не пороги PASS.

| Область | Native 1.4, observed | Compose до доводки, observed | Источник / expected |
|---|---|---|---|
| Toolbar (без OS status bar) | y48…175 =128px =64dp | y48…159 =112px =56dp | `activity_grid.xml`: `?attr/actionBarSize`; AppCompat sw600dp=64, обычный56, phone-land48 |
| Разделитель, x100 | y479…480, teal/AA | y461…462, `#197377` | XML: output/grid weights1:4 **после** вычитания двух margin8dp и divider1dp |
| Серое backspace, x100 | y204…450; `(214,215,215)` | y176…444; `(224,224,224)` | Native drawable horizontal inset4dp, vertical6dp; не внешний padding hit target |
| Левый край backspace, y210 | x24…428 | x16…432 | Root8dp + drawable4dp; веса строки1:3:1:1, без дополнительных gaps |
| Первый message preview, x480 | nonwhite y206…442, с градиентом | white до y442, border y443…444 | `output_line.xml` padding6dp + `GridButton.onMeasure`: квадрат по доступной высоте |
| Preview, y430 | gradient; x480=`#D1D1D1` | x480=`#FFFFFF` | Тот же house Light gradient, что у карточек, не новая белая mini-card |
| Первая grid card, y600 | shadow x38; основной левый контур около40 | левый край16 | Root8dp + nested grid margin8dp + nine-patch visible inset около4dp |
| Верх grid card, x100 | первый nonwhite y499 | первый nonwhite y479 | Grid marginTop8dp; nine-patch top edge около1.5dp |
| Низ третьей строки, x100 | drawable до1573; y1572 shadow=`#B6B6B6` | после1519 white | Нельзя лечить padding-ом: новый host исключает нижнюю safe area, native рисует под navigation gesture |
| Grid surface, x100,y700 | `#D7D7D7` | `#D9D9D9` | Разные позиции в градиенте при разных bounds; не повод менять theme tokens |

Native baseline сверху имеет teal status bar, новая версия white. Это OS/native
root chrome; он намеренно **не подгоняется** этим изменением.

## Причина и минимальная правка

Прочитаны исходники тега `v1.4` (`2073d912`): `activity_grid.xml`,
`output_line.xml`, `grid_button.xml`, `GridActivity.kt`, `CardGrid.kt`,
`OutputLine.kt`, `OutputGrid.kt`, `GridButton.kt`, themes.
Дополнительно проверены уже локальные AppCompat1.7.0 AAR resources и SDK35:

- `abc_action_bar_default_height_material`: values56dp, land48dp,
  sw600dp64dp. Одна pure function выбирает высоту по реальному окну,
  до IME/safe-area padding, а не по fixture/названию устройства. Небольшой
  title/navigation inset8dp возвращает baseline положение glyph/title;
  Material paths и цвета доступного toolbar не заменяются bitmap-ами.
- У root padding8dp, фраза имеет marginBottom8dp, divider1dp,
  grid marginTop8dp. Production budget теперь вычитает фиксированные17dp
  **до** деления1:4. Для нескольких страниц остаются прежние paging actions,
  indicator и его дополнительный бюджет; поведение не переписано.
- В grid родителе ещё marginHorizontal8dp. `CardGrid` не задаёт gap между
  весовыми cells: видимый промежуток создаётся drawable, а не новым gap4dp.
- `btn_default_normal.9.png` xhdpi: 50×98 с markers; horizontal center имеет
  основной контур около x8 (после удаления marker — около3.5–4dp), top около
  y4 (1.5dp), bottom около y85 (6dp до края content drawable). Перенесены
  visible insets4/1.5/4/6dp, отдельно от уже существующих house content-padding
  10.5/6.5/11.5/11.5dp. Это не замена padding на произвольные40rawpx.
- Preview использует те же `CommunicationCardMeasurePolicy`, `Linka14`
  gradient/content padding/corner и `PrimitiveSurfaceChrome.border`, но
  **не** интерактивный `PrimitiveSurface`/`CommunicationCard`: нет onClick,
  button role, selected или fake disabled snapshot. Подпись — настоящий Text,
  изображение Fit, длинная подпись доступна через scroll, demand прежний.
- Gray actions: SDK35 `button_inset_horizontal_material`=4dp,
  `button_inset_vertical_material`=6dp. `drawBehind` меняет только рисунок,
  не уменьшает IconButton hit rectangle ниже48dp. Light цвет `#D6D7D7`
  измерен по этой паре; dark/HC сохраняют свои palette surfaces.
- Normal outline остаётся согласованным `#696969`, selected/focus прежние.
  Preview gradient только в DEFAULT Light; dark/HC не получают серый fallback.

## Expected после интеграции — ещё не observed

При тех же **новых** host insets окно1280×800dp даёт toolbar64dp и рабочую
область после safe areas/root padding664dp вместо672dp. Production budget:
phrase `(664−17)/5 =129.4dp`, grid517.6dp. После nested horizontal margins
grid width1248dp, три weighted outer cells416dp; видимая ширина каждой408dp.
Root8 + nested8 + drawable4 =20dp =40rawpx до основного grid края.

При неизменённом host ожидаются divider примерно y467, top первого grid
контура примерно y488, низ последней surface примерно1508. Это арифметический
прогноз с округлением Compose, **не измеренный screenshot result**. Native
baseline имеет примерно32dp больше вертикального content под gesture region:
это объясняет остаточный сдвиг divider около13rawpx и низа около64rawpx после
возвращения внутренних drawable insets. Safe-area контракт не ослабляется ради
совпадения последнего пикселя. COORD: parent проверяет реальные host insets и
снимает следующую пару; native root здесь не менялся.

Сетка по-прежнему рассчитывается из rows/columns, не из константы3×3.
Минимальная outer cell width56dp оставляет48dp внутри горизонтальных insets;
height budget64dp×max(1,fontScale) оставляет обычный hit target >48dp.
Narrow/dense/large-font layout прокручивается без смены числа ячеек.

## Совместимость и проверка

Единственный внешний caller `ui/app/LinkaApp.kt` и публичная сигнатура
`CommunicationScreen` не изменены. Внутренний caller `CommunicationControls`
передаёт рассчитанную высоту. `CommunicationGrid`/`CommunicationMessage`
сохраняют параметры. Session/commands/immutable snapshots/oneMessageDraft,
COMPOSE/DIRECT, PageUpDown activation, callbacks, notices/errors/UNCONFIRMED,
safe area/IME, image registry, ports/domain/storage не изменялись.
Theme/components читались и переиспользовались, но не редактировались.

Focused JVM, JDK17, один worker, offline, существующий локальный Gradle:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
GRADLE_USER_HOME=/Users/aacidov/.gradle \
/Users/aacidov/.gradle/wrapper/dists/gradle-8.11.1-bin/bpt9gzteqjrbo1mjrsomdt32c/gradle-8.11.1/bin/gradle \
  :shared:jvmTest --tests 'su.linka.pictures.ui.communication.*' \
  --offline --max-workers=1 -Dorg.gradle.java.installations.auto-download=false
```

Сохранены прежние15 тестов (layout expectations исправлены вместе с production
формулами); добавлены5: toolbar classes, реальные1:4 budgets, narrow/high-font
hit budgets, preview gradient Light vs dark/HC, action surface Light vs dark/HC.
Окончательный запуск: **20 tests PASS, 0 failures/errors** — Layout10,
Presentation7, Paging3; `compileKotlinJvm` и `compileTestKotlinJvm` успешны.
`git diff --check` чистый. Correctness review перед окончательным запуском
проверил: размер slot отдельно от видимого inset/hit target, отсутствие
неограниченного verticalScroll вокруг image/caption measure policy, palette
branch, один caller внутреннего controls и неизменность публичного API.

**Процессный инцидент:** первый запуск ошибочно использовал
`GRADLE_USER_HOME=/Volumes/data/developer-state/gradle-caches` вместо обычного
home с symlink `caches`. Wrapper автоматически скачал Gradle8.11.1, несмотря на
`--offline`; затем offline plugin resolution остановил запуск до компиляции.
Это непреднамеренное нарушение запрета downloads, не скрытый успешный gate.
После обнаружения использован только прежний установленный binary/cache.

Не запускались ADB/iOS/signing/native builds/FFmpeg/full gate или screenshot
renderer. Устройства оставлены parent. Настоящие native font rasterization,
nine-patch полупрозрачная тень/AA, hit/focus/large-text и итоговые bounds требуют
новой пары и физической проверки после интеграции. Visual PASS/полное совпадение
U1.4 и новый допуск в пикселях здесь не заявляются.
