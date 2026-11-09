# Проверки текущего development slice, 6 ноября

Это фактические результаты, **не общий release/G4/U1.4 PASS**.

## Актуальная граница

Последние результаты и ограничения: [журнал исполнения](implementation-status.md).
Полный509 JVM/633 Native gate, Android debug70/release70, native UI и сохраняемые
RED описаны там; ниже оставлены исторические срезы без переименования их totals.
iOS26.5 hosted clip/TTS2/2 не закрывает minimum iOS17 TTS RED.

## История: audio/lease, после caption fix

| Проверка | Текущий результат |
|---|---|
| Полная shared JVM suite | **479/479 PASS**,0 failures/errors/skips |
| Полная standalone Native iOS17 | **603/603 PASS**,67 suites,62.103s |
| Android APK/test APK, оба Native frameworks, app-host framework | Собраны офлайн; decoder producer `VERIFIED_FASTPATH` |
| API21 existing phrase playback/Stop/replay/Back/reopen | **1/1 PASS**,5.046s |
| API35 existing phrase playback/Stop/replay/Back/reopen | **1/1 PASS**,28.809s |
| iOS17 existing phrase playback/Stop/replay/library/reopen | **1/1 PASS**,45.232s; проверены настоящее COMPLETED/PLAYING/IDLE, сохранённая фраза |
| Hosted real clip native callback | **1/1 PASS**,0.672s; до исправления CInterop linkage RED0.242s |
| Непустой TTS | **OPEN/RED**,предыдущий runtime INTERRUPTED и OS voice/audio-unit errors; не skip |

Product root cause и security invariants: [media-lease-read-proofs.md](media-lease-read-proofs.md).
Отдельная причина первого hosted clip failure: [ios-real-audio-host.md](ios-real-audio-host.md).
Машиночитаемый manifest: `evidence/audio-lease-2026-11-06/results.json`.
Native UI проверяет настоящее состояние приложения; слышимость, качество,
физическая задержка Stop/тишина и offline TTS этим не измерены. Доски/фраза не
очищались, production не устанавливался; использована ранее разрешённая Simulator
подпись, её конфигурация/profiles/volume/network не менялись.

## Предыдущий срез (исторические результаты)

| Проверка | Результат и границы |
|---|---|
| Полная shared JVM suite | **471/471 PASS**,0 failures/errors/skips |
| Полная standalone Native suite, owned iOS17.0 arm64 | **593/593 PASS**,64 suites,60.852s; не app-host Keychain/UI/physical proof |
| Native frameworks | Simulator arm64 и device arm64 собраны, min17, без unlinked-symbol warnings |
| Настоящий app-hosted XCTest Keychain | **2/2 PASS**,0 skips/expected failures; собственные UUID services/group |
| Настоящий XCUI startup/relaunch | **1/1 PASS**,13.512s; два запуска real development app и AX title/menu/create |
| Android calibrated UI, APK bdbb277…1183df | **1/1 native Back/reopen PASS**,39.949s;9 карточек/ХОЧУ КУШАТЬ сохранены |
| Android microphone guards, API35 | **3/3 PASS**,0.568s; denied/cancelled, ложный callback success, modeled revoke before native allocation. OS permission не выдано, запись не запускалась |
| Android legacy content-filter resolution | **1/1 PASS**,0.761s; только PackageManager query, legacy writer disabled и не запущен |
| Последний APK после permission/manifest поправок,9e3eb901…c0c0a79 | **1/1 native Back/reopen PASS**,30.448s; installed SHA совпал, фраза/9 AX карточек сохранены |
| Android lint | shared **0 errors/7 warnings**,app **0 errors/46 warnings**; ошибки исправлены, suppression/baseline не добавлялись |

## Найденные причины и минимальные исправления

- Kotlin framework раньше присваивал custom compilation после фиксации main link
  inputs. Теперь создаётся с правильной compilation сразу, exports проверяются
  `verifyAppHostTestFramework`. Реальные2 native Keychain tests, не fake facade.
- Для Simulator Xcode использует собственную подпись и Mach-O entitlements.
  Forced Development-подпись дала сохранённый AMFI−413 RED. Штатный путь отдельно
  разрешён пользователем, profiles/trust/ACL/AMFI/SIP не менялись.
- iOS development app получила **только Simulator** own-group entitlements.
  Реальный startup теперь открывает библиотеку вместо Keychain−34018; отдельно
  проверен повторный запуск. Physical/product acceptance не следует из этого.
- Shared-модуль аудиозаписи не объявлял RECORD_AUDIO; основной app объявлял его.
  Декларация теперь у владельца backend. Actual permission проверяется до файла
  и повторно перед AudioRecord после перехода на Main, SecurityException по-прежнему
  даёт PERMISSION_DENIED. Состояние/guards/cleanup/лимиты записи не ослаблены.
  Новая regression сначала RED: expected2 checks/actual0 на прежнем PackageManager
  пути, затем GREEN. Моделируемый отзыв не выдаётся за OS-grant/native mic proof.
- У трёх content MIME intent-data старого импортёра pathPattern без authority был
  некорректен и игнорировался Android. Удалены только эти неработавшие атрибуты,
  wildcard host не добавлялся. Native query подтвердил прежний MIME contract для
  opaque IDs; file filter и поведение импортёра не менялись. Это не исправление
  всех legacy writers/privacy/migration gates и не разрешение production install.

## Воспроизводимость и ещё открытое

Результаты/хеши текущих APK и контрольные суммы отчётов в
`evidence/verification-2026-11-06/`; full logs/xcresult сохраняются локально по
указанным путям. [iOS кадры и XCUI attachments](../progress-screenshots/2026-11-06-ios-development/),
[Android calibrated кадры](../progress-screenshots/2026-11-06-ui14-calibrated/device-resume/).
Все новые PNG — actual capture, без автоматического visual verdict/user approval.

53 lint warnings остаются явно: legacy Views/accessibility/style/resources и shared
Exif/API/KTX. Они не подавлены и не объявлены исправленными. Новые изменения после
полных shared suites ограничены Android-specific guards/manifest и iOS host config;
неизменённые JVM/Native suites повторно не запускались без причины.

OPEN: matched filled iOS board, phone/API21 unlock, portrait/large-font/keyboard/
physical accessibility, fresh external verification, production migration/root
binding, legacy writers/privacy, positive TTS/mic/provider/routes, ARMv7/AAC,
FFmpeg shipping/legal, full release/store gates. Production APK не ставился на
реальные данные, push/store upload не выполнялись.
