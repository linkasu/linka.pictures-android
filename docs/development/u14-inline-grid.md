# U1.4: открытые параметры сетки

## Baseline и границы

Источник: `2073d912`, `SetEditActivity.kt` / `activity_set_edit.xml`,
`values-ru/strings.xml`; actual screenshot
`docs/redesign-audit/screenshots/p00-followup/tablet/04-own-editor-confirmed.png`.
В XML после checkbox расположены открытые `rows_count`, `columns_count`
(`inputType=number`) и `change_grid_size_button`, с равными весами 1/1/1.
Русская надпись: «Изменить размер сетки», на actual PNG — uppercase.
Activity заполняет поля из manifest и меняет сетку только по кнопке.
Fallback невалидного ввода старой Activity не переносится: действует frozen
application validation, а не обход безопасности ради visual parity.

Реализованы два inline underlined TextField, numeric keyboard hint и кнопка над
grid. Screen не хранит отдельный буфер чисел: значения берутся из Grid form
или текущей страницы, команды идут непосредственно в AuthoringSession.
Для узкой ширины/крупного шрифта пара полей остаётся вместе, кнопка переносится
ниже. Высота блока ограничена доступной высотой, содержимое прокручивается;
Apply/Cancel снимают focus и скрывают IME. Semantics называют строки/столбцы.
Единственный caller EditorGridDialog удалён вместе с неиспользуемым modal.
Card dialog, checkbox LITERAL/CARDS, caption и остальная навигация не менялись.

## Матрица actor и совместимость

`ChangeGrid(rowsText, columnsText, pageId: PageId? = null)` — единственный новый
seam. Двухаргументные Kotlin callers и OpenGrid/SaveGrid/CancelGrid сохраняют
source compatibility. Это не обещание binary ABI старого конструктора/copy:
Android/iOS consumers/frameworks нужно пересобрать после интеграции.

| Состояние / команда | Результат |
|---|---|
| Нет form, null pageId | Прежний no-op |
| Нет form, существующий pageId, bounded well-formed строки | Одна change/один snapshot с первым введённым значением |
| Нет form, отсутствующий pageId | INVALID_DATA, без изменения draft |
| Grid form, null или тот же pageId | Меняется только текст этой form |
| Grid form, другой explicit pageId | UNFINISHED_FORM, без замены страницы/формы |
| Card form | UNFINISHED_FORM, без card/media изменений |
| Пустой/нечисловой/числовой overflow текст | Допустимый autosaved unfinished draft |
| NUL/ill-formed UTF16/больше 65536 units | Прежний validText отказ до change/save |
| SaveGrid | Прежний parse, 1..64, overflow и page-count limits; атомарно document+form=null |
| CancelGrid | Документ/опубликованная доска не меняются |

Пока Grid form активна, board Apply, страницы и структурные UI actions
заблокированы; её собственные поля/применение/явная отмена доступны.
При Card form поля сетки отключены. editorPage уже выбирает страницу restored
form независимо от ранее выбранной страницы. Кнопка сетки до первого ввода
доступна как в baseline; SaveGrid без form — существующий no-op.

Писатель прежний: actor → change → coalesced saveDraft, один in-flight плюс
latest pending. Новых SQL, serialized fields, schema/version, locks, modes,
media writers или зависимостей нет. EDIT_BOARD owner/capability admission,
epoch, durable version, commit permit, conflict/unknown-commit quarantine,
background/reauth остаются прежними. Старый binary читает тот же EditorForm.Grid;
новый читает старые forms. DB/backup формат не меняется, миграции нет.

## Проверки и открытые gates

Regression сначала добавлен до реализации: после добавления API, но до изменения
actor `inlineGridFirstKeystrokeCreatesOnlyOneSnapshotWithoutResizing` падает
по ожиданию snapshot (старый actor игнорировал ввод без OpenGrid).

Добавлены 10 actor tests и один pure presentation test: первый ввод/последующее
изменение дают ровно по одному snapshot; valid/invalid text переживает actual
SQLite close/open/ResumeDraft; legacy no-op, чужая страница/Card form, unsafe text,
numeric bounds/overflow, 256-page limit, Cancel/atomic SaveGrid, cell/card/page
metadata, неизменность опубликованной доски/message snapshot, текущая durable
version, application authorization и permit на durable boundary.
Существующие tests не ослаблены.

Final focused JVM command (JDK17, существующие wrapper/cache, offline):

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
ANDROID_HOME=/Users/aacidov/Library/Android/sdk \
./gradlew :shared:jvmTest \
  --tests 'su.linka.pictures.application.authoring.*' \
  --tests 'su.linka.pictures.ui.*' \
  --offline --max-workers=1 --console=plain
```

Результат: **148/148 PASS**, failures/errors/skipped=0, BUILD SUCCESSFUL (14s).
Из них authoring **59** (прежние49 + новые10), UI **89** (включая новый1).
XML evidence: `shared/build/test-results/jvmTest/TEST-*.xml`.
`git diff --check` — PASS. После coherent-diff review проверены все callers:
ни EditorGridDialog, ни direct ResizeGrid в editor UI не осталось.

Actual matched-fixture PNG/IME/focus/large-font acceptance, native ABI/framework
rebuild и parent connected review остаются COORD gates. JVM/pure helper tests
не заменяют эти проверки. Devices, NativeFFmpeg, Xcode/signing, root bindings
и соседние lanes не затрагивались.
