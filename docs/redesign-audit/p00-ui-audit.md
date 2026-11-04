# P00/V0: ограниченный follow-up UI-аудит

## Итог: BLOCKED, не PASS

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
