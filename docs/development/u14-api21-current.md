# U14: текущий API21 phone gate — RED

## Единственный прогон, 2026-11-06

Frozen APK проверены локально **до** `install -r`; обе установки `Success`, без downgrade, очистки или uninstall. Исходники и сборка не менялись. Ветка `implementation/api21-current-acceptance`, исходный HEAD `a3230219bc3010004f81aa3baf5ee7af3fda5679`.

| Артефакт | SHA-256 |
| --- | --- |
| `app.apk` | `9e3eb90139a592d9aece5c5f1855b74d7e6b34531d6d7f870a95cdcaec0c0a79` |
| `appTest.apk` | `f8bd2277df88d44e221a78c46689ce0eba6ad04c0ca7d187b9d0e1414034d2c4` |

Источник обоих: `/private/var/folders/l4/r3plyjwj7pz_nq7zz50x04880000gn/T/opencode/u14-android-lint-verified-ky93ktc4/`. `aapt` подтвердил только development-пакеты `su.linka.pictures.development` и `.test`, minSdk 21, plain `android.app.Application`, отсутствие `FirebaseInitProvider`, выключенные legacy activities; manifest/badging сохранены рядом с evidence. Нормальный production APK не устанавливался; production package отсутствовал в списке до и после.

## Устройство и сохранность

- Работа только через ADB MCP с явным `serial=emulator-5554`. Начальный `list_devices` показал только чужой `emulator-5556`; к нему не обращались. iOS не трогали.
- До запуска проверены процессы и отсутствие слушателей 5554/5555. Выполнен **один** штатный boot существующего AVD:

  ```text
  /Volumes/data/developer-state/android-sdk/emulator/emulator -avd linka_platform_probe_api21_arm64 -port 5554 -no-snapshot-load -no-snapshot-save
  ```

- MCP подтвердил `sys.boot_completed=1`, SDK `21`, ABI `arm64-v8a`; `emu avd name` вернул `linka_platform_probe_api21_arm64` / `OK`.
- Phone: raw PNG **1080×1920, portrait**, 480 dpi; системная конфигурация `sw360dp w360dp h567dp`, `en_US`, effective font scale **1.0** (`settings ... font_scale` возвращает `null`, effective значение видно в `mConfiguration`). Шрифт/масштаб не менялись; конкретный runtime typeface не измерялся, font matrix не проверена.
- Нет wipe/reset, удаления AVD/locks, доступа к snapshots/Downloads, permission grants, PIN, network ADB, OS-настроек, SQLite/private API записей или security bypass. Существующие данные не очищались и не заменялись; разрешённая UI-подготовка и собственная временная fixture — единственная тестовая активность.

## Точная команда и терминальный результат

```text
am instrument -w -r \
  -e class su.linka.pictures.activity.DevelopmentParityTest#filledBoardThroughOwnedIncomingContent \
  -e u14FixtureName LINKa-U14-HOCHU-cfb199d.linka \
  -e u14FixtureSha256 a0589a029f77bfff7307d6ee3c4dbd41e1b2badd52ca90896ca5c835ccfb293b \
  su.linka.pictures.development.test/androidx.test.runner.AndroidJUnitRunner
```

Вывод направлен в собственный `/data/local/tmp/u14-api21-current-instrumentation.txt`, затем скопирован MCP pull. Терминальный маркер ожидался ограниченными проверками содержимого, не безусловной задержкой. Во время macro не было UIAutomator dump или дополнительных UI-действий агента; только passive screencap через `/data/local/tmp/screen.png` и pull, без `/sdcard` fallback.

- Запуск команды: **10:31:03 UTC**. TestRunner start **13:31:04.104 local** / **10:31:04.104 UTC**; окончание **13:32:55.273 local** / **10:32:55.273 UTC**. Часовой сдвиг согласуется с `date -u`; длительность runner **111.17 s** включает cleanup.
- Предусловия macro прошли: библиотека пуста, сообщение пусто. Собственная fixture SHA/read и native content intent прошли; notice получен без auto-import; имя подготовлено через UI, click выбора доставлен.
- Последний успешный stage: **`prepare-import-click-delivered`**.
- Последний await: **`real imported media preview`**. **`import-preview-observed` отсутствует**, до UI unlock выполнение не дошло.
- Фактическая ошибка:

  ```text
  java.lang.AssertionError: Timed out at real imported media preview;
  activePackage=su.linka.pictures.development (no text/password dump)
  ```

  Стек: `confirmImportAndCompose:211 → await:395`. Две suppressed cleanup ошибки: `Activity never becomes requested state "[DESTROYED]" (last lifecycle transition = "RESUMED")`.
- **`INSTRUMENTATION_STATUS_CODE: -2`**, **`FAILURES!!! Tests run: 1, Failures: 1`**, **`INSTRUMENTATION_CODE: -1`**. Последний код сам по себе не означает PASS; промежуточные status `0` — диагностические сообщения, не успех теста. `OK` для теста отсутствует.

## Что именно видно и чего нельзя заключать

[Evidence](evidence/u14-api21-current/20261106-owned-incoming/):

- `instrumentation.txt` — полный фактический terminal output.
- `test-logcat.txt` — TestRunner с временем, ошибкой и окончанием.
- `01-active-preview-wait.png` — настоящий development-экран **во время ожидания preview**, до cleanup: «Файлы и восстановление», имя «ХОЧУ», серые команды, нижняя часть проверочного текста обрезана краем viewport. Это не заполненная доска и не доказательство успешного preview/unlock. Точное время снимка отдельно не инструментировалось; он получен между началом macro и проверкой в 10:31:27 UTC.
- `02-cleanup-launcher-not-defect.png` — passive снимок в **10:31:27 UTC**, уже launcher при cleanup; **не экран дефекта**, не screenshot parity evidence.
- `device-after.txt`, `apk-verification.txt`, manifest/badging — параметры и идентичность.

Подтверждён **таймаут наблюдения preview** на этом phone viewport. Почему нужный AX-текст не был найден/проскроллен, этот прогон не установил; screenshot не доказывает дефект импорта, авторизации или unlock. Нельзя переносить прошлый RED «unlock недоступен» на этот новый результат: здесь стадия раньше.

Не достигнуты explicit UI unlock, confirmImportNew, заполненная 3×3 доска с 9 AX-карточками, «ХОЧУ КУШАТЬ», teal selection, geometry/parity и reopen. Повторного теста/импорта, подстановки сообщения и обхода root guard не было. SAF picker, cross-UID OS grant, AAC/playback, microphone, full phone/font matrix и пользовательское visual approval **не оценивались**.

## Передача обратно primary

Gate остаётся **RED**, source fix не выполнялся. Для следующей работы требуется отдельное решение primary по timeout preview/scroll и cleanup; новый прогон или переимпорт этим отчётом не разрешаются. Состояние после завершения не открывали и не редактировали: macro не достиг подтверждения импорта, окончательная библиотека повторно не инспектировалась.

На последней проверке **10:34:18 UTC** `emulator-5554` оставлен запущенным: `Awake`, display `ON`, foreground launcher после ActivityScenario cleanup; никакие настройки удержания экрана не менялись. До/после сохранены существующие данные AVD, snapshots и Downloads; пакет обновлён с сохранением app data. Другие устройства не тронуты.
