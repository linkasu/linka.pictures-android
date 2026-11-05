# Accepted P08 merge + full Android image/audio/crypto runtime

5 ноября 2026. Самостоятельный image implementation/evidence commit **`93abfedb09139c544212263389bb3b9bf08ab0a8`** завершён до handoff (focused15/15 на обоих AVD; отдельный SHA/evidence в p06-android-image-preparation.md). Затем на clean branch выполнен только порученный `git merge --no-edit a0d2315`, без reset/cherry-pick duplicate history: merge **`34c1f9d4735752d5fbcba3e7252562b38df7ab1f`**, accepted main **`a0d23154ec3cdd687d43f7d5b0b887629d7cfeed`**. Конфликтов и новой security implementation в lane нет.

## Files compatibility

Прочитан новый PlatformFilesPort.chooseDestination/ExportDestination. Image implementation использует только createTemporaryFile/source/sink/discard и не содержит старый save call. Её test adapter делегирует остальные методы обновлённому OwnedAudioTestFiles: chooseDestination явно `error("Not used")`, не unsafe fake Completed. Поэтому дополнительный compatibility shim/новая фабрика/default не потребовались. Побайтно подтверждено, что оба image source/test файла после merge равны image commit93abfed. Файлы root/ports/security изменены только принятым main merge, не собственными runtime edits; после merge изменяется только docs evidence.

## Build/freeze/manifest

`JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ANDROID_HOME=/Users/aacidov/Library/Android/sdk DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer ./gradlew :shared:assembleDebugAndroidTest --console=plain` — **BUILD SUCCESSFUL (28s)**. Source/test discovery даёт ровно **54 tests**: прежние37 + image15 + PasswordCryptoAndroidTest2, что подтверждено runtime numtests и точным совпадением class/test names.

Отдельная exclusive readonly frozen copy:

- `/private/var/folders/l4/r3plyjwj7pz_nq7zz50x04880000gn/T/opencode/p08-image-runtime-46be1b23-d735-46cc-b0a7-5ac4391fef00/frozen-p08-image-test.apk`
- **SHA256 `8e656c99ebed14717dfef498eb0fe50974a6d129a31f82d81b7d1d2d85d42504`**, 16,142,734 bytes; source/copy SHA совпали и frozen не изменился после runs.
- Actual aapt: package=targetPackage=`su.linka.pictures.shared.test`, min21/target34, **нет RECORD_AUDIO/FirebaseInitProvider**, analytics enabled=false/deactivated=true. [Freeze](evidence/p08-image-full-2026-11-05/freeze.json), [binary manifest](evidence/p08-image-full-2026-11-05/manifest.xmltree.txt), [badging](evidence/p08-image-full-2026-11-05/badging.txt). Унаследованные legacy permissions не добавлялись; normal su.linka.pictures APK не устанавливался.

## Owner checks + full actual runtime

До каждого install MCP list_devices/per-serial getprop подтвердили reserved owners/SDK/ABI. Устанавливалась только указанная frozen test copy через MCP install_app(reinstall=true,allowDowngrade=false), **без grants**. На каждом explicit serial выполнен полный `am instrument -w -r su.linka.pictures.shared.test/androidx.test.runner.AndroidJUnitRunner`, без class/package filters, mic, OS prompts или settings/network actions.

| Serial / owner | SDK / ABI | Результат | JUnit time |
|---|---|---|---:|
| emulator-5554 / linka_platform_probe_api21_arm64 | 21 / arm64-v8a | **54/54 PASS**, 0 failed/skipped/assumptions | 36.29s |
| emulator-5556 / linka_audit_tablet | 35 / arm64-v8a | **54/54 PASS**, 0 failed/skipped/assumptions | 25.862s |

API21 MCP execute_shell_command вернул **transport Request timed out**; work factor/код не менялся, instrumentation не перезапускалась. Критерий completion проверен через исходный device output, который продолжил original run и завершился `OK (54 tests)`/INSTRUMENTATION_CODE=-1. Промежуточная read-only проверка установила отсутствие утилиты tail на API21; вместо установки/fallback raw adb использован MCP pull исходного output. Это transport timeout, не скрытый failed/skipped test и не shortened crypto workload.

Полные stdout/stderr [API21](evidence/p08-image-full-2026-11-05/api21-instrumentation.txt)/[API35](evidence/p08-image-full-2026-11-05/api35-instrumentation.txt) pulled MCP, прочитаны все per-test terminal results. Parser проверил 54 starts/54 status0, current1..54, exact source method sets, отсутствие ignored/assumption/error/failure status и финальный OK на обоих. [Results JSON](evidence/p08-image-full-2026-11-05/results.json) содержит per-case records/counts/capture SHA и transport-timeout факт. Новый full run также повторно реально подтвердил image15; это не смешивание старого focused run с новым итогом54.

Actual narrow codec logs current PID4715/API21 и PID13716/API35, старые записи исключены без buffer clear: [API21](evidence/p08-image-full-2026-11-05/api21-codecs.log), [API35](evidence/p08-image-full-2026-11-05/api35-codecs.log). На обоих MP3/Vorbis/WAV/AMR-in-3GP/rawAMR = **200/200/100/220/220ms**, все fixture/source-before-after hashes совпали с ранее pinned значениями. Старые37, новые image15 и crypto2 все присутствуют отдельно в results.

## Что именно доказано для P08

- `actualApiProviderPreservesUnicodeAndEmbeddedNulAtProductionWorkFactor` реально использует Android/JCE SecretKeyFactory **PBKDF2WithHmacSHA1**, **1,300,000 iterations**, 256-bit output. На API21 и API35 совпал frozen golden **`70561a17a1f4a0f70b79678a6e6f1fc3bb1c9205340688574a92ba8b71e0a8d6`**, включая Unicode/NUL/unpaired UTF16 password encoding contract; SecureRandom salt и constant-time comparison branches также actual API. Work factor не уменьшался для device pass.
- `verifierReopensFromOwnedNoBackupRootWithoutAnyOsPromptOrLibraryAccess`: реальный test-UID noBackup FS, fresh must-create UUID directory, verifier write/reopen, Protected state, exact password unlock и отказ отличающегося пробелами пароля, отсутствие plaintext password в stored bytes; test cleanup удаляет только собственный directory. Критические hash/PBKDF/FS операции не fake.
- **DeviceOwnerVerifier намеренно fake UNAVAILABLE**; verify вызывает error при попытке prompt. Этот test не доказывает Android Keyguard/BiometricPrompt/system credential/owner verification, не обходит обязательный future OS-owner handoff и не касается реальной библиотеки/миграции/паролей пользователя.

## Остатки

Оба AVD ARM64; ARMv7 runtime не закрыт. Не доказаны positive local TTS/mic, physical audio/route/Bluetooth/background, production target35 foreground/UI или real OS owner/credential recovery. Test target34 на API35 — не normal-app target35 acceptance. Pickers/destination native binding после chooseDestination, Access shell и migration/user-data safety остаются отдельными COORD scopes. Common/iOS/полный release gate в этом runtime handoff не запускались и PASS не объявляются. Все дополнительные собственные изменения после merge — только эта documentation/evidence; дополнительных native/security fixes не было.
