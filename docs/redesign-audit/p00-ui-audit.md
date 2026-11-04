# P00/V0: ограниченный follow-up UI-аудит

## Текущий итог: focused follow-up выполнен частично, не G0/G1 PASS

Финальный узкий сбор: на tablet проверены space/empty radios, title+image, explicit synthetic TTS submit, Tab-доступ к action buttons, добавление карточки и save confirmation. **Новый собственный `P00TabletSave` сохранён и оставлен: библиотека tablet теперь содержит 7 наборов вместо 6.** Исходные шесть не редактировались/удалялись. Phone отключился во время ввода имени; новые phone radio/save проверки BLOCKED средой. G0/G1 в целом не PASS; physical/minimum OS UNKNOWN. Подробности — в разделе «Финальный узкий сбор».

История предыдущего follow-up: после первого сбора пользователь явно разрешил: «Да, отменить эти черновики» — только несохранённые `Audit14д` на phone и current new-board на tablet. Разрешение использовано; выполнен один ограниченный обход собственных новых несохранённых форм. Actual editor, форма карточки, генерация текстовой картинки, системный image picker/cancel и discard confirmation наблюдены на обоих размерах. На том этапе итоговые сохранения/публикации не выполнялись; save confirmation оставался UNKNOWN. Подробности и evidence — в разделе «Продолжение после разрешения» ниже.

## История первого сбора: BLOCKED, не PASS

Сессия остановлена до первого UI-ввода: на телефоне обнаружено уже заполненное имя `Audit14д`, происхождение которого в этой сессии не установлено. По условию «неожиданные данные — stop» существующее состояние не менялось. На планшете уже открыт экран создания набора; этот черновик также не создавался данной сессией. Его сохранение/сброс не выполнялись. Это ограничение проверки, не подтверждённый дефект приложения.

Base commit: `3deade7a9a9d82e6478b0a34d19c99ef204e5add`. Начальный `git status --short` пуст. Исходники и прежние docs/PNG не изменялись.

## Подтверждённая среда

Перед любым взаимодействием вызван `adb.list_devices`; подключены только два перечисленных эмулятора. Имя AVD, OS, установленная версия и геометрия повторно получены через adb MCP. UI-ввода затем не было.

| Serial | AVD | Android/API | Приложение | Physical size / density | Фактический screenshot viewport |
|---|---|---|---|---|---|
| `emulator-5554` | `linka_audit_phone` | 15 / 35, arm64 AVD по имени модели `sdk_gphone64_arm64` | `su.linka.pictures` 1.4 / versionCode 5, minSdk 21, targetSdk 35 | 1080×2400 / 420 dpi | 2400×1080, landscape, rotation 1 |
| `emulator-5556` | `linka_audit_tablet` | 15 / 35, arm64 AVD по имени модели `sdk_gphone64_arm64` | то же | 1600×2560 / 320 dpi | 2560×1600, landscape, rotation 1 |

Физические устройства и minimum OS не проверялись. **G0/G1 не объявляются PASS**.

## Новые визуальные свидетельства

Каждый PNG получен новым вызовом MCP `take_screenshot` и открыт через `read`; вывод не основан только на XML или имени файла.

1. [Phone: исходное состояние с fullscreen IME](screenshots/p00-followup/phone/01-entry-existing-name-dialog.png). Виден текст `Audit14д`, кнопка `DONE`, русская клавиатура и её микрофон (не нажат). Это **не actual editor**, не форма карточки и не доказательство доступности подтверждения сохранения. XML за IME сообщает диалог «Имя набора» с кнопками «ОТМЕНА»/«ГОТОВО»; визуально эти кнопки в PNG не подтверждены.
2. [Tablet: actual editor создания набора](screenshots/p00-followup/tablet/01-entry-existing-new-board-editor.png). Виден заголовок «Создать набор», back/save icons, unchecked «Вводить без пробела», значения 3 и 4, «ИЗМЕНИТЬ РАЗМЕР СЕТКИ», 12 плиток «Создать карточку», стрелки страниц. Это редактор набора, **не форма отдельной карточки**. Кнопки не нажимались; работоспособность не подтверждена.

Старый `phone/21-editor-new.png` не используется как новое доказательство: по переданному контексту там fullscreen IME, не actual editor. Старые PNG не менялись и не переименовывались.

## Воспроизведение сбора

Рабочий каталог: `/Users/aacidov/.local/share/opencode/worktree/67846f/pokazhi-p00-ui-audit`.

Вызовы через adb MCP, **не shell adb**, с явным serial:

```text
list_devices()
для serial = emulator-5554 и emulator-5556:
  execute_raw_adb({serial, args: ["emu", "avd", "name"]})
  execute_shell_command({serial, command:
    'getprop ro.build.version.release; getprop ro.build.version.sdk; wm size; wm density; dumpsys package su.linka.pictures | grep -E "versionName|versionCode"'})
  dump_ui_hierarchy({serial})
take_screenshot({serial: "emulator-5554", savePath:
  "<worktree>/docs/redesign-audit/screenshots/p00-followup/phone/01-entry-existing-name-dialog.png"})
take_screenshot({serial: "emulator-5556", savePath:
  "<worktree>/docs/redesign-audit/screenshots/p00-followup/tablet/01-entry-existing-new-board-editor.png"})
```

Путь входа в существующие экраны неизвестен: они были открыты до начала сессии. Доказательств воспроизводимости переходов из библиотеки этот сбор не даёт. Tap/keyevent/type_text/launch/stop и любые изменения данных не выполнялись.

## Observed vs expected / оставшийся объём

| Проверка | Expected для завершения проверки | Observed / статус |
|---|---|---|
| Новый собственный тестовый черновик | Создать в этой сессии без затрагивания чужого состояния | BLOCKED: уже открыты состояния неизвестного происхождения |
| Actual editor phone | Видна сетка и toolbar без fullscreen IME | BLOCKED: только IME поверх диалога имени |
| Actual editor tablet | Видна сетка и toolbar | Визуально OBSERVED; переход в редактор и взаимодействия не проверены |
| Форма карточки, оба размера | Открыть пустую карточку собственного черновика | BLOCKED, не открывалась |
| Локальная картинка из тестового текста | Ввести безопасный тестовый текст, увидеть результат | BLOCKED, генерация не запускалась |
| Picker / cancel / system import | Открыть системный picker и отменить без импорта | BLOCKED, не запускался |
| Save / discard | Увидеть доступные подтверждения только собственного черновика | BLOCKED; XML диалога имени не считается визуальной/поведенческой проверкой |
| Generate text / audio | Наблюдать доступные формы без записи голоса/host microphone | BLOCKED, формы не открывались |
| Error / empty library | Наблюдать безопасно достижимые состояния без удаления данных | UNKNOWN; не провоцировались |

Для продолжения нужен подтверждённый владельцем безопасный исходный экран либо явное подтверждение, что `Audit14д` и текущий планшетный черновик принадлежат этой проверке и их разрешено отменить. До этого не следует нажимать save/discard/back поверх неизвестного состояния. Никаких публикаций, удаления, clear/uninstall, изменений ОС или записи голоса не выполнялось.

## Проверка артефактов

`git diff --check` без ошибок. Оба PNG проверены по signature, CRC каждого chunk, успешной zlib-декомпрессии IDAT и IEND; размеры 2400×1080 и 2560×1600. Все Markdown-ссылки существуют. До staging tracked diff пуст, untracked содержат только этот документ и два новых PNG в разрешённом каталоге. Product build/tests не запускались: код не менялся.

## Продолжение после разрешения

Base продолжения: `2604991a41eed02b4ec6bbee46df1cc750c96e7d`. Локальная ветка `implementation/p00-ui-followup` создана через `git switch -c` после проверки отсутствия имени; чужие refs не перезаписывались. Первый сбор и его PNG сохранены как история.

### Среда и безопасный вход

Повторный `adb.list_devices()` вернул только `emulator-5554` и `emulator-5556`. Перед первым UI-вводом повторены `execute_raw_adb({serial,args:["emu","avd","name"]})` и диагностический `execute_shell_command` из первого сбора: имена `linka_audit_phone` / `linka_audit_tablet`, Android 15/API35, приложение 1.4/5 и physical size/density не изменились. Все UI/screenshot/type/back/launch действия — через adb MCP с явным serial; shell adb не использовался. Настройки ориентации не менялись: editor/card/picker landscape, phone library после выхода portrait (1080×2400), tablet library landscape (2560×1600).

Phone: Back скрыл fullscreen IME, «ОТМЕНА» (1600,660) закрыл разрешённый диалог `Audit14д`; следующий Back привёл к launcher. Приложение вновь открыто `start_app_activity({serial:"emulator-5554",packageName:"su.linka.pictures"})`, показана библиотека. Tablet: toolbar Up (70,110) → «Выйти из сохранения» → «ГОТОВО» (1720,835) → библиотека. Ни один сохранённый набор не открывался для изменения.

### Reproduction: одна focused wave

Координаты ниже в физических пикселях соответствующего serial, не уменьшенным картинкам из просмотра. `tap_screen`, `type_text`, `dump_ui_hierarchy`, `take_screenshot` — adb MCP. Back выполнен `execute_shell_command({serial,command:"input keyevent 4"})`. PNG сохранены по абсолютному пути `<worktree>/docs/redesign-audit/screenshots/p00-followup/...`; каждый визуально открыт через `read`.

| Шаг | Phone `emulator-5554` | Tablet `emulator-5556` | Actual outcome |
|---|---|---|---|
| Собственный новый набор | FAB (943,2260), `type_text("P00Phone")`, Back для IME, «ГОТОВО» (1800,660) | FAB (2456,1496), `type_text("P00Tablet")`, «ГОТОВО» (1720,550) без Back | Actual editor с сеткой 3×4, toolbar и save icon. На tablet первая попытка была отменена Back; PNG tablet/03 показывает библиотеку, не editor; повторный вход подтверждён tablet/04 |
| Форма карточки | Первая плитка (620,590) | Первая плитка (650,650) | Стандартный тип, альтернативы «Карточка пробел»/«Пустая карточка», поле названия, image/audio controls, trash icon. Пустые/пробельные режимы не переключались |
| Картинка из текста | Кнопка (1500,610), `type_text("P00 TEST")`, Back скрыл IME, «ГОТОВО» (1800,658) | Кнопка (1500,810), `type_text("P00 TEST")`, «ГОТОВО» (1720,550) | В форме появился растровый текстовый preview `P00 TEST`; это не AI/network image generation. Offline-режим отдельно не проверялся |
| Image picker → cancel | Кнопка выбора (1500,475), Back | Кнопка выбора (1500,700), Back; на tablet выполнено до генерации | Системный photo picker «No photos or videos»; возврат к той же форме. Phone preview после cancel сохранён. Albums/menu/media не открывались, файл не выбирался |
| Text-to-audio form | Кнопка (1200,760), снимок, «ОТМЕНА» (1600,660) | Только видимые controls в форме | На phone открыт пустой текстовый диалог «Создать звук их текста». Синтез/воспроизведение не запускались; «ЗАПИСЬ ЗВУКА» и микрофоны не нажимались |
| Отмена карточки и набора | Back из карточки → toolbar Up (70,130) → «ГОТОВО» (1800,600) | Back из карточки → toolbar Up (70,110) → «ГОТОВО» (1720,835) | На обоих «Выйти из сохранения» с «ОТМЕНА»/«ГОТОВО», затем библиотека без `P00Phone`/`P00Tablet` |

### Новые PNG продолжения: визуально проверенное содержимое

| Evidence | Что действительно видно |
|---|---|
| [phone/02](screenshots/p00-followup/phone/02-own-actual-editor.png) | Actual phone editor, не IME; 12 create-card tiles, rows 3/columns 4, save icon |
| [phone/03](screenshots/p00-followup/phone/03-standard-card-form.png) | Пустая standard card form, выбор картинки/генерация/аудио; нижние «ОТМЕНА»/«ГОТОВО» визуально отсутствуют, хотя XML их сообщает |
| [phone/04](screenshots/p00-followup/phone/04-generate-text-dialog.png) | Диалог изображения из текста с введённым `P00 TEST`, «ОТМЕНА»/«ГОТОВО» |
| [phone/05](screenshots/p00-followup/phone/05-generated-text-preview.png) | `P00 TEST` отрисован в preview, поле названия карточки остаётся пустым |
| [phone/06](screenshots/p00-followup/phone/06-system-image-picker.png) | Системный picker, Photos/Albums, «No photos or videos», без thumbnails |
| [phone/07](screenshots/p00-followup/phone/07-picker-cancel-preview-retained.png) | После отмены picker вернулась форма с прежним `P00 TEST` |
| [phone/08](screenshots/p00-followup/phone/08-text-to-audio-form-no-recording.png) | Пустой text-to-audio диалог, запись не запускалась |
| [phone/09](screenshots/p00-followup/phone/09-own-draft-discard-confirmation.png) | «Выйти из сохранения», «ОТМЕНА»/«ГОТОВО» поверх собственного editor |
| [phone/10](screenshots/p00-followup/phone/10-library-after-discard.png) | Portrait library после discard, шесть прежних наборов, тестового имени нет |
| [tablet/02](screenshots/p00-followup/tablet/02-authorized-draft-discard-confirmation.png) | Подтверждение отмены разрешённого пользователем исходного new-board |
| [tablet/03](screenshots/p00-followup/tablet/03-own-actual-editor.png) | **На самом деле библиотека, НЕ editor**, несмотря на имя: первая попытка имени отменена Back. Файл оставлен как прозрачная история, доказательство editor — tablet/04 |
| [tablet/04](screenshots/p00-followup/tablet/04-own-editor-confirmed.png) | Actual editor собственного нового набора, сетка 3×4 |
| [tablet/05](screenshots/p00-followup/tablet/05-standard-card-form.png) | Пустая standard card form; нижних «ОТМЕНА»/«ГОТОВО» визуально нет |
| [tablet/06](screenshots/p00-followup/tablet/06-system-image-picker.png) | Системный picker «No photos or videos», без thumbnails |
| [tablet/07](screenshots/p00-followup/tablet/07-picker-cancel-return.png) | Возврат к пустой форме карточки после Back из picker |
| [tablet/08](screenshots/p00-followup/tablet/08-generated-text-preview.png) | Текстовая картинка `P00 TEST` в форме |
| [tablet/09](screenshots/p00-followup/tablet/09-own-draft-discard-confirmation.png) | Подтверждение выхода без сохранения собственного набора |
| [tablet/10](screenshots/p00-followup/tablet/10-library-after-discard.png) | Библиотека с шестью прежними наборами после discard; тестового имени нет |

### Observed vs expected: итог продолжения

Минимальная цель actual phone editor + формы карточки обоих размеров достигнута. Для текстовой картинки expected — видимый результат введённого текста; actual — `P00 TEST` на обоих размерах. Для picker/cancel expected — возврат без импорта и потери текущего preview; actual подтверждён на обоих, сохранение preview — phone. Для discard expected — подтверждение и возврат без нового сохранённого набора; actual подтверждён на обоих. Это наблюдения конкретных переходов, не общий PASS продукта.

Визуальные замечания: на обоих размерах не видны нижние action buttons карточки, хотя phone XML содержал `cancel_button`/`positiveBtn`; визуально заметны разрывы «ВОСПРОИЗВЕДЕНИЕ» и опечатки «Название картлчки», «СОЗДАТЬ ЗВУК ИХ ТЕКСТА». Expected — читаемые labels и явные действия подтверждения/отмены. Причина layout/стилей не исследовалась, код не менялся; Back безопасно закрыл формы.

**UNKNOWN / не проверено:** финальное save и наличие отдельного save confirmation (toolbar save намеренно не нажат: может сразу записать набор); подтверждение/сохранение карточки кнопкой «ГОТОВО»; реальный импорт изображения/набора; error flow; пустая библиотека самого приложения (пустой photo picker не равен empty library); синтез/воспроизведение аудио, запись голоса; physical devices и minimum OS. G0/G1 по-прежнему UNKNOWN, не PASS. Никаких publication/delete/clear/uninstall, host microphone или настроек ОС.

Оба устройства оставлены в библиотеке. Список шести видимых сохранённых наборов совпал до/после обхода по XML labels (порядок между phone/tablet отличается). Saved boards не редактировались и final save не нажимался. Byte-level сравнение private app storage не выполнялось; возможные служебные временные файлы генерации отдельно не проверены.

Проверка артефактов продолжения: все 20 PNG первого и второго сбора визуально прочитаны; PNG signature/chunk CRC/zlib/IEND и размеры проверены программно, все ссылки существуют. `git diff --check` и staged diff check без ошибок. За пределами этого отчёта и новых follow-up PNG tracked изменений нет; прежние PNG неизменны. Product build/tests не запускались, исходники не менялись.

## Финальный узкий сбор

Base: `71b17cd7f2b4a8ec752f6db0e0558ca430fb3109`, та же ветка `implementation/p00-ui-followup` и worktree. Пользователь дополнительно разрешил сохранить **только новые собственные** `P00PhoneSave`/`P00TabletSave`, не удаляя результат. Это разрешение не распространялось на изменение прежних шести наборов, настройки ОС или микрофон.

### Подтверждение устройств и phone blocker

Перед вводом снова выполнены `adb.list_devices()`, `emu avd name`, диагностическая команда OS/API/size/density/package и `dump_ui_hierarchy`, везде явные serial. Подтверждены `emulator-5554` → `linka_audit_phone`, `emulator-5556` → `linka_audit_tablet`; Android 15/API35, версия 1.4/5, геометрия прежняя. Оба устройства сначала показывали библиотеку с исходными шестью наборами.

После FAB (phone 943,2260; tablet 2456,1496) отправлен параллельный `type_text` с `P00PhoneSave`/`P00TabletSave`. MCP вернул **`MCP tool "type_text" on server "adb" failed: Request timed out`**. Проверочный `dump_ui_hierarchy` вернул **`error: closed`**. Повторный `list_devices()` показал только `emulator-5556`. Состояние phone после ввода неизвестно; автоматического повтора, перезапуска, shell adb fallback, очистки или сохранения не было. Phone radio types/title/audio/save проверки в этом сборе **BLOCKED средой**, а не доказанным багом приложения. Новых phone PNG получить не удалось. На tablet повторно подтверждено имя AVD и в диалоге наблюдён ровно `P00TabletSave`, поэтому ввод не дублировался.

### Tablet: точные действия и actual outcomes

Все дальнейшие действия через adb MCP на `emulator-5556`, viewport 2560×1600 landscape / 320 dpi; координаты физические. Источники и UI hierarchy не модифицировались.

1. Имя собственного набора: `tap_screen(1720,550)` («ГОТОВО»), первая плитка `tap_screen(650,650)`.
2. Space radio: `tap_screen(1320,510)` → [11-space-card-type.png](screenshots/p00-followup/tablet/11-space-card-type.png). Визуально выбран «Карточка пробел», остальные controls формы остаются видны.
3. Empty radio: `tap_screen(1580,510)` → [12-empty-card-type.png](screenshots/p00-followup/tablet/12-empty-card-type.png). Визуально выбран «Пустая карточка», остальные controls остаются. Это проверка переключения/форм, не сохранения семантики этих типов.
4. Standard radio `tap_screen(1000,510)`; title `tap_screen(1200,600)`, `type_text("P00 TEST")`; Back (`execute_shell_command`, `input keyevent 4`) скрыл IME. Генерация картинки: `tap_screen(1500,810)`, `type_text("P00 TEST")`, «ГОТОВО» `tap_screen(1720,550)` → [13-standard-title-and-image.png](screenshots/p00-followup/tablet/13-standard-title-and-image.png). Видны standard radio, title и image preview `P00 TEST`.
5. Синтетическое TTS: явная кнопка `tap_screen(1280,970)`, `type_text("P00 TEST")` → [14-synthetic-tts-input.png](screenshots/p00-followup/tablet/14-synthetic-tts-input.png). Затем «ГОТОВО» `tap_screen(1720,550)` → [15-after-tts-playback-enabled.png](screenshots/p00-followup/tablet/15-after-tts-playback-enabled.png). Диалог закрылся, playback button стала активной (визуально teal, XML `enabled="true"`, ранее false), ошибок/запросов скачивания или network voice не появилось. **UI-level success**, не проверка качества/содержимого аудиофайла. Play/record/микрофон не нажимались, autoplay агентом не инициировался. Установки голосов/сети не менялись. Read-only `dumpsys texttospeech` и `get_logcat` с фильтром `TextToSpeech:V TTS:V GoogleTTS:V *:S`, limit 50, были пусты; конкретный engine/voice и offline-only характер синтеза независимо не подтверждены. Offline/audio validity остаются UNKNOWN, а не заявленный PASS.
6. **Ровно две bounded попытки action buttons:**
   - Scroll: `swipe_screen({x1:1100,y1:1070,x2:1100,y2:600,duration:450})` → [16-after-bounded-scroll.png](screenshots/p00-followup/tablet/16-after-bounded-scroll.png). Ничего не прокрутилось, нижние «ОТМЕНА»/«ГОТОВО» по-прежнему не видны.
   - Keyboard: 8 одиночных `execute_shell_command({serial:"emulator-5556",command:"input keyevent 61"})` (Tab) с проверкой `focused="true"` после каждого. Порядок: choose image → generate image → play audio → generate audio → record audio → delete → cancel → positiveBtn. Record/delete только получили фокус, **не активировались**. На 7-м Tab `cancel_button` сфокусирована, на 8-м — `positiveBtn` («ГОТОВО»). [17-tab-focus-card-done.png](screenshots/p00-followup/tablet/17-tab-focus-card-done.png) визуально показывает появившуюся при фокусе бледную кнопку «ГОТОВО». Затем `input keyevent 66` (Enter) активировал только её. Карточка добавилась в новый черновик → [18-pre-save-board-with-card.png](screenshots/p00-followup/tablet/18-pre-save-board-with-card.png), первая плитка содержит title и текстовую картинку. Координатных нажатий по невидимым action buttons, кликов за viewport, injection в hierarchy или модификации приложения не было.
7. Разрешённое сохранение **нового** набора: toolbar save `tap_screen(2496,112)` → [19-new-board-save-confirmation.png](screenshots/p00-followup/tablet/19-new-board-save-confirmation.png). Визуально «Подтвердить сохранение набора», «ОТМЕНА»/«ГОТОВО». После фиксации нажато «ГОТОВО» `tap_screen(1720,835)` → [20-library-after-new-board-save.png](screenshots/p00-followup/tablet/20-library-after-new-board-save.png). В библиотеке видны **`P00TabletSave` + исходные шесть**. Новый набор оставлен, не удалён и не переименован; повторное открытие и проигрывание карточки не выполнялись.
8. Узкая проверка доступности document import: library overflow `tap_screen(2500,110)` показал только «Настройки»; import item не обнаружен. Меню закрыто Back, настройки не открывались, системный document picker не запускался. Image picker из предыдущего сбора не считается проверкой импорта набора.

Каждый новый PNG 11–20 визуально открыт через `read`, имена сами по себе не использованы как доказательство. Абсолютный префикс сохранения: `/Users/aacidov/.local/share/opencode/worktree/67846f/pokazhi-p00-ui-audit/docs/redesign-audit/screenshots/p00-followup/tablet/`.

### Конечная матрица checked / blocked / unknown

| Остаток | Phone | Tablet | Ограничение / expected vs actual |
|---|---|---|---|
| Actual editor + standard card form | CHECKED предыдущим сбором | CHECKED | Новые данные не подменяют старую историю |
| Space/empty radio forms | BLOCKED: AVD disconnected | CHECKED: PNG 11/12 | Expected выбранный radio; actual выбран, controls формы сохраняются. Сохранение типов не тестировалось |
| Title `P00 TEST` + image | BLOCKED в финальном сборе; image был проверен ранее | CHECKED: PNG 13/18 | Title и preview видны; карточка добавлена в новый draft |
| Bottom actions, штатный scroll | BLOCKED: AVD disconnected | CHECKED: scroll не помогает | Визуальная проблема воспроизведена, baseline не исправлялся |
| Bottom actions, Tab/Enter | BLOCKED: AVD disconnected | CHECKED: Tab достигает cancel/done, Enter добавляет карточку | Не «полностью недоступны»: есть keyboard workaround, touch discoverability остаётся проблемой |
| Synthetic TTS submit | BLOCKED: AVD disconnected | CHECKED UI-level: playback enabled, ошибок не показано | Engine/voice/offline-only, качество/длительность/факт корректного audio UNKNOWN; playback не запускался |
| Save confirmation / новый saved set | BLOCKED: phone save не выполнялся | CHECKED: PNG 19/20, `P00TabletSave` оставлен | Tablet library сознательно изменена 6 → 7, не скрыто. Saved board after restart не проверялся |
| Discard | CHECKED предыдущим сбором | CHECKED предыдущим сбором | В финальном сборе tablet сохранён, не удалён |
| Document picker/cancel | NOT CHECKED | BLOCKED: из library menu видны только настройки | Нет broad поиска или открытия настроек; пользовательские файлы не выбирались |
| App empty library / real import / forced error | UNKNOWN | UNKNOWN | Не создавались destructive действиями; отсутствие photos в picker не равно empty library |
| Physical / minimum OS / общий G0/G1 | UNKNOWN | UNKNOWN | Не объявлять PASS |

Итоговое состояние: tablet на library с новым `P00TabletSave` и исходными шестью наборами; phone отсутствует в последнем полученном `list_devices`, состояние его несохранённого имени неизвестно. Исходные сохранённые наборы не открывались для изменения, не переименовывались/overwrite/delete. Продуктовый код, пароль и ОС не менялись. Возможные служебные файлы TTS/image и byte-level private storage не проверялись.

Артефакты финального сбора: только этот отчёт + 10 новых PNG tablet/11–20; старые PNG неизменны. PNG signature/CRC/zlib/IEND, размеры и все ссылки проверены, `git diff --check` / staged check без ошибок. Build/tests не запускались: продуктовый код не менялся. Никаких других агентов.
