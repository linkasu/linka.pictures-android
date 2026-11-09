# Identity и общая метрика: проверка 2026-11-09 UTC

Функциональные блокеры OTP, миграции, consent delivery и iOS native tests
закрыты. Это не разрешение массового выпуска: почтовая квота остаётся
200 сообщений за rolling 24h и 1/s.

## Приложение

- Реализация: `9d3e401`, `c12636d`; исправление запуска iOS native tests:
  `97aaf75`. Библиотечная схема, карточки, черновики и правила защиты
  помощника этой интеграцией не изменены.
- Shared JVM tests, Android debug/release unit tests, Android/shared lint,
  release safety, 50 RU/EN selectors, debug APK и iOS framework проверены.
  Localization, offline release-workflow checks и доставка Compose resources
  также прошли.
- Полный iOS native gate: **707 tests / 82 suites, 0 failures, 0 errors**.
  Команда: `./gradlew :shared:iosSimulatorArm64Test --device
  DD0F9A3F-DC32-4373-BA08-400FECE3A1CC` с JDK17. Причина прежних ошибок —
  standalone-контекст Kotlin runner без системных сервисов Simulator.
  `standalone=false` исправляет запуск; sandbox/fixtures/production ports
  и проверки не ослаблялись.
- Два device-теста Android identity-хранилища прошли на API35. Ветка
  API21–22 проверена unit-тестами конверта, но не устройством этой версии.
- Реальное OTP-письмо получено через Apple Mail MCP; код принят debug APK.
  Приложение перешло к отдельному выбору аналитики. На тестовом устройстве
  выбрано **выключено**, без подразумеваемого согласия.
- Фактический офлайн-перезапуск проверен: Wi-Fi и mobile data выключены,
  active default network отсутствует, процесс остановлен и запущен снова,
  библиотека открылась без регистрации. Обе исходные настройки сети
  восстановлены; данные приложения не очищались, сетевой ADB не менялся.

## Production Identity

- Consent API: [PR9](https://github.com/linkasu/linka.identity/pull/9).
  Санитизированные delivery diagnostics:
  [PR10](https://github.com/linkasu/linka.identity/pull/10), deployed source
  `43bef5706299fa7d1e73e645cce6965a3162f89d`.
- CI: 890 race tests, integration gate, vet/build и workflow tests.
  [Maintenance42595713234](https://github.com/linkasu/linka.identity/actions/runs/42595713234)
  и [normal42597945174](https://github.com/linkasu/linka.identity/actions/runs/42597945174)
  завершились успешно.
- Active revision `bbam5rlbgaeimir4nklj`, image
  `cr.yandex/crp5051c3iheo6jgo8kt/linka-identity@sha256:9e8e1b626483dbc8c4d5d74bae50882c5b8d38da00993693d8c084b253d9335f`.
- Schema **2→3**: 29→31 tables. Все 1391 строка данных в прежних 28
  data tables сохранились по хэшам; меняется только schema metadata,
  две новые таблицы при promotion были пусты.
- Новый keyless runtime principal `aje85f95ii4g6sv8jr93` получил тот же
  минимальный набор resource-scoped прав. Старый runtime-v2
  `ajeesdb9vm232kicq06k` больше не имеет доступа к YDB; write probe
  подтверждает gRPC7 `PermissionDenied`. Ключи шифрования/JWT/SMTP
  не ротировались. Worker timer восстановлен в ACTIVE.
- Под write fence сделан backup `etnqma7ocrqc763rs4rk`, READY, TTL168h.
  Изолированное native restore всех 29 таблиц / 1392 строк, включая schema
  metadata, совпало с frozen source по хэшам.
- По явному разрешению выполнен дополнительный local rehearsal на
  официальном YDB с защищённой копией native backup: данные не
  расшифровывались, fingerprints совпали, тот же финальный CI-image
  прошёл миграцию. Проверена матрица old2 pass / new2 reject /
  old3 reject / new3 pass. Новые облачные test tenants имели отдельный
  Query Service compilation timeout; это не объявляется исправлением
  облачного сервиса. В production preflight новый SQL-table query прошёл.
- По указанию пользователя throttle limit повышен до **500 RU/s**,
  provisioned capacity=0, storage limit=5GiB сохранён. При 10 RU/s
  runtime probes давали ResourceExhausted; те же probes после повышения
  прошли. Первый consent timeout не оставил частичного commit.
- Временные облачные БД, local YDB container и защищённые data exports
  удалены. Сохранены только metadata/hash receipts, без записей и credentials.

## Sender и Metrics

- Причина SMTP-отказа подтверждена: MAIL550 `identity not found`.
  В нужном каталоге создана Postbox identity `nkolinka.ru`, добавлены
  только два выданных DKIM CNAME. Все прежние DNS-записи, включая
  MX/SPF/DMARC и старые DKIM, сохранены. VerificationStatus=SUCCESS,
  SendingEnabled=true; повтор проверки выполнен документированной
  кнопкой Cloud Console.
- Metrics [PR30](https://github.com/linkasu/linka.metrics/pull/30) ввёл epochs.
  Live-проверка обнаружила missing ledger grants: bind-mounted bootstrap
  мог сохранять старый inode после rsync. Исправление
  [PR31](https://github.com/linkasu/linka.metrics/pull/31), deployed source
  `f0b6415700357cdc507973727bd4e0b3286adafe`, передаёт текущий host bootstrap
  через stdin. Behavioral red/green regression, make check и полный CI прошли.
  Publish Images, Deploy VPS42606395257 и Deploy YC Collector42606535617 успешны.
- Live canary без email и пользовательского содержимого прошёл
  `allow(epoch1) → deny(epoch1) → allow(epoch2) → deny(epoch2)` с completed
  receipts. Batch принят, exact replay идемпотентен; старые и поддельные
  epochs отвергнуты. Тестовый scope оставлен denied.
- Три остановленных тестовых consent-события после review повторно
  поставлены в очередь. ID, payload, original timestamps и attempts=12
  сохранены; счётчики не сбрасывались. Все семь consent events delivered,
  pending/manual-DLQ для этой темы отсутствуют. Финальные health/ready —200.

## Не входит в подтверждённый допуск

Публикация Android/iOS в магазины, App Store/legal review, реальный OTP на
iOS и аппаратная проверка API21–22 не выполнялись. Запрос Postbox quota
`atdjdfucdvqi1b9huu3c` на 10000/day и 5/s всё ещё PENDING; runtime limit200/day
не повышался. Историческая/маркетинговая рассылка не включалась. Нельзя
считать 500 RU/s разрешением на массовую отправку писем или массовый
mandatory-email rollout до отдельного capacity/policy допуска.
