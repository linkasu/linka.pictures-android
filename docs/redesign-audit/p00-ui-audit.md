# P00/V0: ограниченный follow-up UI-аудит

## Текущий итог: focused follow-up выполнен частично, не G0/G1 PASS

После первого сбора пользователь явно разрешил: «Да, отменить эти черновики» — только несохранённые `Audit14д` на phone и current new-board на tablet. Разрешение использовано; выполнен один ограниченный обход собственных новых несохранённых форм. Actual editor, форма карточки, генерация текстовой картинки, системный image picker/cancel и discard confirmation наблюдены на обоих размерах. Итоговые сохранения/публикации не выполнялись; save confirmation остаётся UNKNOWN. Подробности и новые evidence — в разделе «Продолжение после разрешения» ниже.

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
