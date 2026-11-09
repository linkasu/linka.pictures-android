# LINKa Pictures / LINKa Покажи

Приложение для общения карточками на Android и iOS. Общие экраны, редактор,
сообщение, SQLite-хранилище и форматы обмена находятся в `shared/`. Android
`app/` и iOS `iosApp/` содержат платформенный запуск; второго legacy-интерфейса нет.

## Данные и запуск

- Release Android: `su.linka.pictures`; debug: `su.linka.pictures.development`.
  Debug и release используют один `MainActivity` и общий `LinkaApp`.
- Существующая development-библиотека остаётся в `files/pokazhi-development`.
  Её IDs, черновики, сообщение, настройки и credentials не переимпортируются.
- Старые Android-архивы переносятся в отдельное поколение
  `files/pokazhi-v1/libraries/<migrationId>`. Исходные `files/sets` и preferences
  не изменяются. Только подтверждённый `active` допускает новую библиотеку.
- На новых Android/iOS-установках доступны пять общих исходных наборов.
  Существующие библиотеки не дополняются; удалённые примеры не восстанавливаются.
  Android продолжает прерванный перенос прежних семи примеров из прежних ресурсов.
  Повреждённые legacy-исходники сохраняются как нечитаемые архивы.
- Android credentials находятся отдельно, в `noBackupFilesDir`; не входят в backup.
  Отсутствующий credential record у существующей библиотеки не отключает защиту.
- На iOS запись защиты помощника — отдельный `WhenUnlocked`, non-synchronizable
  Keychain item: может переноситься защищённым системным backup вместе с библиотекой.
  Старый device-only item переводится без изменения bytes и только с действующим guard.
  Identity email/tokens остаются `ThisDeviceOnly`. Ручной экспорт/backup по-прежнему
  не содержит credentials; отсутствие/коррупция записи защиты не обходятся.
- Прерванный перенос продолжается явно. Неизвестный исход записи не повторяется
  автоматически и не считается отменой. Downgrade Android 1.4 — не откат новых данных.
- Интерфейс следует системной локали (RU/EN). Язык интерфейса не меняет язык речи
  или пользовательские карточки. Analytics SDK отсутствует.

## Регистрация и добровольная аналитика

Локальное общение доступно без аккаунта и интернета. Необязательное подтверждение
почты через `https://api.identity.linka.su` находится в настройках. Старые наборы,
черновики и защита помощника не изменяются. При сбое Identity-хранилища онлайн-доступ
закрывается с восстановлением, но локальное общение не блокируется. Защита помощника
остаётся отдельной и не обходится. Нет plaintext fallback. Почта, ожидающие доказательства и credentials хранятся только
в отдельном device-only secure blob; они не входят в библиотеку, экспорт или backup.

Аналитика выключена по умолчанию и не является условием регистрации. Отдельное
согласие можно дать после OTP и изменить в настройках. Сбор начинается только
после серверного подтверждения и завершения доставки согласия. Отказ немедленно
останавливает сбор и отправку, очищает очередь; оригинальный отказ сохраняется
для отправки после восстановления сети. Быстрые переключения сохраняют порядок.
При CAS-конфликте сбор остаётся выключенным до нового явного решения.

Разрешённый HTTPS transport отправляет только безопасные типы действий и результаты
в `https://metrics.nkolinka.ru/v2/batches`, с отдельным installation Metrics token,
не account token. Текст, почта, названия/ID карточек, изображения, аудио и пути
не включаются. Отдельная защищённая очередь ограничена 16 неизменяемыми batches,
общий secure blob — 64 KiB; повторы сохраняют исходные body и batch ID, максимум
четыре неуспешные/неопределённые отправки без учёта подтверждённого HTTP 429.
`Retry-After` сохраняется вместе с batch: до срока отправка запрещена, повтор
возобновляется при следующем foreground или явной синхронизации, без фонового
цикла ожидания квоты. При заполнении новые события отбрасываются, не блокируя общение.
Android намеренно использует INTERNET, но не Analytics SDK, Firebase, Crashlytics
или новые фоновые разрешения. Release safety продолжает запрещать legacy/debug
компоненты и посторонние SDK.

Удаление онлайн-аккаунта использует отдельную сохранённую capability проверки
статуса: HTTP 202 — запрос, не завершение удаления. Неизвестный исход проверяется,
а не повторяется автоматически. Только серверный `completed` очищает локальные
Identity credentials; карточки, черновики и защита помощника не удаляются.
Публичный API удаления должен быть развёрнут и проверен отдельно до публикации.
Локальные fake-тесты не доказывают реальную доставку OTP, удаление на сервере,
достаточность почтовой квоты или юридический допуск.

## Проверка

Нужен JDK17. Основные проверки:

```sh
./gradlew :shared:jvmTest :app:testDebugUnitTest :app:testReleaseUnitTest \
  :app:lint :shared:lint :app:verifyReleaseSafety :app:verifyUiSelectors
python3 -B shared/src/commonTest/check_localization.py
python3 -B scripts/check-release-workflow.py
```

`verifyUiSelectors` сверяет 50 RU/EN native-test селекторов с ресурсами на JVM.
`verifyReleaseSafety` проверяет оба runtime classpath и merged manifest:
единый launcher, отсутствие legacy Activity, Analytics и debug provider в release.
Подпись и установка production-приложения не являются частью обычной проверки.

iOS использует заранее собранный framework и штатные CMP resources:

```sh
PLATFORM_NAME=iphonesimulator ARCHS=arm64 \
BUILT_PRODUCTS_DIR="$PWD/shared/build/compose/ios/iosSimulatorArm64" \
UNLOCALIZED_RESOURCES_FOLDER_PATH=. \
./gradlew :shared:linkDebugFrameworkIosSimulatorArm64 :shared:syncComposeResourcesForIos
```

Затем собирается `iosApp/LinkaPokazhi.xcodeproj`. Для устройства выбираются
`iphoneos`, `iosArm64` и `linkDebugFrameworkIosArm64`. Sandbox и entitlements
ради упаковки ресурсов не отключаются.

На macOS `python3 -B scripts/test-ios-compose-test-resources.py` проверяет доставку
ресурсов в обе Native test binaries и отказ на различающихся дубликатах.

Миграционные device-тесты используют отдельный `su.linka.pictures.shared.test`
и уникальные синтетические каталоги, не рабочие библиотеки. Облачные Test Lab
и подпись остаются отдельными явными действиями; Test Lab не является Analytics SDK.

Ограничения и доказательства объединения: [unified-runtime.md](docs/development/unified-runtime.md).
