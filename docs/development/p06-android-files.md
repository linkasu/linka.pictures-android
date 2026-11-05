# Android native files handoff — transfer prerequisite / blocker

Новая ветка `implementation/p06-android-files` создана от clean **`219d46b6dcf8683fb15cdcccd0b1ad936c4a4b9c`** в собственном worktree. Старые 54/54 и 63/63 suites не повторялись; image/dref commits не amend/reset. AndroidPlatformFilesPort **пока не реализован**: ниже только transfer verification и конкретный native environment blocker перед новым runtime scope.

## Full boot после переноса

Read-only проверены ровно два owned ini:

- `/Volumes/data/developer-state/android-avd/linka_platform_probe_api21_arm64.ini` → `/Volumes/data/developer-state/android-avd/linka_platform_probe_api21_arm64.avd`.
- `/Volumes/data/developer-state/android-avd/linka_audit_tablet.ini` → `/Volumes/data/developer-state/android-avd/linka_audit_tablet.avd`.

Начальный MCP adb.list_devices был пустой; localhost5554/5555/5556/5557 свободны. Запущен существующий `/Volumes/data/developer-state/android-sdk/emulator/emulator` (37.2.12) только с `-avd <owned> -port {5554,5556} -no-snapshot-load -no-snapshot-save`, environment ANDROID_HOME/ANDROID_SDK_ROOT=`/Volumes/data/developer-state/android-sdk`, ANDROID_AVD_HOME=`/Volumes/data/developer-state/android-avd`. Никакого wipe/reset/config edit/snapshot deletion/новых grants. bproxy/другие AVD/ARMv7 не запускались.

Boot completion проверялся MCP по sys.boot_completed, без фиксированного sleep: API21 готов при первой проверке, API35 сначала пустой property, затем **1** при следующем; emulator log подтвердил API35 full boot за45.583s. Явные serial/owner/getprop результаты:

| Serial | AVD | sys.boot_completed | SDK / ABI |
|---|---|---|---|
| emulator-5554 | linka_platform_probe_api21_arm64 | 1 | 21 / arm64-v8a |
| emulator-5556 | linka_audit_tablet | 1 | 35 / arm64-v8a |

После boot выполнены только read-only owner/SDK/ABI и `pm path su.linka.pictures.shared.test`, затем MCP pull уже установленного test APK (не private app data). Обе копии **16,144,120 bytes**, SHA256 **`1c9d835cdcd98ffedcd853ded375445c659f8206c35d68599dec30c7d4f0faae`**, совпадают с прежним frozen dref test APK. Копии сохранены в approved temp `/private/var/folders/l4/r3plyjwj7pz_nq7zz50x04880000gn/T/opencode/p06-transfer-dd02be92-dbcc-4d07-93ab-08975f6964f0/api{21,35}-installed-test.apk`. Это transfer/data-preservation proof, **не новая полная native acceptance**. Normal app/credentials/library/private userdata не читались, APK не переустанавливались, instrumentation не запускалась.

## Конкретный blocker

Уже после sys.boot_completed=1 и успешного чтения установленного APK API35 host emulator завершился **SIGABRT**. Native stdout содержит `ERROR Failed to find ColorBuffer: 27`, `Failed to bind to texture: invalid color buffer`, после completed boot — `Netsim ... Stream removed` и `Killed by SIGABRT`. Причину abort не объявляем доказанной только по этим соседним строкам; наблюдаемый факт — native process crashed. Полный managed host log: `/Users/aacidov/.local/share/opencode/shell/67846f12c6312a5d2a234ce99442f75d17977218/sh_1ab95ada60016QfS17quv6ooIT.out` (не копируется целиком: там нерелевантные SDK boot properties/public-key diagnostics).

Повторный MCP list_devices видит только **emulator-5554**. API35 не перезапускался автоматически; renderer/GPU/AVD settings/locks/permissions не менялись, native prompts не подтверждались. Для обязательного focused files runtime на обоих AVD нужно COORD решение по восстановлению API35/допустимому обычному restart, либо отдельный handoff host-compile-only до стабильного device. Не маскируем это новым набором emulator flags или synthetic SAF PASS.

Файлы production/ports/root/manifest/access/migration не менялись. Новый files adapter и provider tests не пишутся до возвращения этого platform blocker primary; фактов SAF/device acceptance нет. Старые native suites остаются валидной сохранённой историей.
