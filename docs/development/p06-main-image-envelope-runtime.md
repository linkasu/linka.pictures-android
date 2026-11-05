# Affected Android image runtime — actual main artifact / common envelope routing

Verification-only lane на clean own branch `implementation/p06-android-files`, base `6e289f630733628a6b0a8e31c817477cab247ca2`. **Ни own worktree, ни main не собирались/не патчились**: использован только уже собранный parent artifact `/Volumes/data/linka/linka.pictures-android/shared/build/outputs/apk/androidTest/debug/shared-debug-androidTest.apk`. Parent fullgate257 JVM/Native frameworks — переданное parent evidence, не новый run этой lane.

## Source snapshot, включая настоящий WIP

Main HEAD до/после copy и после runtime: **`81ff8f088bcb15b13702c2fcaec8e26772359864`**. Только narrow status трёх относящихся файлов, без unrelated diff: MediaContainer.kt **M/WIP**; остальные два tracked committed. Поэтому HEAD сам по себе не заменяет следующие actual file SHA:

| Source под main | Bytes | SHA256 |
|---|---:|---|
| shared/src/androidMain/kotlin/su/linka/pictures/platform/capabilities/MediaContainer.kt | 9342 | `b5ec111eca393192d1e88d30f72f6948236dee8d9fda405c41e95da98deab65b` |
| shared/src/commonMain/kotlin/su/linka/pictures/media/ImageEnvelope.kt | 3762 | `068710efca4676cc5b6848723a9157777abf767dbea6adc722b4af79b0b790b4` |
| shared/src/commonMain/kotlin/su/linka/pictures/binary/Crc32.kt | 726 | `fcab57f2d2f9bdfe471d1f487f3805ff34ef19cb1aabf10fdcbcc48a374f3e99` |

Router прочитан: MediaContainer.image вызывает common validateImageEnvelope с RandomAccessFile readAt; прежнего дублирующего image walker нет. Common validator/Crc32 прочитаны в относящихся диапазонах. SHA стабилен до/после frozen copy и после обоих runs, main не записывался. В APK DEX присутствуют ImageEnvelopeKt и binary.Crc32 symbols. Это проверка actual готовой Android ветки, не собственной старой branch implementation и не новая source integration.

## Artifact freeze / binary manifest

Exclusive readonly copy: `/private/var/folders/l4/r3plyjwj7pz_nq7zz50x04880000gn/T/opencode/p06-image-envelope-97404afa-1fb5-45bd-abd9-7670bb0df227/frozen-main-image-test.apk`, **SHA256 `683787ceaaba3969b926edcd745d07a8b725e85a7b301cd8ed7e875ffd2785be`**, 16,092,266 bytes. Hash source/copy совпал и после runtime не изменился. Actual aapt из external SDK подтвердил package=targetPackage su.linka.pictures.shared.test, min21/target34, **нет RECORD_AUDIO/FirebaseInitProvider**, analytics enabled=false/deactivated=true. [Freeze/source hashes](evidence/p06-main-image-envelope-2026-11-05/freeze.json), [binary manifest](evidence/p06-main-image-envelope-2026-11-05/manifest.xmltree.txt), [badging](evidence/p06-main-image-envelope-2026-11-05/badging.txt).

## Focused actual execution

MCP list_devices + explicit per-serial getprop подтвердили boot1 и owners: emulator5554=linka_platform_probe_api21_arm64/SDK21/arm64-v8a; emulator5556=linka_audit_tablet/SDK35/arm64-v8a. Только frozen **test** APK установлен через MCP reinstall=true/allowDowngrade=false, без grants/normal app. API35 работает под **primary-approved per-run `-feature -WiFiPacketStream`**; lane ничего не перезапускала/не меняла в emulator/config/network/settings.

На каждом explicit serial только `am instrument -w -r -e class su.linka.pictures.platform.capabilities.AndroidMediaInspectionPortTest,su.linka.pictures.platform.capabilities.AndroidImagePreparationPortTest su.linka.pictures.shared.test/androidx.test.runner.AndroidJUnitRunner`. Фактическая discovery = **9+15=24**. Никаких audio37/full63/full82/Access/files suites.

| AVD | Affected result | JUnit time |
|---|---|---:|
| API21 ARM64 / emulator-5554 | **24/24 PASS**, 0 failures/skips/assumptions | 0.52s |
| API35 ARM64 / emulator-5556 (flagged) | **24/24 PASS**, 0 failures/skips/assumptions | 2.148s |

Полные stdout/stderr [API21](evidence/p06-main-image-envelope-2026-11-05/api21.txt)/[API35](evidence/p06-main-image-envelope-2026-11-05/api35.txt) pulled MCP, все per-case результаты прочитаны попарно. [Results JSON](evidence/p06-main-image-envelope-2026-11-05/results.json) проверяет exact class/method sets, current1..24, 24 starts/24 status0, OK(24)/INSTRUMENTATION_CODE=-1 и source/artifact integrity. Среди реальных passed cases: PNG CRC/truncation/bounds/actual decode, JPEG+EXIF read-only exact-copy, actual Canvas/PNG, UTF16/NUL/wrap/empty policy, handles/errors/cancellation/ownership. Controlled provider/FS faults внутри tests не переименовываются в actual OS fault acceptance.

## Границы

Только новый docs/evidence commit в own root; main artifact/source read-only, source code/ports/root/manifest не менялись, сборок lane не было. Никаких grants/mic/network/config/restarts/normal-app/user-library/private-data reads. API35 flagged run не доказывает default emulator/Bluetooth/WiFi stability или production target35 foreground app. ARMv7/physical/real SAF UI/positive mic/TTS/OS-owner/release gates не закрываются. Это целевой regression proof изменённого Android common-envelope routing, не повтор старых suites и не общий product PASS.
