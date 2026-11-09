# Релиз 2.0.0: фактическая подготовка 9 ноября 2026

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
