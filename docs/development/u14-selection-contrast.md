# U1.4: минимальное отклонение ради контраста выбора

Основание: реальный кадр `docs/progress-screenshots/2026-11-06-ui14-editor/03-kushat-content-modal-over-grid.png` показывает жёлтый выбранный RadioButton на белом фоне. Брендовый `Linka14.accent = #FBCC30` даёт лишь **1.524:1**, ниже необходимых 3:1 для значимого индикатора выбора.

## Изменение

В `LinkaTheme` функциональные Material `secondary` и `secondaryVariant` теперь равны `palette.primary`, `onSecondary` — `palette.onPrimary` во всех четырёх темах. Реальный production mapping вынесен в package-internal чистую `linkaMaterialColors`, которую вызывает сама тема и проверяют common-тесты. В тёмной и обеих HC-темах значения уже были такими; меняется только обычная светлая тема.

`Linka14.accent` остаётся историческим BRAND-токеном. Единственный библиотечный FloatingActionButton получает явные `backgroundColor = Linka14.accent`, `contentColor = Color.Black` только в обычной светлой теме; в остальных темах сохраняет `palette.primary/onPrimary`. Контраст чёрного содержимого на жёлтом — **13.782:1**.

Не меняются геометрия, тексты, активация, guards, replay, состояние и данные. Светлый `primaryVariant = Linka14.primaryDark` не меняется: прямой essential caller в commonMain не найден; проверка не доказывает отсутствие использования внутри Material.

## Затронутые потребители

Поиск по `shared/src/commonMain` перед изменением:

- Стандартный RadioButton без собственного selected color: `CardEditorDialog`, `EditorSettingsDialog` (режим и ввод), `BoardSettingsDialog`.
- Стандартный Switch: `SettingsSwitch` в `SettingsScreen` (тёмная тема, высокий контраст, показываемые элементы).
- Явный `MaterialTheme.colors.secondary`: текст заголовка секции в `SettingsScreen`.
- Единственный FloatingActionButton: `LibraryScreen`; брендовый цвет сохранён явно.
- Саму `LinkaTheme` вызывают `LinkaApp` и `RootStatusScreen`.

## Контраст production-токенов после исправления

WCAG relative luminance, непрозрачные sRGB-цвета; округление до трёх знаков.

| Тема | secondary | Индикатор / surface | Индикатор / background | onSecondary / secondary |
| --- | --- | ---: | ---: | ---: |
| Светлая | #197377 | 5.585:1 | 5.585:1 | 5.585:1 |
| Тёмная | #66D9D9 | 9.505:1 | 10.661:1 | 7.800:1 |
| Светлая HC | #004747 | 10.541:1 | 10.541:1 | 10.541:1 |
| Тёмная HC | #8FFFFF | 17.916:1 | 17.916:1 | 17.916:1 |

Обычный светлый secondary проходит и 3:1 для RadioButton, и 4.5:1 для текста заголовка на белом. Тесты дополнительно сохраняют проверки палитры disabled/pressed, проверяют secondary на pressed/disabled поверхностях и onSecondary на pressed secondary. Алгоритмы pressed, disabled и Material opacity не изменены. Это не измерение отрисованного полупрозрачного switch track или disabled-индикатора.

## Проверка и границы

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew :shared:jvmTest --tests 'su.linka.pictures.ui.theme.*' --offline --max-workers=1 --console=plain
```

Успешно: **6 тестов**, 0 ошибок/пропусков. В этой исходной ветке `commonTest/ui/theme` содержит **4 прежних теста**, все сохранены; добавлены 2. Указанные в задании 11 прежних theme-тестов в этом пакете отсутствуют — согласовать с основной веткой при интеграции, не заявлять фиктивный прогон 13 тестов.

Новых зависимостей и Compose UI test framework нет. Native/Android build, установка, устройства и новый PNG не запускались: визуальная проверка остаётся у координатора. Требуется review основным агентом перед интеграцией. Это только документированное accessibility-отклонение от U1.4; **полный U1.4 остаётся OPEN**. Пользователь должен увидеть изменённые индикаторы выбора в визуальном дневнике, а не получить утверждение о полном принятии UI.
