# Identity и общая метрика: проверка 2026-11-08 UTC

Клиент реализован в коммитах `9d3e401` и `c12636d`. Это не допуск
обязательного email-входа к массовому выпуску.

## Пройденные проверки

- JVM shared tests, Android debug/release unit tests, Android/shared lint,
  `verifyReleaseSafety`, 50 RU/EN UI selectors, debug APK и iOS framework.
- Localization и offline release-workflow checks; доставка Compose test resources.
- 19 тестов Identity FSM и 3 native iOS identity checks на Simulator.
- Два Android device-теста защищённого identity-хранилища на API 35.
- Конверт для API 21–22 проверен unit-тестами, но не устройством этой версии.
- Реальный debug APK установлен поверх существующей тестовой установки:
  обязательный gate показан, запрос OTP принят действующим Identity.
  Повторная отправка автоматически не выполнялась.

## Развёрнутый бэкенд

Metrics PR [30](https://github.com/linkasu/linka.metrics/pull/30), commit
`22794e09f51fdd570e9189ad46475dd2872bf27f`: CI, публикация GHCR,
Deploy VPS и Deploy YC Collector успешны. Native ClickHouse backup и
изолированное восстановление проверены workflow. `/v2/consents` без
полномочий возвращает `401 invalid_consent_token`.

Identity PR [9](https://github.com/linkasu/linka.identity/pull/9), commit
`2661ca507c837848357e1039ad8eb39859c37252`: CI и публикация образа успешны.
Production **не мигрирован**: рабочая schema 2, прежний runtime, таймер и права
записи сохранены. Schema 3 требует отдельного успешного cutover; не запускать
normal deploy поверх schema 2 и не подменять migration gate.

## Непройденные gates

1. **YDB rehearsal.** Native backup `etnvmjafachb4lbr7hj4` имеет статус READY
   и TTL 168 часов. В отдельную временную БД восстановлены 29 таблиц / 1240
   строк; Table API читает данные. Query Service стабильно отвечает
   `Query compilation timed out`, в том числе для новой пустой probe-таблицы.
   Проверены provisioned RCU 1 и 10 в пределах лимита временной БД; проблему
   это не устранило. Мигратор из точного CI-образа также не прошёл rehearsal.
   Production migration не начата. Временная БД удалена, её provisioned
   capacity перед удалением возвращена в 0. Backup и metadata-only receipts
   сохранены; контакты и credentials в этот документ не включены.
2. **Реальная доставка OTP.** Одна согласованная проверка прошла очередь и одну
   sender-попытку, затем сервер зафиксировал `delivery_failed`.
   Письмо не найдено через Apple Mail MCP по точному отправителю и получателю;
   это не само по себе доказательство отсутствия доставки, но серверный
   terminal state подтверждён. Причина отказа sender ещё не установлена.
   Повторные письма не запрашивались. End-to-end verify/офлайн-reopen остаются
   непроверенными. Действующий лимит отправки — 200/rolling 24h и 1/s;
   повышение квот этим изменением не выполнялось.
3. **Полный iOS native gate.** Первый прогон: 707 тестов, 25 ошибок.
   После выбора закреплённого Simulator iOS 17: 68 целевых тестов, 23 ошибки.
   Две репрезентативные ошибки image/file ports воспроизведены также на
   неизменённом исходном `6fc5add` в отдельном worktree. Их проверки не
   ослаблялись; весь iOS gate зелёным не считается.

## Следующий допуск

Устранить Query Service blocker и выполнить rehearsal/cutover Identity 2→3
с остановкой writers, свежим backup и проверкой сохранности данных. Затем
проверить отдельное согласие, outbox acknowledgement, `allow→deny→allow`,
неприём старого epoch и отсутствие старой истории в DataLens. Устранить
отказ sender и отдельно согласовать повтор реального письма; пройти OTP,
перезапуск и офлайн-доступ на обеих платформах. Публикация Android/iOS до
прохождения этих gates не выполнялась.
