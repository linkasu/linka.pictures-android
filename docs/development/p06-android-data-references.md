# Bounded 3GPP data-reference validation — Android

Base: empty-policy correction `5f75a8d` (separate commit; focused1/1 API21+35). Security implementation ограничена own capabilities: прежний flat `MediaContainer.threeGp` делегирует новому internal `ThreeGpAtoms`; новые tests — `ThreeGpDataReferencesTest`. Root/ports/Access/migration/manifest/files/audio backend не меняются. iOS F/C-bridge implementation делает другой владелец, её результаты этим Android evidence не подменяются.

## Причина и accepted policy

COORD actual iOS negative показал: external flags0 dref/url path может игнорироваться FFmpeg, в то время как embedded audio успешно декодируется. Поэтому успешного decoder PCM недостаточно для безопасной переносимости immutable source между players. Принята общая политика: **non-self-contained data references отвергать до platform decoder/player preflight**, не переписывая оригинал и не разрешая external fetch.

- Walker остаётся container/reference validator, не новым полным MOV parser. Обходит structural moov/trak/mdia/minf/dinf, обрабатывает dref; unknown leaves и все mdat payloads seek-skip-ятся. Никакого поиска строк dref/url внутри media payload. Direct moov/rmra — UNSUPPORTED_MEDIA.
- Atom extents проверяются через remaining parent bytes до сложения: unsigned32/extended64, size0 только как оставшийся parent; отрицательные unsigned64/слишком малые headers/truncation/выход за parent/source — INVALID_DATA. Source<=64MiB, каждый moov<=8MiB, visited atoms включая dref entries<=4096, depth<=8; resource refusal — LIMIT_EXCEEDED. Никаких whole-movie arrays или unbounded recursion.
- dref FullBox version0/reserved flags0, count>0 и exact child count. Каждый entry **только url , version0, flags1 self-contained и ноль location payload**. flags0 (даже пустой URL), URN/alias/unknown type/другие flags/любые location bytes — UNSUPPORTED_MEDIA. Malformed fullbox/version/count/extent — INVALID_DATA; oversized declared count ограничен resource budget до allocation.
- stsd/audio sample entries/data_reference_index не разбираются: accepted policy не требует расширять sample parser; все встреченные standard dref entries разрешены только как local. Это не утверждение о полной MOV grammar/произвольных vendor extension semantics.
- Существующий actual inspector вызывает structural validation до создания MediaExtractor/MediaCodec. Playback проходит existing common AudioCoordinator preflight/inspectClip; новый второй decode/player/очередь не добавлены. Genuine containers остаются неизменными, текущие decoder/PCM/finite-float/duration/cancellation guards не менялись. Каждый visited atom проверяет cancellation/deadline callback.

## Tests / correctness review

9 новых instrumented tests, existing54 (включая исправленный empty case) не ослаблены:

1. Genuine pinned AMR-in-3GP source SHA и actual220ms PCM остаются положительными.
2. External URL/empty flags0/URN/alias/unknown ref/local-flag-with-location variants построены заменой dref в собственном genuine fixture. Embedded mdat/sample offsets остаются прежними; проверяются parser refusal и actual inspector UNSUPPORTED, source SHA до/после совпадает.
3. Direct moov/rmra отвергается даже с embedded audio.
4. В mdat добавлены bytes, похожие на external dref/url; opaque payload не сканируется, actual decoder по-прежнему даёт220ms.
5. Extended64 и size0 boxes ограничены parent, включая self-contained URL entry с size0.
6. Truncated headers, unsigned overflow/negative64/extended-zero, child crossing parent, malformed versions/counts и extra children — INVALID_DATA.
7. Inclusive4096 atoms/depth8 и отказы сверх budgets, ограничение declared entry count.
8. Source64MiB/moov8MiB limits проверены собственными sparse cache files без больших ByteArrays; actual decoder не нужен для отказа.
9. Cancellation на nested atoms распространяется, borrowed source остаётся открытым до outer use и байты не изменяются.

Focused compile предшествовал coherent-diff review; review проверил subtraction-based extents, EOF/parent alignment, entry accounting и отсутствие mdat reads. Parser — 87 lines; conditional stsd parsing и новый full MOV layer не добавлены. Последний `compileDebugKotlinAndroid assembleDebugAndroidTest` — **PASS** (11s), ранее указанное JDK17/Android SDK/Xcode окружение. APK содержит **63 tests**: existing54 + security9.

## Frozen APK / actual MCP runtime

Frozen readonly copy: `/private/var/folders/l4/r3plyjwj7pz_nq7zz50x04880000gn/T/opencode/p06-dref-ab0055fe-ba75-46ee-9ae1-be3a85ad4481/frozen-dref-test.apk`, **SHA256 `1c9d835cdcd98ffedcd853ded375445c659f8206c35d68599dec30c7d4f0faae`**. Actual aapt: package=targetPackage su.linka.pictures.shared.test, min21/target34, no RECORD_AUDIO/FirebaseInitProvider, analytics disabled. [Freeze](evidence/p06-dref-2026-11-05/freeze.json), [manifest](evidence/p06-dref-2026-11-05/manifest.xmltree.txt), [badging](evidence/p06-dref-2026-11-05/badging.txt).

MCP list_devices/per-serial getprop до install подтвердили emulator5554=linka_platform_probe_api21_arm64/SDK21/arm64-v8a, emulator5556=linka_audit_tablet/SDK35/arm64-v8a. Установлен только frozen test APK, reinstall без grants/downgrade. Сначала `am instrument -w -r -e class ...ThreeGpDataReferencesTest`, затем полный `am instrument -w -r su.linka.pictures.shared.test/androidx.test.runner.AndroidJUnitRunner` без filters, на том же SHA.

| AVD | Focused9 | Full63 |
|---|---|---|
| API21 ARM64 / emulator-5554 | **9/9 PASS**, 0.113s | **63/63 PASS**, 32.831s |
| API35 ARM64 / emulator-5556 | **9/9 PASS**, 0.252s | **63/63 PASS**, 23.258s |

На всех четырёх runs **0 failures/skips/assumptions**. API21 full MCP call превысил transport request timeout; исходный run не перезапускался, work factor не уменьшался. Original stdout/stderr завершился OK(63)/INSTRUMENTATION_CODE=-1 и был pulled до server restart. Полные [focused21](evidence/p06-dref-2026-11-05/focused-api21.txt)/[focused35](evidence/p06-dref-2026-11-05/focused-api35.txt)/[full21](evidence/p06-dref-2026-11-05/full-api21.txt)/[full35](evidence/p06-dref-2026-11-05/full-api35.txt) сохранены; [results.json](evidence/p06-dref-2026-11-05/results.json) проверяет exact source test sets, indices, start/status0 counts и hashes. Все terminal results обоих full runs прочитаны попарно.

Genuine 3GP/source SHA `f81c04f29cffcba93be208c36cfc0e2df9196c3fb2e097d5e7234a17ed72426c`, actual duration220ms, и opaque-mdat false-positive variant подтверждены новым test на обоих. Все legacy fixture/source SHA и positive bounded PCM/type assertions также PASS в full63; encoded fixtures не менялись. Новые exact Vorbis/MP3/raw-AMR log durations не выдумываются: после server restart narrow MCP get_logcat timeout на обоих, затем MCP list_devices пустой. Устройства не перезапускались, raw adb fallback/повторных tests не было. Предыдущие exact200/200/100/220/220ms остаются историческим full54 evidence, не маркируются как повторно captured log lines этого run.

## Границы

Нет grants/mic/settings/network/normal-app install/SDK/dependencies/CI/push. Source originals immutable; нет external URL opening, remux, silence/fallback или переноса URI в persistent state. ARMv7/positive TTS/mic/physical/real OS-owner/production target35 foreground gates не закрыты. PBKDF2 production-work-factor и own noBackup FS tests снова PASS в full63, но verifier там по-прежнему UNAVAILABLE fake, не OS owner proof. Cross-platform F implementation/полный producer corpus и stsd grammar вне этой lane; новых platform contradictions на проверенном genuine fixture не обнаружено.
