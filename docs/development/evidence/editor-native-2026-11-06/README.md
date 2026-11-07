# Native editor: ввод, выделение и Cancel

Исходные отчёты и кадры сохраняются без замены RED на PASS. Android editor
save/publish/reopen и прежние iOS publish/rename/cold-open описаны в журнале;
здесь дополнительно разделены новые результаты выделения:

- `ios17-cancel-evidence.json`: shortcut replacement дал
  `NANCEL-CHECK-17ATIVE-CARD-17`, а не `CANCEL-CHECK-17` (**RED,34.595s**).
  Продолжение того же изменённого черновика → Cancel → SAVED → исходная
  опубликованная карточка/фраза прошло **1/1,30.349s**.
- `ios17-selection-*` и `selection-probe-v2-*`: два диагностических отказа
  на ошибочных guards/assertions, не product fixes. Три shortcut-команды исполнены,
  но эти первые кадры сами не устанавливают выделенный диапазон.
- `system-selection-blank-area-red.json`, `system-selection-on-word-red.json`:
  long press не открыл меню, payload не вводился.
- `system-selection-visible-range-red.json`: double tap → публичное Select All.
  **Всё название видимо выделено**, затем `app.typeText` воспроизвёл тот же дефект.
  Поэтому одного объяснения «shortcut ничего не выделил» недостаточно.
- `system-selection-real-key-evidence.json`: то же видимое полное выделение,
  но нажатие настоящей клавиши **ф** заменило название ровно на **ф**;
  Cancel/SAVED/опубликованная исходная карточка и фраза подтверждены:
  **1/1 PASS,42.857s**, без изменения продукта, clipboard или private injection.

Дифференциальный результат локализует сбой к XCTest injected-input пути в этой
проверке; точный внутренний механизм и аппаратная клавиатура остаются OPEN.
Это не принятие всего редактора, всего U1.4 или physical/AAC.

`system-selection-real-key-sequence-evidence.json` подтверждает отдельно
последовательность трёх настоящих клавиш: точные значения **ф → фы → фыв**,
затем Cancel/SAVED/исходная опубликованная карточка и фраза:
**1/1 PASS,45.529s**. Конечный текст просмотрен на исходном полном native PNG.
