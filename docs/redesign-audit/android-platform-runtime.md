# P01 / роль V: независимый Android runtime smoke

**Результат:** отдельный probe APK установлен и запущен на Android 15 / API 35 / ARM64; common UI, редактирование, focus/IME, SQLite write → close → reopen → read и Android callback наблюдались успешно. **Это не G1 PASS:** Android API 21 и ARMv7 runtime не проверены.

Дата: 4 ноября 2026. Write-root: `/Users/aacidov/.local/share/opencode/worktree/67846f/pokazhi-p00-android`. Base: `ce266410f46d5fa57de481dad2fb9dc2d3f31bb4`, исходно чистый detached HEAD. После проверки отсутствия ref создана новая branch `implementation/p01-android-runtime` командой `git switch -c`; существующие refs не перезаписывались. Этот отчёт не дублирует legacy inventory и не меняет product code, root configs или старые документы.

## Артефакт и предустановочные проверки

- Read-only APK: `/Users/aacidov/.local/share/opencode/worktree/67846f/pokazhi-p01-platform-smoke/app/build/outputs/apk/debug/app-debug.apk`.
- Переданный source commit: `c7eb3a2b5558c5653d0d6ed890fff07faf5a1f91`. Его probe-исходники прочитаны через `git show`, не изменялись. Сборка в этой проверке не запускалась; привязка бинарника к source основана на переданном build handoff и совпавшем SHA, не на независимой воспроизводимой сборке.
- `shasum -a 256 <APK>`: **`19ed34181dafd0127a71fc9c97d8ddf7d5b0d6c586091a0811cfac51d48274c4`**, точное совпадение с заданием.
- `/Users/aacidov/Library/Android/sdk/build-tools/35.0.0/aapt dump badging <APK>`: package **`su.linka.pictures.platformprobe`**, versionCode **1**, versionName **probe**, sdkVersion **21**, targetSdk **35**, compileSdk **35**, debuggable, launchable activity **`su.linka.pictures.ProbeActivity`**, native-code **arm64-v8a / armeabi-v7a**. Единственный uses-permission в badging — собственный `su.linka.pictures.platformprobe.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`; RECORD_AUDIO/сетевых permission в выводе нет. Наличие ARMv7 в APK не доказывает его runtime.
- Первым device-действием был `adb MCP list_devices`: найден только `emulator-5556`, status device, model sdk_gphone64_arm64. Все последующие device-вызовы явно задавали serial **emulator-5556**. `emulator-5554` не запускался, не перезагружался и не использовался.
- Через `adb.execute_shell_command`: `getprop ro.boot.qemu.avd_name` → **linka_audit_tablet**; `getprop ro.build.version.sdk` → **35**; `getprop ro.product.cpu.abi` → **arm64-v8a**; `getprop ro.build.version.release` → **15**. Только после этого выбран этот AVD для установки.
- Первоначальный общий read-only shell-запрос завершался `pm path su.linka.pictures.platformprobe`: инструмент сообщил `Error executing execute_shell_command`, сохранив четыре значения getprop выше. Ненулевой exit соответствует отсутствующему package path, но отдельно перепроверен, а не скрыт: `adb.list_packages(filter="su.linka.pictures.platformprobe")` → **No packages found matching the criteria**. Unknown pre-existing probe не обнаружен.
- `git show-ref --verify refs/heads/implementation/p01-android-runtime` до создания ветки сообщил отсутствие ref (`fatal: ... not a valid ref`); затем обычный `git switch -c` успешно создал ветку без force.

## Установка, OS и изоляция

Все install/launch/tap/input/screenshot/stop выполнены только через adb MCP, без host shell adb. Ни permission approval, ни root/reboot/network settings не требовались; native/tool denial не было.

1. `adb.install_app(serial="emulator-5556", apkPath=<APK>, reinstall=false, allowDowngrade=false)` → **Performing Streamed Install / Success**.
2. `adb.start_app_activity(packageName="su.linka.pictures.platformprobe", activityName="su.linka.pictures.ProbeActivity")` → **Starting: Intent { cmp=su.linka.pictures.platformprobe/su.linka.pictures.ProbeActivity }**.
3. Сразу сохранён и визуально прочитан [android01-launch.png](screenshots/platform-probe/android01-launch.png). Поле содержит default Unicode, статус ещё приглашает сохранить; фокус отсутствует, IME скрыта.
4. После тестов `getprop ro.build.fingerprint` → `google/sdk_gphone64_arm64/emu64a:15/AE3A.240806.043/12960925:userdebug/dev-keys`. Probe-only `dumpsys package su.linka.pictures.platformprobe | grep -E 'versionCode=|versionName=|primaryCpuAbi=|secondaryCpuAbi=|minSdk=|targetSdk='` → primaryCpuAbi **arm64-v8a**, secondaryCpuAbi **null**, versionCode **1**, minSdk **21**, targetSdk **35**, versionName **probe**.

Пакет **не** `su.linka.pictures`: baseline не устанавливался/обновлялся/запускался, его файлы, preferences и прежние семь наборов не читались и не менялись этой проверкой. Действия выполнялись только в UI нового probe и его новом sandbox. Микрофон, общение и production library не использовались; видимая иконка микрофона системной IME не нажималась.

## Точные UI-действия и наблюдаемые результаты

Координаты ниже — **реальные 2560×1600 px**, rotation=1. Первоначальный screenshot в просмотрщике был масштабирован: первый `tap_screen(240,325)` попал в подпись фокуса, **не кнопку**. Последующий `dump_ui_hierarchy` показал прежний initial status и bounds кнопки `[48,368][576,464]`; действие исправлено по реальным bounds, а не повтором предполагаемого SQL save. Ошибочное попадание не скрывается.

После каждого save критерий завершения — XML `dump_ui_hierarchy` с **обеими точными строками** SQLite readback и callback. Оба раза первый dump уже содержал ожидаемый статус, дополнительных попыток не требовалось. Fixed sleep и слепых повторов ввода/сохранения не было.

| Шаг | Точное действие / expected | Actual observation и доказательство |
|---|---|---|
| Default Unicode before | После запуска поле должно содержать `Привет, SQLite!`; SQL status ещё отсутствует | Видны `Привет, SQLite!`, `Поле без фокуса`, `Введите сообщение и сохраните его в probe SQLite`; [01 launch](screenshots/platform-probe/android01-launch.png) |
| Unicode save/read/callback | `adb.tap_screen(x=300,y=416)`, затем dump; expected exact сохранённое значение и callback | XML и визуальный [02 unicode-read](screenshots/platform-probe/android02-unicode-read.png): `SQLite прочитал: Привет, SQLite!` и `Android callback: Привет, SQLite!`. Поле без фокуса |
| Focus / IME | `adb.tap_screen(x=300,y=200)` | [03 focus-ime](screenshots/platform-probe/android03-focus-ime.png): фиолетовая рамка/курсор, `Поле в фокусе`, видима системная QWERTY IME. Предыдущий Unicode status не изменился |
| Replace field once | `adb.execute_shell_command("input keycombination 113 29")` (Ctrl+A); `adb.send_keyevent(67)` (DEL); `adb.type_text(text="P01 quote ' SQLite")` **ровно один раз** | Все три вызова успешны, без timeout. Следующий XML: EditText **точно** `P01 quote ' SQLite`, `focused="true"`, status ещё Unicode; [04 ascii-before-save](screenshots/platform-probe/android04-ascii-before-save.png) показывает новое поле, старый readback и IME |
| Quoted ASCII save/read/callback, clearFocus | `adb.tap_screen(x=300,y=416)`, затем dump; expected exact ASCII с U+0027 и переход focused true → false | XML и [05 ascii-read-clearfocus](screenshots/platform-probe/android05-ascii-read-clearfocus.png): `SQLite прочитал: P01 quote ' SQLite` и `Android callback: P01 quote ' SQLite`. EditText не меняет значение, `focused="false"`, подпись `Поле без фокуса`, IME скрыта. BACK или ручного hide keyboard между 04 и 05 не было |

Каждый из пяти PNG сохранён `adb.take_screenshot(savePath=<write-root>/docs/redesign-audit/screenshots/platform-probe/android*.png)` и **каждый визуально прочитан** инструментом read, а не только проверен по XML. Это meaningful before/after Unicode, отдельный focus/IME и before/after ASCII. Toast не зафиксирован отдельным снимком; успешный native callback наблюдается через возвращённый им common UI status, не через утверждение о визуальном Toast.

## SQLite: что именно подтверждает round trip

Read-only source trace для **c7eb3a2**:

- `app/src/probe/java/su/linka/pictures/ProbeActivity.kt:9-20`: host задаёт `getDatabasePath("platform-smoke.sqlite")`, создаёт parent directory; ComposeView вызывает общий `ProbeSmoke`; native callback вызывает Android Toast и возвращает `Android callback: $text`.
- `shared/src/probeMain/kotlin/su/linka/pictures/shared/ProbeSmoke.kt:22-49`: общий Material OutlinedTextField/state/focus; Button выполняет clearFocus, `database.saveAndRead(text)`, затем host callback. Exception превратился бы в `Ошибка probe: ...`, чего в наблюдаемых статусах нет.
- `shared/src/probeMain/kotlin/su/linka/pictures/shared/ProbeDatabase.kt:6-22`: BundledSQLiteDriver открывает БД, создаёт таблицу `probe`, выполняет bound `INSERT OR REPLACE ... VALUES (1, ?)` через bindText; connection use закрывается; **второй open** выполняет SELECT и проверяет равенство исходному тексту. Это не присваивание UI-строки вместо чтения, и обе проверки используют один id=1 с replacement.

Два точных UI readback/callback, вместе с этой реализацией, подтверждают выполнение write-close-reopen-read на данном runtime. Это не low-level trace sqlite calls, не durability после reboot/kill и не migration test.

Разрешённая отдельная read-only проверка наличия файла через adb MCP:

```text
run-as su.linka.pictures.platformprobe ls -l databases/platform-smoke.sqlite
-rw------- 1 u0_a209 u0_a209 12288 2026-11-04 21:29 databases/platform-smoke.sqlite
```

Только `ls -l` **этого** нового package: содержимое SQLite, baseline files/preferences и чужие sandbox не читались.

После evidence collection `adb.force_stop_app(packageName="su.linka.pictures.platformprobe", serial="emulator-5556")` → **force-stopped successfully**. Probe оставлен установленным вместе с тестовой БД; uninstall/clear data не выполнялись. Новых запусков после stop не было.

## Focused artifact / PNG checks

Python standard library проверила все пять PNG: signature, размер/границы каждого chunk, **CRC32 каждого chunk**, terminal IEND и отсутствие лишних байтов после него. Все — **2560×1600, CRC OK**. Никакого изменения или пересжатия скриншотов не было.

| PNG под `screenshots/platform-probe/` | Chunks | SHA-256 |
|---|---:|---|
| [android01-launch.png](screenshots/platform-probe/android01-launch.png) | 12 | `f1bfa0ff0b127824d9034c5bf4a55a3dd4f830e88be258e8159c5512db2f97ef` |
| [android02-unicode-read.png](screenshots/platform-probe/android02-unicode-read.png) | 13 | `56ddfc7d5d39b04609316bba88dcab6bfd4d1ba92ce475c8a0fd6c3e834500eb` |
| [android03-focus-ime.png](screenshots/platform-probe/android03-focus-ime.png) | 22 | `0bd1f265213c2f4514439ea780ad09771e3c40fab3ac5addba2ad06cfb8ddc6d` |
| [android04-ascii-before-save.png](screenshots/platform-probe/android04-ascii-before-save.png) | 22 | `d52e5730b3ab60fc0fae1e79bc2a0e908f22e46b0ffb3b4148231b02d1c75725` |
| [android05-ascii-read-clearfocus.png](screenshots/platform-probe/android05-ascii-read-clearfocus.png) | 13 | `73e80f22fb12f976b99e0790459b11d5a5e7c4c2690a1f63a3f5a6a892b5c0ea` |

Focused local report-link check выполнен Python assertions: **11 ссылок, 5 уникальных PNG, все существуют** внутри разрешённого screenshots directory. `git diff --check` — без ошибок. Набор новых файлов ограничен этим отчётом и пятью `android*.png`; staged `git diff --cached --check` и окончательный diff проверяются перед commit, actual results возвращаются в handoff вместе с commit, без самоссылочного hash в документе.

## Границы результата

- **Observed PASS только для описанного smoke на API35 ARM64**: реальный native Android host + common Compose UI + bundled SQLite + host callback; default Unicode readback; ASCII U+0027 readback; observable focus/IME/clearFocus.
- **G1 остаётся OPEN / НЕ PASS.** minSdk21 — packaged metadata, не API21 runtime. ARMv7 ELF в APK — artifact evidence, не ARMv7 runtime. Android21 ARMv7/ARM64, другие IME, accessibility/TalkBack, lifecycle/process death, cold read после перезапуска, reboot/durability, стресс/error paths, полнота Unicode/SQL security matrix, iOS14 и product regression не проверялись.
- Productcode/configs/CI не менялись, тесты не отключались, baseline data не трогались, emulator-5554 не запускался, permissions/network/root/credentials не менялись. Нет push, делегации или самостоятельной интеграции probe в product.
