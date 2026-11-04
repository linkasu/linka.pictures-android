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

## Дополнение: разрешённая попытка Android21 ARM64 — остановка до создания AVD

Это отдельная принятая задача после явного разрешения пользователя скачать AOSP API21 ARM64 и создать **новый** AVD `linka_platform_probe_api21_arm64`; iOS17 выполняется отдельно, здесь не исследовался. Base этой попытки: `a4dd51d56b6f56e1ce105c6ed0314c4732b1b7b1`, ветка `implementation/p01-android-runtime`, исходно чистая. История API35 выше оставлена без изменений.

**Итог по стадиям:** downloaded **YES**, SDK installed **YES**, AVD created **NO**, booted **NO**, API21 probe installed/launched **NO**, API21 runtime passed **НЕ ПРОВЕРЕН**. Не обнаружена ошибка приложения или доказанная несовместимость API21 kernel с host: до boot дело не дошло. G1 остаётся **OPEN / НЕ PASS**; физические устройства, AAC и ARMv7 также открыты.

### Host / инструменты / предварительные проверки

- Первым действием — `adb MCP list_devices`: только `emulator-5556`, device, sdk_gphone64_arm64, transport_id 11. Повторный list после остановки дал тот же единственный device. Никаких install/launch/input/stop действий на нём в этой попытке не выполнялось.
- `uname -m` → `arm64`; `sw_vers` → macOS **27.0.1**, build **26A434**.
- `/Users/aacidov/Library/Android/sdk/emulator/emulator -version` → **37.2.12.0**, build_id **16428233**.
- `command -v sdkmanager` / `command -v avdmanager` → `/opt/homebrew/bin/sdkmanager` / `/opt/homebrew/bin/avdmanager`; `sdkmanager --sdk_root=/Users/aacidov/Library/Android/sdk --version` под JDK17 → **20.0**. Каталог `/Users/aacidov/Library/Android/sdk/cmdline-tools` отсутствует (первичный `ls` сообщил `No such file or directory`), новые tools не устанавливались.
- `ls -l /opt/homebrew/bin/avdmanager` → symlink `/opt/homebrew/share/android-commandlinetools/cmdline-tools/latest/bin/avdmanager`. У Homebrew SDK есть собственный `system-images/`, отдельный от `/Users/aacidov/Library/Android/sdk/system-images/`.
- `emulator -list-avds` до и после попытки: `bproxy_api35`, `linka_audit_phone`, `linka_audit_tablet`. Новый task-owned `.ini` и `.avd` каталог под `/Users/aacidov/.android/avd/` отсутствовали и остались отсутствующими. Ни `--force`, ни overwrite/delete существующих AVD не применялись.
- `avdmanager list device` подтвердил штатный профиль **Nexus 5** (id 17), выбранный для создания, без custom kernel/architecture.
- APK повторно read-only проверен до любых возможных install: `shasum -a 256 <тот же app-debug.apk>` → **`19ed34181dafd0127a71fc9c97d8ddf7d5b0d6c586091a0811cfac51d48274c4`**. `aapt dump badging` повторно подтвердил **su.linka.pictures.platformprobe**, versionCode **1**, versionName **probe**, sdkVersion **21**, targetSdk **35**, activity **su.linka.pictures.ProbeActivity**, ARM64/ARMv7. Бинарник не пересобирался и не изменялся.

### Download / install: выполнено без принятия новых лицензий

Read-only запрос официального repository manifest `https://dl.google.com/android/repository/sys-img/android/sys-img2-3.xml` через Python urllib/ElementTree подтвердил точный remotePackage:

```text
path: system-images;android-21;default;arm64-v8a
api-level: 21; tag: default; abi: arm64-v8a; revision: 4
archive: arm64-v8a-21_r04.zip
size: 211426314
checksum type=sha1: c4375f1b4b4cd21a8617660e25f621cedcbd8332
uses-license ref: android-sdk-license
```

Выполнена только точная согласованная установка, stdin закрыт; `yes`, `--licenses`, sudo/chmod или обход permissions не использовались:

```sh
env JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  sdkmanager --sdk_root=/Users/aacidov/Library/Android/sdk \
  --install 'system-images;android-21;default;arm64-v8a' </dev/null \
  > /private/var/folders/l4/r3plyjwj7pz_nq7zz50x04880000gn/T/opencode/p01-api21-sdk-install.log 2>&1
```

Процесс выполнялся background; лог прочитан **только после автоматического completed notification**, без polling/sleep. Лог прошёл Downloading → Unzipping → **100% Unzipping... arm64-v8a/system.img**. Проверка строк error/warning/license/accept не выявила prompt/ошибок; новых лицензий не принималось. Исходный ZIP отдельно не сохранялся/не хешировался этой проверкой: SHA-1 выше — значение официального manifest, не независимо вычисленный digest загруженного ZIP.

После completed в разрешённом SDK существует `system-images/android-21/default/arm64-v8a/`: `system.img`, `userdata.img`, `ramdisk.img`, `kernel-qemu`, `kernel-ranchu`, `source.properties`, `package.xml`, `NOTICE.txt`, `advancedFeatures.ini`. `source.properties` подтверждает `Pkg.Desc=Android SDK Platform 5.0.2`, `Pkg.Revision=4`, `AndroidVersion.ApiLevel=21`, `SystemImage.Abi=arm64-v8a`, `SystemImage.TagId=default`; `package.xml` localPackage совпадает с exact package и revision.

| Установленный файл | Bytes | Вычисленный SHA-256 |
|---|---:|---|
| source.properties | 177 | `3560ad660a4a4871846e89ce6ed4d27c7009e113f02b378aa3220e5dc28ec552` |
| package.xml | 18757 | `cb9fc4595a5179df6a4544e14236236e87ccc2956ec5a5b57c3e077e2bb6b9af` |
| kernel-qemu | 5861888 | `ae7ff689686b66aca0c84fa1f9e57fc35389fe683605bb1946fa610a1356376b` |
| kernel-ranchu | 7748096 | `3112e2210d65c276b067e6cfdd1c26caa95e45061cb08893cccd03f82f76407b` |
| ramdisk.img | 990305 | `e5a9cbbe6541384168c877f563ad78c62133f469af55599226ba2995efe5d0aa` |

`file kernel-qemu kernel-ranchu` определил **оба** как `Linux kernel ARM64 boot executable Image, little-endian`. Это формат kernel, не доказательство поддержки нынешним Emulator/macOS.

### AVD creation: actual error и STOP

```sh
env JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  ANDROID_HOME=/Users/aacidov/Library/Android/sdk \
  ANDROID_SDK_ROOT=/Users/aacidov/Library/Android/sdk \
  avdmanager create avd --name linka_platform_probe_api21_arm64 \
  --package 'system-images;android-21;default;arm64-v8a' --device 'Nexus 5' </dev/null \
  > /private/var/folders/l4/r3plyjwj7pz_nq7zz50x04880000gn/T/opencode/p01-api21-avd-create.log 2>&1
```

**Exit 1**, точный существенный stderr/stdout из объединённого лога:

```text
Loading local repository...
[=========                              ] 25% Loading local repository...
Error: Package path is not valid. Valid system image paths are:
system-images;android-35;google_apis;arm64-v8a
null
```

Подтверждённый факт: установленный API21 package присутствует в согласованном SDK, но этот вызов avdmanager его **не обнаружил**, несмотря на ANDROID_HOME/ANDROID_SDK_ROOT. Связь с отдельным Homebrew SDK-root — обоснованная **гипотеза по расположению launcher и перечисленному API35**, не отладка реализации avdmanager. Read-only `avdmanager --help` не перечисляет глобальный `--sdk_root` (есть silent/verbose/clear-cache/help); clear-cache не запускался.

На этом остановлены provisioning и device действия: tools/configs не менялись, symlinks не создавались, образ не копировался в другой SDK, дополнительные cmdline-tools/другие image/ABI/версии не устанавливались, custom AVD config не писался. Для продолжения нужен отдельно согласованный способ использовать установленный image штатным AVD manager без выхода за разрешённые tooling writes.

Boot-команда **не запускалась**, поэтому `sys.boot_completed=1` не наблюдался, новый serial/AVD/API/abilist не подтверждён и никаких предположений о владельце serial5554 не делалось. `linka_audit_phone`, tablet и их data не reset/kill/reboot/clear/uninstall. Новых `android21*.png` нет, поскольку нет нового запущенного UI; прежние PNG не переименовывались в API21 evidence.

### Проверка append-only и ограничения

Изменение этой попытки — **только добавление данного раздела** к существующему отчёту; разрешённые screenshots API21 не создавались. Focused checks перед local commit: сохранность byte-prefix отчёта из base, отсутствие изменений старых PNG/других файлов, существование прежних report links, `git diff --check` и staged diff-check. Actual results и final commit возвращаются в handoff. Полные SDK install/create логи оставлены по точным temp paths выше; они не являются source-controlled artifacts.

Не выполнялись boot, API21 install/launch/SQLite/callback/focus/IME, physical/AAC/ARMv7, iOS runtime или новые app patches. Успешная SDK-установка не выдаётся за созданный/загруженный AVD или runtime PASS; предыдущий API35 result не расширяется на API21.

## Дополнение: co-located tools20 → API21 ARM64 boot и partial runtime

Base продолжения: `fa17623d47c761ad3960a8451080ff887d59a447`, та же ветка, чистый worktree. COORD явно разрешил **дополнительный exact `cmdline-tools;20.0`** в `/Users/aacidov/Library/Android/sdk`, чтобы штатный avdmanager находился рядом с согласованным image, без Homebrew/global config/symlink изменений. Старые разделы выше — история, не актуальное утверждение об отсутствии AVD после этого продолжения.

**Текущий итог:** image downloaded/installed **YES**; tools20 installed **YES**; новый AVD created **YES**; booted **YES**, `sys.boot_completed=1`; probe installed/launched **YES**; Unicode и quoted ASCII SQLite write-close-reopen-read + Android callback **observed PASS**; **clearFocus/IME hide — observed FAIL**. Полный runtime smoke и G1 **не PASS**. API21 ARM64 действительно исполнен, но это **не ARMv7 / physical / AAC / iOS proof**.

### Exact SDK-root fix и metadata

Первое device-действие продолжения — MCP list_devices: только старый tablet `emulator-5556` (transport_id 11). Повторные SHA/aapt проверки APK до установки: hash **`19ed34181dafd0127a71fc9c97d8ddf7d5b0d6c586091a0811cfac51d48274c4`**, package **su.linka.pictures.platformprobe**, min21, target35, version1/probe, оба ARM ABI; источник `c7eb3a2` и binary не менялись.

```sh
env JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  sdkmanager --sdk_root=/Users/aacidov/Library/Android/sdk \
  --install 'cmdline-tools;20.0' </dev/null \
  > /private/var/folders/l4/r3plyjwj7pz_nq7zz50x04880000gn/T/opencode/p01-api21-tools20-install.log 2>&1
```

Дождались automatic completed notification background install, затем прочитан лог: **100% Unzipping... cmdline-tools/NOTICE**, без новых license prompts/errors/warnings. `yes`, accept-licenses, sudo/chmod не использовались. Co-located `/Users/aacidov/Library/Android/sdk/cmdline-tools/20.0/bin/sdkmanager --version` → **20.0**, не latest и не upgrade.

| Файл под `SDK/cmdline-tools/20.0/` | SHA-256 |
|---|---|
| source.properties | `215e11e90893196549e86dfd6a024f20848322dc7a3d694bc4847b8e6d849ad1` |
| package.xml | `a812bf4987ebc2991af0a1b5e7e36374c9f8668dc2441440aadedc114ec6b5ee` |
| bin/avdmanager | `a3ae9bdf8302aafed80115e2156039766ed309bcf7025f289c3a21e274b9279c` |

source.properties: `Pkg.Revision=20.0`, `Pkg.Path=cmdline-tools;20.0`, `Pkg.Desc=Android SDK Command-line Tools`. Это установленная local metadata, не независимо вычисленный digest distribution ZIP.

### Новый AVD / boot criterion / serial ownership

До create повторно проверено отсутствие task-owned `.ini`/`.avd`; выполнено **без force**:

```sh
env JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  ANDROID_HOME=/Users/aacidov/Library/Android/sdk \
  ANDROID_SDK_ROOT=/Users/aacidov/Library/Android/sdk \
  /Users/aacidov/Library/Android/sdk/cmdline-tools/20.0/bin/avdmanager create avd \
  --name linka_platform_probe_api21_arm64 \
  --package 'system-images;android-21;default;arm64-v8a' --device 'Nexus 5' </dev/null \
  > /private/var/folders/l4/r3plyjwj7pz_nq7zz50x04880000gn/T/opencode/p01-api21-avd-create-colocated.log 2>&1
```

Exit **0**, `Auto-selecting single ABI arm64-v8a`. Emulator list-avds теперь содержит новый `linka_platform_probe_api21_arm64` вместе с прежними тремя. Generated config проверен read-only: `abi.type=arm64-v8a`, `hw.cpu.arch=arm64`, `hw.device.name=Nexus 5`, manufacturer Google, `image.sysdir.1=system-images/android-21/default/arm64-v8a/`, tag default, target android-21. Никакие ручные config/kernel/image/architecture patches не делались. С прежним Homebrew launcher exact create не работал; с co-located той же версии — работает: согласованная SDK-root причина устранена.

Штатный boot (Emulator **37.2.12.0 / build16428233**, host macOS27.0.1 ARM64, как зафиксировано выше):

```sh
/Users/aacidov/Library/Android/sdk/emulator/emulator -avd linka_platform_probe_api21_arm64 \
  > /private/var/folders/l4/r3plyjwj7pz_nq7zz50x04880000gn/T/opencode/p01-api21-emulator-boot.log 2>&1
```

Boot process оставлен работающим, его completion не ожидался и background-job статус/лог не опрашивался. Проверялся **device completion criterion**, не elapsed sleep: MCP list_devices сначала ещё показывал только tablet; затем bounded loop (не более 12 native MCP calls / 120000ms) первым вызовом обнаружил новый **emulator-5554**, device, sdk_phone_arm64/generic_arm64, transport_id13. Перед **любыми state changes** на нём MCP read-only getprop подтвердил имя **linka_platform_probe_api21_arm64**, SDK **21**, abilist **arm64-v8a**, primary abi **arm64-v8a**, **sys.boot_completed=1**. Повторный запрос уточнил name через `ro.kernel.qemu.avd_name`.

Serial5554 **переиспользован новым task-owned AVD**, а не старым `linka_audit_phone`; владелец не угадывался по номеру. OS **5.0.2**, fingerprint **`generic_arm64/sdk_phone_arm64/generic_arm64:5.0.2/LSY66K/8789838:eng/test-keys`**. Fixed sleep не применялся; дополнительных boot loops после observed1 не понадобилось. Host/image совместимость достаточна для этого actual boot; утверждения о других emulator/host/kernel combinations не делаются.

### Probe install и UI: actual actions / ошибки evidence tooling

Все device install/launch/input/capture/dump/pull/stop — **через adb MCP**, serial явно `emulator-5554`; host raw adb не использовался. `list_packages(filter="su.linka.pictures.platformprobe")` → **No packages found**. `install_app(reinstall=false, allowDowngrade=false)` → **Performing Push Install**, `pkg: /data/local/tmp/app-debug.apk`, **Success**. `start_app_activity` с package `.platformprobe` и activity `su.linka.pictures.ProbeActivity` → Starting Intent, Activity реально resumed (probe-only dumpsys activity). Permissions/Apple login prompts не возникали; baseline `.pictures` не устанавливался/открывался/чистился.

Первый native `take_screenshot` **неуспешен**: `adb: error: remote object '/sdcard/mcp_screencap_temp.png' does not exist`. Read-only MCP `ls -ld` подтвердил `/sdcard -> /storage/sdcard`, `/storage/emulated/0: No such file or directory`, штатный shell-owned `/data/local/tmp` доступен. Это missing-path helper failure, **не permission denial**. Storage/permissions не правились; дальнейшие captures выполнялись MCP `execute_shell_command("screencap -p /data/local/tmp/<task-owned-name>.png")` + MCP `pull_file` в разрешённые `android21*.png`. XML: MCP `uiautomator dump /data/local/tmp/p01-api21-ui.xml && cat ...`; без изменения legacy storage или SDK.

API21 uiautomator XML **сам содержит `?` вместо не-ASCII**, включая русские подписи/перенос строки: это подтверждено pull исходного XML и Python parse, а не объявлено повреждением app data. Поэтому Unicode/UI русские строки проверены **визуально в PNG**; ASCII field/readback/callback и focused=true дополнительно assert-проверены по XML. Все четыре PNG прочитаны визуально read-инструментом, не только CRC/XML.

| Действие, координаты 1080×1920 | Expected / actual |
|---|---|
| Capture после launch, до save | [android21-01-launch.png](screenshots/platform-probe/android21-01-launch.png): default `Привет, SQLite!`, initial приглашение сохранить. **Уже `Поле в фокусе`**, IME ещё не видима; это отличается от API35 initial state |
| MCP tap `(450,700)` по Save, затем native UI dump; no sleep | [android21-02-unicode-read.png](screenshots/platform-probe/android21-02-unicode-read.png): **точно** `SQLite прочитал: Привет, SQLite!` / `Android callback: Привет, SQLite!`. SQL/callback PASS; **поле остаётся в фокусе и IME видима**, clearFocus/IME-hide expected не выполнен |
| Focus input, первая попытка заменить | Tap `(400,375)`, MCP shell `input keyevent 123` + пятнадцать keycode67 в том же input-вызове, затем type_text `P01 quote ' SQLite`. Все вызовы успешны, но XML выявил **`P01 quote ' SQLiteLite!`**, т.е. cursor/end/deletion approach не очистил хвост. Этот неверный текст **не сохранялся**; это ошибка тестового редактирования, не SQL mismatch |
| Коррекция по наблюдаемому UI, не blind retry | MCP stationary swipe `(300,375)→(300,375)`, duration1000 для long-press; dump показал стандартный **Select all** bounds `[801,87][1080,231]`. Tap `(940,160)`, keyevent67 удалил выделенное, затем type_text той же synthetic ASCII. Новый dump подтвердил **точно `P01 quote ' SQLite`**, focused=true. Timeout не было, ввод не дублировался после неизвестного исхода |
| ASCII before save | [android21-03-ascii-before.png](screenshots/platform-probe/android21-03-ascii-before.png): новое точное ASCII с U+0027, IME/focus; readback ещё старый Unicode — field не выдаётся за DB result |
| MCP tap `(450,700)`, затем UI dump | [android21-04-ascii-read-focus.png](screenshots/platform-probe/android21-04-ascii-read-focus.png): **точно** `SQLite прочитал: P01 quote ' SQLite` / `Android callback: P01 quote ' SQLite`. XML assertions подтверждают оба ASCII значения и **focused=true**. IME остаётся видима, подпись `Поле в фокусе`; clearFocus/IME-hide **FAIL**, не PASS |

При каждом SQL save следующий dump уже показывал новый callback; дополнительных save-ретраев или fixed sleeps не было. На API21 `focus.clearFocus()` в неизменённом sample присутствует (source trace выше), но наблюдаемое состояние не стало false: причина focus retention/reacquisition **не диагностирована**, однозначная вина CMP/OS/host не приписывается. Exception/error status или install/link/native SQLite error не наблюдались. Frontend acceptance по снятию фокуса не выполнен; **нового app patch/сборки/подмены sample нет**.

MCP probe-only `dumpsys package` подтвердил primaryCpuAbi **arm64-v8a**, secondaryCpuAbi **null**, versionCode **1**, versionName **probe**, targetSdk **35**. Read-only разрешённый `run-as su.linka.pictures.platformprobe ls -l databases/platform-smoke.sqlite` → `-rw------- u0_a53 u0_a53 12288 2026-11-04 22:00 platform-smoke.sqlite`. Содержимое БД и baseline preferences не читались. По неизменённому `ProbeDatabase.saveAndRead` эти успешные статусы включают **реальный close/reopen + equality check**, а не только UI echo; process-death durability не проверялась.

После captures probe **force-stopped через MCP**, оставлен установленным с синтетической БД. **Новый task-owned AVD оставлен работающим**, не reset/kill/reboot; старые AVD не менялись, старый tablet не использовался для state changes.

### PNG / logs / focused checks

Все новые PNG **1080×1920**, signature/chunk lengths/CRC32 каждого chunk/IEND/trailing bytes проверены Python standard library; **CRC OK**:

| PNG под `screenshots/platform-probe/` | Chunks | SHA-256 |
|---|---:|---|
| android21-01-launch.png | 13 | `20683ce8ce9c112a102252c16d25ccd82601235317cc9c2b1a7c1626a1a3a369` |
| android21-02-unicode-read.png | 19 | `7b346bdab438b57d65d5eec632f8afe520195bc52b498026afdb0cc4f8828025` |
| android21-03-ascii-before.png | 20 | `1624d7551b74585f68d7c239adc6288a8d9f60a5ebcb76cc55a83ce9fac8f2da` |
| android21-04-ascii-read-focus.png | 20 | `8f6226f86584777a2c3d2bd907546ac6f4256a9b0888519d1d34694b3f42689d` |

Логи в session temp `/private/var/folders/l4/r3plyjwj7pz_nq7zz50x04880000gn/T/opencode/`:

| Файл | SHA-256 / статус |
|---|---|
| p01-api21-tools20-install.log | `b1009a763fcf9c7b01daab2c2d0010134c67a7cc783852bae85f6e73237c75e4` |
| p01-api21-avd-create-colocated.log | `8dfa6bd392616cd9d443437559e6ce085b011836a3a15ce0dabfbc6c640cbefb` |
| p01-api21-ascii-before.xml | `e788f67f2bbce1ce1ca3645dcf65504a95905bcfdf069749149aac8914ca16d4` |
| p01-api21-ascii-after.xml | `01e929b9cdddcfb3e42e1c9c391635793e3b99546f0dd306cbcad73d45c038f1` |
| p01-api21-emulator-boot.log | live emulator process output; не объявляется финальным immutable log/hash |

Focused final checks: append-only byte-prefix относительно base `fa17623d`, только этот отчёт + четыре разрешённых `android21*.png`, существование всех report PNG links, PNG CRC, `git diff --check` и staged diff-check; actual results/final commit возвращаются в handoff. Новых лицензий/global tools configs/product/build/CI изменений, push, делегации, iOS-дублирования или аппаратных permission обходов нет. **Open:** clearFocus/IME на API21, ARMv7, physical/AAC, TalkBack и полный продуктовый/lifecycle/error-path quality gate.

## Дополнение: API21 ARMv7 provisioning — STOP, unsupported QEMU2 architecture

Отдельное явное разрешение пользователя «Да, проверить ARMv7»; задача здесь **только provisioning**, без установки APK до нового build/SHA от COORD. Base: `c9a32601bf4a6106fb263fa8dc2b748f67a6a845`, branch `implementation/p01-android-runtime`, чистый worktree перед началом. Product/probe source, build/root configs и старые evidence не менялись.

**Итог стадий:** exact image downloaded **YES**, SDK installed **YES**, отдельный AVD created **YES**, emulator launch attempted **YES / exit1**, guest booted **NO**, actual API21/ARMv7/zygote32 proof **НЕТ**, app installed/launched **НЕ ВЫПОЛНЯЛОСЬ**. Это конкретный **host emulator/engine blocker**, не app crash и не доказательство работоспособности ARMv7 по наличию ELF или AVD config. **G1 не PASS**, physical/AAC/P00/совокупный UI acceptance остаются открыты.

### Инструменты / scope / exact install

Первое действие — MCP list_devices: только `emulator-5554` (own API21 ARM64, transport13) и `emulator-5556` (старый tablet, transport11). Ни один из них не получал state-changing команд. Новый `linka_platform_probe_api21_armv7.ini` и `.avd` до создания отсутствовали; старые AVD не переиспользовались/не reset/kill/reboot/clear.

- Host: `uname -m` → **arm64**; macOS **27.0.1 / 26A434**.
- Co-located `SDK/cmdline-tools/20.0/bin/sdkmanager --version` под JDK17 → **20.0**, source.properties SHA256 `215e11e90893196549e86dfd6a024f20848322dc7a3d694bc4847b8e6d849ad1`.
- `SDK/emulator/emulator -version` → **37.2.12.0 / build16428233**; emulator/source.properties SHA256 `ba13085f647fb8389a4d06a1de0e4104ac4e841733e50f67223ac230e1ce685a`.
- Наличие `SDK/emulator/qemu/darwin-aarch64/qemu-system-armel` подтверждено: **Mach-O 64-bit executable arm64**, SHA256 `839db17b71ca2129b46ff290518e4de328865af0bdb01dca8592f8b7e633ea8b`. **Наличие бинарника не означает поддержку выбранного guest штатным launcher**; прямой запуск этого engine не выполнялся.

Read-only официальный `https://dl.google.com/android/repository/sys-img/android/sys-img2-3.xml` подтвердил exact remotePackage **system-images;android-21;default;armeabi-v7a**, API21/default/armeabi-v7a, revision **4**, archive **armeabi-v7a-21_r04.zip**, **187163871 bytes**, manifest SHA1 **`8c606f81306564b65e41303d2603e4c42ded0d10`**. Это опубликованный checksum, не отдельно вычисленный hash скачанного ZIP.

```sh
env JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  /Users/aacidov/Library/Android/sdk/cmdline-tools/20.0/bin/sdkmanager \
  --sdk_root=/Users/aacidov/Library/Android/sdk \
  --install 'system-images;android-21;default;armeabi-v7a' </dev/null \
  > /private/var/folders/l4/r3plyjwj7pz_nq7zz50x04880000gn/T/opencode/p01-armv7-sdk-install.log 2>&1
```

Background install прочитан **после automatic completed notification**, без polling. Лог достиг **100% Unzipping... armeabi-v7a/kernel-q**; проверки error/warning/license/accept не выявили prompt или ошибок. Никаких новых лицензий, yes/sudo/chmod/global config/symlink/tool-version замен не было. Установленная local metadata: **Android SDK Platform 5.0.2**, revision4, API21, ABIarmeabi-v7a, tagdefault; package.xml exact localPackage совпал.

| Файл под `SDK/system-images/android-21/default/armeabi-v7a/` | Bytes | SHA256 |
|---|---:|---|
| source.properties | 179 | `3e0aae4b60633e8cb58d4b726bb499b5d406bbf5d84768a2af46c802ff15e73e` |
| package.xml | 18765 | `def4b04df74629d2844d10302542e3b5ac5899adeef2dfc600d105ddde49e52c` |
| kernel-qemu | 2407928 | `c0fb84b0ed4444a56abd3201bb1b13b3d8e664d50d604d7b82a54482d1be8bd6` |

Image содержит `system.img`, `userdata.img`, `ramdisk.img`, **kernel-qemu**, hardware.ini/build.prop/NOTICE/metadata; **kernel-ranchu отсутствует**. `file kernel-qemu` → **Linux kernel ARM boot executable zImage (little-endian)**. Ни image, ни kernel не подменялись.

### Отдельный AVD и exact fatal

```sh
env JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  ANDROID_HOME=/Users/aacidov/Library/Android/sdk \
  ANDROID_SDK_ROOT=/Users/aacidov/Library/Android/sdk \
  /Users/aacidov/Library/Android/sdk/cmdline-tools/20.0/bin/avdmanager create avd \
  --name linka_platform_probe_api21_armv7 \
  --package 'system-images;android-21;default;armeabi-v7a' --device 'Nexus 5' </dev/null \
  > /private/var/folders/l4/r3plyjwj7pz_nq7zz50x04880000gn/T/opencode/p01-armv7-avd-create.log 2>&1
```

Create exit **0**, **Auto-selecting single ABI armeabi-v7a**, без force. Новый generated config: `abi.type=armeabi-v7a`, `hw.cpu.arch=arm`, `hw.device.name=Nexus 5`, manufacturerGoogle, targetandroid-21, `image.sysdir.1=system-images/android-21/default/armeabi-v7a/`, tagdefault, `hw.gpu.enabled=no`, `hw.gpu.mode=auto`. Config только прочитан, SHA256 **`bf99e1a89d135209893c802f602dba23c2e3077b1e338b96072bf3880578ff10`**; это declared native configuration, **не actual guest proof**.

Штатный headless/no-audio launch без engine/CPU/kernel overrides и без изменения acceleration:

```sh
/Users/aacidov/Library/Android/sdk/emulator/emulator \
  -avd linka_platform_probe_api21_armv7 -no-window -no-audio \
  > /private/var/folders/l4/r3plyjwj7pz_nq7zz50x04880000gn/T/opencode/p01-armv7-emulator-boot.log 2>&1
```

Automatic background completion сообщил **exit1**; только после него прочитан полный log:

```text
INFO         | Android emulator version 37.2.12.0 (build_id 16428233) (CL:N/A)
INFO         | Graphics backend: gfxstream
FATAL        | CPU Architecture 'arm' is not supported by the QEMU2 emulator, (the classic engine is deprecated!)
```

**STOP по unsupported ABI/engine.** Ошибка не сообщает об отсутствии hardware acceleration, поэтому разрешённый `-accel off` не использован как неподтверждённый обход. Classic engine, прямой qemu-system-armel, другой toolversion/OS/ABI, kernel/CPU patch и фальсификация arch не пробовались. Нативных permission denials/approval не было.

Повторный MCP list_devices после fatal показал **только прежние 5554/5556**, с теми же transport IDs; **нового serial нет**, владельца следующего номера не угадывали. `sys.boot_completed=1`, actual AVD name/API/primaryABI/abilist/zygote32 получить не удалось — guest не поднялся. Процесс уже завершился по fatal до необходимости bounded boot wait; fixed sleeps и polling background logs не применялись. Созданный task-owned AVD и exact image оставлены на месте, старые AVD не изменены. APK не устанавливался никуда в этой задаче; нового app smoke/PNG нет.

### Logs / append-only checks

Все логи в `/private/var/folders/l4/r3plyjwj7pz_nq7zz50x04880000gn/T/opencode/`:

| Log | SHA256 |
|---|---|
| p01-armv7-sdk-install.log | `b1591e5affc072f074dc9830cfff2c6be06dee0a6475edc3e742c70c5356a917` |
| p01-armv7-avd-create.log | `82a8cc42a7f025f9eb05470b12b19f24ae9a786e923ed63b6ebed7f49652fdd2` |
| p01-armv7-emulator-boot.log | `6e9ab49e815d5c2d1595a7fd865bbcb1acd30734d4724365e252b85bcb387c08` |

Проверки перед local commit: append-only byte-prefix относительно `c9a32601`, изменение только этого отчёта, сохранность старых screenshots/links, `git diff --check` и staged diff-check; actual result/final commit — в handoff. Это provisioning blocker, **не runtime PASS и не app regression**. Следующий app smoke требует отдельно переданный новый APK/SHA и поддерживаемый настоящий 32-bit runtime; физический ARMv7/AAC остаётся отдельным пробелом.

## Дополнение: final APK886, quick64 regression API21 / API35

Accepted artifact update от COORD; base **`006371fef505982ec964a5bf2cb2fd3f3ae9ec2d`**, та же branch, исходно чистая. До действий — MCP list_devices: только API21 ARM64 `5554` и API35 tablet `5556`. Новый 32-bit device **не появился**; предыдущий actual launch log остаётся blocker: `CPU Architecture 'arm' is not supported by the QEMU2 emulator, (the classic engine is deprecated!)`. Повторный boot/другая SDK/engine установка в этом продолжении **не выполнялись**; никакого нового SDK background job эта сессия не запускала и чужие jobs не опрашивала. Поэтому разрешённый 32-bit smoke **не начат**, а два quick64 выполнены независимо, как разрешено COORD. `androidv7*.png` не создавались, ARM64 не переименовывался в ARMv7 proof.

### Final artifact и owner checks до update

Read-only APK path тот же: `/Users/aacidov/.local/share/opencode/worktree/67846f/pokazhi-p01-platform-smoke/app/build/outputs/apk/debug/app-debug.apk`. **Перед install** `shasum -a 256` → **`886630c576528783bede77bf9385d3c7471b90f87f708524043ac6f415f76c0b`**, exact handoff match. `aapt dump badging` → package **su.linka.pictures.platformprobe**, sdkVersion **21**, target/compileSdk **35**, versionCode **1**, versionName **probe**, activity **su.linka.pictures.ProbeActivity**, native-code **arm64-v8a / armeabi-v7a**. Последнее — artifact metadata, не native32 runtime.

`git rev-parse cd78fba` → **cd78fbacd6a1e46fd6168fbafd410b49555ca60b**, parent **394de1f28b4956fd82bb991ed54cd25642c88f0f**. Read-only `git show cd78fba:app/src/probe/java/su/linka/pictures/ProbeActivity.kt` подтвердил host `ComposeView.isFocusableInTouchMode = true` перед setContent; source не редактировался этой сессией. Из build handoff, **не новый build/test этой роли**: Kotlin2.2.21/AGP8.10.1/Gradle8.11.1/JDK17/CMP1.9.3/SQLite2.5.2, actual R8=8.10.24, JVM test1 passed. Reproducible build/reflection R8 здесь повторно не выполнялись; связь APK/source принята по переданному source и совпавшему SHA.

До state changes MCP getprop с явными serial подтвердил:

| Serial | Actual owner / SDK / ABI / zygote / readiness |
|---|---|
| emulator-5554 | **linka_platform_probe_api21_arm64**, SDK21, abilist **arm64-v8a**, **zygote64**, `sys.boot_completed=1` |
| emulator-5556 | **linka_audit_tablet**, SDK35, abilist **arm64-v8a**, **zygote64**, `sys.boot_completed=1` |

Обновлены **только known-owned .platformprobe** через MCP `install_app(reinstall=true, allowDowngrade=false)`: API21 **Push Install / Success**, API35 **Streamed Install / Success**. Никаких clear-data/uninstall/reboot/permission approval. `su.linka.pictures` и её library/preferences не читались/не менялись.

### Точные quick64 действия и observed result

На обоих устройствах MCP launch `.platformprobe/su.linka.pictures.ProbeActivity` успешен. Default text **Привет, SQLite!**; после launch начальный статус приглашает сохранить, поле без фокуса. Проверка deliberately ограничена **tap field → Save с Unicode**: ASCII/SelectAll на этих двух quick64 в этом продолжении не выполнялись, прежний quoted-ASCII result относится к прежнему APK и не объявляется новой проверкой final886.

| Шаг | API21 ARM64, 1080×1920 | API35 ARM64, 2560×1600 |
|---|---|---|
| Launch before | [android21-final01-launch.png](screenshots/platform-probe/android21-final01-launch.png) | [android35-final01-launch.png](screenshots/platform-probe/android35-final01-launch.png) |
| Focus / IME | MCP tap **(400,375)**; [android21-final02-focus.png](screenshots/platform-probe/android21-final02-focus.png): `Поле в фокусе`, рамка/курсор, IME видима | MCP tap **(300,200)**; [android35-final02-focus.png](screenshots/platform-probe/android35-final02-focus.png): тот же focus/IME state |
| Save | MCP tap **(450,700)** | MCP tap **(300,416)** |
| Readback / callback / clearFocus | [android21-final03-read-clearfocus.png](screenshots/platform-probe/android21-final03-read-clearfocus.png): **SQLite прочитал: Привет, SQLite! / Android callback: Привет, SQLite!**, **Поле без фокуса**, IME скрыта | [android35-final03-read-clearfocus.png](screenshots/platform-probe/android35-final03-read-clearfocus.png): те же **точные** readback/callback, focusfalse, IME скрыта |

После Save следующий native UI dump показал expected status и EditText **focused=false** на обоих. API35 XML сохраняет exact Unicode; API21 по-прежнему заменяет не-ASCII на `?`, поэтому точное Unicode в API21 status проверено **визуально**, flags — в XML. Root view API21 получил focus=true, **EditText false** — это не ошибочно выдано за потерю фокуса всем window. Сразу затем captures; все шесть PNG **каждый визуально read**. Between focus и after-save не было BACK/hide keyboard, retry или fixed sleep. На этой final886 quick-проверке ранее наблюдавшийся API21 failure **не воспроизвёлся**: проверенный field clearFocus/IME-hide теперь **PASS**; старая c9/FA история сохранена как результат прежнего binary.

API21 screenshot/dump использовали уже разрешённый MCP `execute_shell_command("screencap -p /data/local/tmp/p01-final21-<stage>.png")` → MCP pull_file; UI `uiautomator dump /data/local/tmp/p01-final21-ui.xml && cat ...`. API35 — native take_screenshot/dump_ui_hierarchy. Не использовались host raw adb, storage/config/permission workaround. Новых screenshot-helper errors/denials не было.

MCP read-only **только .platformprobe** `dumpsys package` / `run-as ... ls -l databases/platform-smoke.sqlite`: оба primaryCpuAbi **arm64-v8a**, version1/probe, БД **12288 bytes** (API21 u0_a53, API35 u0_a209). Бинарник и SQLite path не подменялись; содержимое DB не читалось. После evidence collection оба owned probe **force-stopped через MCP**, оставлены установленными с сохранёнными данными. AVD не остановлены/не reset.

### Evidence hashes / проверка append history

Python проверил signature, lengths, CRC32 всех PNG chunks, IEND и отсутствие trailing bytes — **все шесть CRC OK**:

| PNG | Dimensions / chunks | SHA256 |
|---|---|---|
| android21-final01-launch.png | 1080×1920 / 14 | `ef4555e9681a397973ee0ab14dc5b480c90884101b63370e27a5367d6e9148c5` |
| android21-final02-focus.png | 1080×1920 / 19 | `ce0d3aed6adb3f2a3ae82b0bac64541324872410a541a6607ee0129272a29331` |
| android21-final03-read-clearfocus.png | 1080×1920 / 14 | `2ef027ef89fd449b0214db55f3600cb8cf458f0d2a5d4d4c96e32fe35dae96a2` |
| android35-final01-launch.png | 2560×1600 / 12 | `8cbb3f93945ffe5f058f54e60ab2dde0eaeeec3348dc066c97ad32f37a37e8c1` |
| android35-final02-focus.png | 2560×1600 / 20 | `b0b6e9832ddec4ab929141f84fbe0b7c46a6c93c4c398ff81da81b62f3c27acf` |
| android35-final03-read-clearfocus.png | 2560×1600 / 13 | `1e2c448d0450c9f26ff7d80f04258d58c6bdc8ec911bf44196d9856f39bff935` |

Final focused checks: base006371f byte-prefix сохранён, включая прежние FA/c9 sections; только report append + эти шесть разрешённых PNG; report links существуют; diff-checks перед commit. Actual results/final commit возвращаются в handoff. **G1 совокупно не PASS:** настоящее ARMv7/zygote32 не получено, P00/codecs/physical/AAC и принятие остальных UI checks принадлежат COORD. Новых provisioning/tooling writes/builds/source fixes/делегации/push эта роль не выполняла.
