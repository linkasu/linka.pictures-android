# U1.4 — восстановление экрана общения

Статус: реализация локального visual slice, **не визуальная приёмка**.
База `a008cd8`, зависимость image demand/paging `21bbbeb` (локально `699e092`).
Изменения ограничены `ui/communication`, его commonTest и этим журналом.

## Проверенный baseline

Полностью прочитан `ui-1.4-parity.md`; просмотрены PNG из его атласа:

- `phone/10-board-phrase-empty.png`: 2000×900, landscape. Toolbar сверху;
  удалить последнее слева, фраза по центру, речь и очистить справа;
  ниже разделитель и все девять карточек 3×3. Сетка примерно y=362…870.
- `tablet/11-board-phrase-filled.png`: 2000×1250, landscape. Та же иерархия;
  заполненная фраза примерно y=159…352, сетка y=390…1228.
- `tablet/12-board-settings.png`: компактный диалог параметров поверх доски,
  вход через шестерёнку в правом краю toolbar.

Это наблюдения в pixels, **не выдуманный dp/fontScale устройств**.
Исходники прочитаны через `git show 2073d912:`: `GridActivity.kt`,
`OutputLine.kt`, `CardGrid.kt`, `activity_grid.xml`, `output_line.xml`,
`grid_activity_menu.xml`, drawables backspace и record_voice_over.
XML даёт веса фраза/сетка 1:4, элементов фразы 1:3:1:1,
padding 8dp, разделитель 1dp; CardGrid использует равные строки и колонки.
Новые screenshots 2.0 как baseline не использовались.

## Что восстановлено

- Toolbar Back → existing `onOpenLibrary`, название доски, реальная optional
  шестерёнка settings и контекстное меню новых согласованных действий.
- Одна строка фразы над сеткой при обеих ориентациях. Backspace / фраза /
  Speak либо Stop / Clear на местах 1.4, с системными labels и disabled state.
  Статус playback доступен в semantics кнопки. Общая фраза видна и в DIRECT.
- Обычные saved/stopped/voice-ready не занимают строк. Ошибки, failed save,
  loading/saving/UNCONFIRMED и актуальная ошибка голоса остаются видимыми;
  длинное предупреждение прокручивается, не теряя текст.
- Режим, literal text insertion и UndoClear через меню. Text entry — настоящий
  IME TextField в диалоге с явным «Добавить», без автоматического Speak;
  значение поля сохраняется до явной очистки, как раньше.
- Сетка равномерных 1×1 ячеек рассчитывается из actual rows/columns и viewport,
  без фиксированных трёх колонок, автосортировки или crop изображений.
  Размеры не зависят от наличия bitmap. Demand регистрируют только composed
  grid rows / message parts через существующий registry; production loader
  из этого слоя не вызывается и не подменяется.
- Видимые paging arrows только для нескольких страниц и showPageButtons;
  indicator/custom accessibility actions и release-only PageUp/PageDown
  сохранены. ChangePage не создаёт Stop; mutations идут в прежний Session.

## Расчёт и цели снимков для primary

- Фраза: 1/5 высоты после toolbar/notices/insets, минимум 48dp×fontScale
  (при уменьшенном шрифте минимум 48dp). Поля 8dp, gap ячеек 4dp.
- Ячейки: `(width−gaps)/columns`, `(height−gaps)/rows`, минимум 48dp по ширине,
  64dp×max(1,fontScale) по высоте. При недостатке места прокрутка, не изменение
  числа строк/колонок; короткое окно дополнительно прокручивает фразу+сетку,
  сохраняя viewport хотя бы одной строки. Иконки не меньше 48dp.
- Pure JVM targets: сетка 784×208dp → 3×3 без overflow; 344×440,
  784×330 и 984×420dp используют весь бюджет. Это размеры **области сетки**,
  не утверждение о размерах эмулятора или измеренном референсе.
- Primary снимает синтетическую «ХОЧУ» 3×3 с пустой/заполненной фразой,
  phone/tablet portrait/landscape при одинаковых viewport и fontScale с 1.4;
  отдельно DIRECT, playback/Stop, несколько страниц с/без arrows, текст/IME,
  missing voice при попытке Speak, UNCONFIRMED и large text.

## COORD — обязательная интеграция вне этого scope

1. `LinkaApp.kt` пока вызывает `CommunicationScreen` старым способом. Primary
   подключает `onImagesNeeded` к принятому bounded loader, убирает eager demand
   всех изображений страницы и связывает trailing
   `onOpenBoardSettings: (() -> Unit)? = null` с настоящими house settings.
   Если callback null, шестерёнка отсутствует: no-op navigation не создаётся.
2. Theme/components lane должна заменить прежние min96/card square/image160,
   большой selected-header и padding defaults на bounded адаптивное содержимое
   `CommunicationCard`, сохранив её сигнатуру. Этот slice задаёт точный размер
   ячейки; прежний внутренний компонент ещё может переполнять маленькую ячейку.
   Цвета/пропорции карточек окончательно проверять после интеграции этой lane.
3. Platform Back остаётся обязанностью app host; toolbar использует existing
   callback и не меняет authority доступа. Настройки и их durable save не
   реализованы повторно в Compose.

## Проверка и пределы

Focused JVM: `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
./gradlew :shared:jvmTest --tests 'su.linka.pictures.ui.communication.*' --max-workers=1`.
Результат: **15 tests PASS** (Layout 5, Presentation 7, Paging 3).
Покрыты pure cell/vertical budgets, разбор vector paths, notices, прежние presentation и paging tests.
Нет изменений Session, storage, playback или security-контракта.

Не запускались Android install/devices, native/FFmpeg/full gate, physical
accessibility/IME проверки и новые screenshots. Нельзя считать compile/JVM
доказательством fit девяти карточек или визуального паритета: нужны coherent
theme/app integration, просмотр пары PNG и отдельное одобрение пользователя.
