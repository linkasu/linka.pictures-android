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
- На новой Android-установке доступны семь исходных наборов. Удалённые примеры
  не восстанавливаются. Повреждённые исходники сохраняются как нечитаемые архивы.
- Credentials находятся отдельно, в `noBackupFilesDir`; не входят в backup.
  Отсутствующий credential record у существующей библиотеки не отключает защиту.
- Прерванный перенос продолжается явно. Неизвестный исход записи не повторяется
  автоматически и не считается отменой. Downgrade Android 1.4 — не откат новых данных.
- Интерфейс следует системной локали (RU/EN). Язык интерфейса не меняет язык речи
  или пользовательские карточки. Analytics SDK отсутствует.

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
