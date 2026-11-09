# Релиз 2.0.0: фактическая подготовка 9 ноября 2026

## Актуализация после первого CI и исследования аудио

Этот раздел заменяет устаревшие статусы ниже, которые оставлены как журнал этапов.

### Финальные локальные результаты follow-up

- PR39 объединён после CI `42646609869`; main `2b31446…` прошёл CI `42648108810`.
  Первый manual run `42649543992` **отменён до Test Lab**: firebase-test имеет
  `steps=[]`, ни одного gcloud test вызова не было. Обнаружен риск unscripted Robo:
  он может отправить signup/OTP на production Identity без разрешённого алиаса.
  Cloud matrix ниже superseded: оставлены только deterministic startup API36/16KB10m
  и isolated migration physical API30 15m, без retries/shards; максимум compute
  ~$1.42 до free quota. Robo не выполнялся и не считается пройденным.

- Полный gate: **593 JVM, 26+26 Android unit, 722 iOS Native**, без failures/errors/skips;
  Android/shared lint, safety, selectors, APK/AAB, device Release framework и Simulator
  Debug framework PASS. Producer integrity **16/16** PASS. Android test APK пересобран
  после добавления runtime page-size assertion; Compose resources синхронизированы.
- Новый archive `build/prerelease/LinkaPokazhi-final-20261109.xcarchive` собран.
  `check-prerelease.py --app …` и `codesign --verify --deep --strict …` PASS:
  runtime действительно dynamic/embedded, FFmpeg definitions в executable отсутствуют,
  DiskSpace imports в app/runtime отсутствуют, privacy manifests/resources присутствуют.
- App-host iOS17: **4/5**, файл/dynamic version/Keychain PASS, TTS FAIL с отсутствующим
  executable Apple TextToSpeechMauiSupport. Это не скрытый skip и не PASS для TTS17.
  На уже установленном iOS26.5 полный тот же suite **5/5 PASS**.
  Evidence: `KeychainAudioDynamicRuntime{,-ios26}.xcresult` под build/prerelease.
- Полный подписанный app-host с заменённым recipient runtime: **5/5 PASS** на26.5.
  Test plugin побайтово тот же, host entitlements неизменны, relink не выполнялся;
  проверена именно изменённая версия через environment expectation теста.
  Evidence: `build/prerelease/RuntimeReplacementHost/{preparation.json,result.xcresult}`.
- **Distribution export новой сборки BLOCKED**: Xcode account credentials отсутствуют,
  локально доступна только Development identity; прежний Distribution был managed.
  Попытка штатного CSR-процесса остановлена на отказе браузерного tool в file access:
  ограничения не обходились. Ни нового сертификата, ни отзыва/изменения старого нет.
  Неиспользованный временный private key и CSR удалены только из своего temp directory.
  Нужен пользовательский вход в Xcode/штатная настройка Distribution. Старый IPA
  остаётся superseded; новый archive пока имеет Development, не App Store подпись.

- Identity PR11 объединён; main `348d94bee8bf1a2520d1f123524bf83e763b1111`
  прошёл main CI/publish и normal deploy
  `https://github.com/linkasu/linka.identity/actions/runs/42621149593`.
  Live negative canary: health/ready200, deletion без JWT401, неизвестная status
  capability404. Реальное удаление тестового аккаунта ещё НЕ выполнено.
- App PR38 объединён после зелёного PR CI, main `35275a5…`.
  Следующий main CI `42620570319` выявил реальную гонку Background/SwitchCard:
  поздний UNAUTHORIZED менял уже durable source из SAVED в FAILED. Управляемый
  regression воспроизвёл RED и прошёл GREEN для reply до/после Resume.
  Исправление пока в незакоммиченной follow-up ветке
  `fix/prerelease-privacy-and-resume-20261109`; main не считается исправленным.
- iOS production и native storage tests переведены на NativeSQLiteDriver из
  `sqlite-framework:2.5.2`; bundled остаётся Android/JVM. Schema/SQL/bytes не менялись.
  Полный локальный gate после этого прошёл, включая 719 iOS Native tests.
  Отсутствие DiskSpace imports нужно подтвердить на новой Release-сборке.
- Старый signed IPA SHA256 `471979b04221e938c5fbb9829c0cac3ba2e43b48d2fad71597acea78e0375b42`
  экспортирован Apple Distribution, но **SUPERSEDED, НЕ ЗАГРУЖАТЬ**: старый SQLite,
  старая audio packaging и гонка. Это не финальный кандидат.
- Пользователь разрешил перенос только assistant protection item через защищённый
  системный Keychain backup. `WhenUnlocked`, sync=false, bytes неизменны, последний
  guard/readback; неизвестная accessibility fail-closed. Identity остаётся
  ThisDeviceOnly. Hosted XCTest реально исполнились **2/2 PASS**, result bundle
  `build/prerelease/KeychainBackupPolicy.xcresult`; физический restore не проверен.
- Повторный read-only Android launcher smoke на emulator-5554 (API35/4KB) прошёл
  1/1 за7.178s после update-install собственного development APK. Данные не очищались;
  это не fresh-install, не API36/16KB и не upgrade опубликованного production APK.
- Последний завершённый полный gate до audio/backup follow-up: 593 JVM,
  26+26 Android unit и719 iOS Native, без failures/errors/skips. Новые audio
  проверки ещё требуют повторного native gate; старые результаты не приписываются
  изменённому бинарнику.
- Оригинальные FFmpeg license assets доступны офлайн в настройках; SHA проверены.
  Пользователь отверг object/relink-пакет приложения; публикации такого пакета нет.
  Прототипы в ignored build сохранены только как исторический эксперимент.
- Выбранный минимальный аудиопуть после primary-source research: AVAudioPlayer для
  WAV PCM16/MP3 без временной перекодировки; существующий bounded FFmpeg8.1.2
  только legacy fallback, в отдельном dynamic LinkaFFmpeg.framework. Новые codecs
  и собственный 3GP parser перед выпуском не добавляются. На iOS17 нативные OGG/AMR/3GP
  уже дали отрицательные runtime-результаты. Переупаковка и native route ещё проверяются.
  Обоснование: `audio-native-research-2026-11-09.md` и
  `audio-decoders-research-2026-11-09.md`.
- Dynamic bridge proof реально прошёл на iOS17: все5 форматов, 25 отрицательных
  сценариев, same-TU native/policy/truncation/cancel/bounds/fault-injection и отдельная
  Kotlin/CInterop compilation. Evidence run `f54d408a-7c18-4db0-99b2-834da74a1fc0`
  в `build/ios-ffmpeg-preflight`, status DEVELOPMENT_PROOF_PASS, все5 исходных SHA
  неизменны. Точные синтетические fixtures теперь tracked, не зависят от старого `/tmp`.
  CoreAudio source MP3 frames включают padding; explicit FFmpeg conversion его
  обрезает. Эти разные контракты проверяются отдельно, а не выравниваются под тест.
- Recipient rebuild из реально изменённого `libavutil/version.c` прошёл:
  один и тот же harness после замены framework (без перелинковки) выдал версию
  с marker `LINKA_RECIPIENT_REBUILD_PROOF_20261109`. 1/10111 source files изменён,
  prefix unsigned, только test copies получили штатную Simulator-подпись.
  Evidence: `build/prerelease/recipient-rebuild-proof-gln88jt3/proof.json`.
  Замена внутри целого app-host ещё проверяется; CLI proof её не подменяет.
- Firebase ещё не запускался, расходов0. Пользователь разрешил один прогон до$5.
  Каталог `gcloud firebase test android models describe MediumPhone_ps16k.arm`
  подтверждает VIRTUAL/arm64/API36, название16K и tag preview=36; это не physical
  stable-device proof. Разрешённый workflow теперь ограничен тремя executions:
  startup API36/16KB10m (с runtime assert16384), physical redfin/API30 migration15m,
  Robo API36/16KB5m, без retries/shards. Максимум по опубликованным $1/h virtual
  и$5/h physical — $1.50 до free quota; новый paid run ещё не запущен.
  Ни store upload, ни review, ни public release не выполнялись и не разрешены
  автоматически. Политика на сайте и финальные store forms пока не актуализированы.

## Исторический срез до этих исправлений

Статус: **NO-GO для публичного выпуска**. Локальные проверки ниже не заменяют
подпись, deployment Identity, store validation, physical acceptance или legal approval.
Исходный commit приложения: `a561401`; релизная ветка —
`release/2.0.0-prerelease-20261109`.

## Подтверждённый объём и идентичности

- Пользователь разрешил локальное общение без регистрации. Аккаунт и аналитика
  необязательны; защита помощника остаётся отдельной и не обходится.
- Android: `su.linka.pictures`, `2.0.0`, code `6`. В Google Play проверены все
  видимые App Bundles: максимум `5`, последняя опубликованная версия `1.4`.
- iOS: пользователь подтвердил `su.linka.pictures`, `2.0.0`, build `1`.
  Apple App ID зарегистрирован в существующей команде без дополнительных capabilities.
- App Store Connect: создан разрешённый черновик **6832546390**,
  `https://appstoreconnect.apple.com/apps/6832546390/distribution/ios/version/inflight`.
  Сохранены версия 2.0.0, русское описание, support URL и notes; sign-in required
  выключен, публикация только ручная. Нет upload/submission/release.
- RuStore: создан бесплатный неопубликованный черновик **2063785208**,
  `https://console.rustore.ru/apps/2063785208`. APK не загружался.
- Google Play существующее приложение: **4973526126828045666**. Формы и публикация
  не менялись. Действующий URL политики: `https://linka.su/privacy-policy/`.
  Политика ещё требует конкретизации Identity/Metrics и самостоятельного удаления.

## Реализация

- Android target/compile API36, min21; реальные Release metadata и signing checks.
  Shared instrumentation host намеренно остаётся target34; это не target приложения.
- iOS Release/archive scheme, release framework/resources, AppIcon и required-reason
  manifest; Debug сохраняет собственный development bundle.
- Пять готовых стартовых наборов подключены к новым библиотекам. Старые Android
  active-библиотеки не дополняются; interrupted journal7 использует прежние семь
  ресурсов. iOS сохраняет direct `library-v1`; fresh admission — отдельный `starter-v1`
  с поколениями и явным продолжением после interruption. Unknown commit не повторяется.
- Ссылки на политику и поддержку доступны в регистрации и настройках. До отправки
  email показаны получатели и категории данных. Новых SDK аналитики/рекламы нет.
- Удаление аккаунта имеет сохранённые request UUID и отдельный секрет проверки статуса.
  Неизвестный результат не повторяется автоматически; HTTP202 не означает завершение.
  Только серверный `completed` очищает локальные Identity credentials. Библиотека,
  черновики, сообщение и защита помощника не удаляются. Reauthentication для повтора
  привязана к исходному аккаунту. Пока удаление ожидается, аналитика не собирается.
- В `/Volumes/data/linka/linka.identity-pokazhi-consent` подготовлены публичные
  POST `/v1/public/account-deletions` и `/v1/public/account-deletions/status`.
  Signed account subject проверяется, авторизация повторяется в той же serializable
  транзакции, что privacy fanout. Status capability read-only и не зависит от уже
  удалённой сессии; в БД сохраняется только domain-separated SHA256. Новых таблиц нет.
  Этот backend-код **не развёрнут** в production.

## Полученные доказательства

| Проверка | Фактический результат |
|---|---|
| Shared JVM | 592 tests / 65 suites, 0 failures/errors/skips |
| Android unit Debug / Release | 26 + 26, 0 failures/errors/skips |
| Android/shared lint, safety, 50 selectors | PASS |
| Android debug APK, unsigned release APK/AAB | BUILD SUCCESSFUL |
| iOS Native на `linka_platform_probe_ios17` | 717 tests / 83 suites, 0 failures/errors/skips |
| Android admission instrumentation | 7 tests, 0 failed/ignored, emulator-5554, API35/4KB |
| Release workflow policy | 384 комбинации PASS; реальный Actions run не выполнен |
| Release wiring/icon/privacy/metadata/signature-result selfcheck | PASS |
| RU/EN localization | PASS |
| Android native ABI/ELF | ARMv7/ARM64 и x86/x86_64; оба 64-bit ELF имеют PT_LOAD align 16384 |
| Identity Go | targeted tests, `go test -race ./...`, `go vet ./...` PASS |
| Identity YDB integration | 14 top-level tests, 59 PASS с subtests, 0 skipped |

ADB MCP ожидание instrumentation получило transport timeout. Тест не перезапускался
вслепую: logcat TestRunner подтвердил завершение 7/0/0 и штатное окончание instrumentation.

YDB-тесты выполнялись только на собственных ephemeral контейнерах из уже имеющегося
официального `ydbplatform/local-ydb:latest` 26.2.1.14, `/local`, in-memory synthetic data,
без mounts и production credentials. Контейнеры удалены; чужой PostgreSQL не менялся.
Первый общий privacy flow превысил test timeout45s; budget увеличен до180s, повтор
на чистом YDB прошёл. Полный integration-набор и integration `-race` не выполнялись;
использованный образ отличается от pinned compose image.

Отдельный resource-packaging selfcheck получил host timeout20s и не считается PASS;
нужен последовательный повтор после окончания archive. Native717 выше действительно
исполнены, а не только скомпилированы.

Первый device Release link воспроизвёл `Java heap space` в Kotlin/Native
DevirtualizationAnalysis при heap2GiB. На этом Mac24GiB лимит повышен до4GiB;
оптимизации не отключены, повторный archive пока не завершён. iPad отдельно объявляет
четыре штатные ориентации для multitasking; iPhone сохраняет landscape-контракт.

## Артефакты Android

| Файл | SHA256 |
|---|---|
| `app/build/outputs/apk/debug/app-debug.apk` | `9600e6e5d05b31d58591c2baa16d64b6c20b55247910681bfce780e48020d32f` |
| `app/build/outputs/apk/release/app-release-unsigned.apk` | `472fe59058079a78af46810d0560f2f2f78ef46fcef0a4b016fb73aba3d18fb9` |
| `app/build/outputs/bundle/release/app-release.aab` | `ee9d8f7c33e6eba9c8204c87f6969729f4b8de7e2f7abe70f2f3bda15a6b3bf3` |

Release-файлы неподписанные и не выдаются за готовые к загрузке.

## Открытые условия выпуска

1. Проверить CI и только затем отдельно разрешить deployment. Сверка через явно
   разрешённый пользователем `gh` CLI: Identity main `43bef57` имеет тот же tree, что
   исходный `3e8c0e9`; новая ветка от main — `feat/public-account-deletion-20261109`.
   Commit `ebddbb35b10b9dcc5f764bb3eea4e7bbcab2e64a` опубликован в ветке, создан
   draft PR `https://github.com/linkasu/linka.identity/pull/11`; CI ожидается.
   Android remote main `25a6fc0` — предок локального HEAD, локальная KMP-основа
   впереди на272 commits. Force push и перезапись истории не выполняются.
   По отдельному разрешению глобально добавлен официальный GitHub MCP; он требует
   OAuth и не считается connected. Пользователь явно выбрал `gh` CLI вместо OAuth.
2. Реальная проверка удаления после deployment: requested → processing → completed,
   включая downstream Metrics receipt. Fake/local YDB не заменяют live acceptance.
3. Пользователь разрешил существующие release-ключи/профили и Test Lab до$5.
   Облако не запускалось, расходы0; проект/стоимость пока не подтверждены.
4. На Mac найдена только Apple Development identity. Apple Distribution identity
   и существующие provisioning profiles не найдены. Пользователь отдельно разрешил
   штатное создание Distribution certificate/profile без отзыва прежних сертификатов;
   новые credentials пока не создавались. Нативные подтверждения выполняет пользователь.
5. Подпись Android, сверка public certificate с магазином и настоящий published-APK
   upgrade не выполнены. Synthetic admission7 не заменяет этот сценарий.
6. API36 runtime, 16KB runtime, physical iPhone/iPad/Android, аппаратное аудио,
   VoiceOver/TalkBack и полная жестовая приёмка ещё не подтверждены.
7. Shipping/legal статического FFmpeg остаётся OPEN: exact corresponding source,
   notices/relink/EULA/patent/App Store compatibility не объявляются одобренными агентом.
8. Публичная политика, store privacy/data-safety/age формы и финальные скриншоты
   должны соответствовать последней сборке до submission. Нет разрешения на публикацию.

Откат старым бинарником не гарантируется: прежний iOS не видит `starter-v1`, прежний
Android не продолжает новый journal5, старый Identity parser не понимает pending deletion.
Исходные legacy-данные не удаляются; downgrade не является восстановлением новых данных.
