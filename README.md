# linka.pictures-android

## Сборка и тесты

Поддерживаемая платформа версии 2.0 — Android 7.0+ (API 24). Наборы `.linka`,
изображения, аудио и родительский пароль хранятся локально. Автоматический Android
backup и перенос данных должны быть отключены в release manifest; перед удалением
приложения или сменой устройства экспортируйте нужные наборы вручную и сохраните
копии вне приложения. Экспорт набора не является резервной копией всех настроек.

Нужны JDK 17, Android SDK (platform 35 и Build Tools, выбранные AGP), принятые
лицензии SDK и доступ к Maven-репозиториям при первом запуске. Используйте только
wrapper из репозитория; версии инструментов заданы в Gradle-файлах.

```sh
export JAVA_HOME="$(/usr/libexec/java_home -v 17)" # macOS; на Linux задайте путь к JDK 17
export ANDROID_HOME="$HOME/Library/Android/sdk" # замените своим путём SDK
./gradlew :app:verifyReleasePolicy
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:testReleaseUnitTest
./gradlew :app:lintDebug :app:verifyReleasePrivacy
# На подключённом тестовом устройстве/эмуляторе API 24+:
./gradlew :app:connectedDebugAndroidTest
```

Debug, конфигурация (`./gradlew help`), release unit-тесты и privacy-проверка не
требуют production keystore или паролей. `check` включает `verifyReleasePolicy`
и `verifyReleasePrivacy`. `build`/`assemble` без указания варианта включают release
и поэтому требуют release inputs; для локальной разработки выбирайте debug явно.
BuildConfig сохраняет политику согласия: debug запрещает сбор telemetry, release
лишь разрешает его после согласия пользователя; Analytics по умолчанию выключен.

## Подписанный релиз

CI/ответственный за релиз обязан явно выбрать **versionCode больше всех ранее
загруженных в Play**, включая другие треки. Автоматической сверки с Play нет.
Передайте `-PreleaseVersionCode=N` (приоритетнее окружения) либо
`ANDROID_VERSION_CODE=N`: только десятичное целое `1..2100000000`, без знака,
пробелов и ведущих нулей. Файлы версий задача не изменяет. Локальный default —
`versionCode=6`, `versionName=2.0`; default недопустим для release без явного input.

Секреты предоставляет защищённое окружение CI/локальный secret manager, **не**
аргументы `-P`, исходники или `gradle.properties`. Обязательны все четыре переменные:

| Переменная | Значение |
| --- | --- |
| `ANDROID_KEYSTORE_PATH` | Путь к существующему читаемому непустому keystore (относительный — от `app/`) |
| `ANDROID_KEYSTORE_PASSWORD` | Пароль keystore |
| `ANDROID_KEY_ALIAS` | Явный alias ключа, без default |
| `ANDROID_KEY_PASSWORD` | Явный пароль ключа, без fallback |

Пустые/пробельные значения и управляющие символы запрещены. Сам gate проверяет
inputs и свойства файла, не открывает keystore и не выводит значения секретов;
криптографическую корректность keystore, alias и паролей проверяет Android signing
toolchain при сборке. Не включайте shell tracing, Gradle `--debug` или build scans
в job с секретами. Не публикуйте keystore, конфигурационные кеши или логи с секретами.

После безопасного предоставления переменных окружения:

```sh
# 7 — пример, не автоматически выбранный следующий код Play.
./gradlew :app:verifyReleaseSigning -PreleaseVersionCode=7
./gradlew :app:bundleRelease -PverifyReleaseSigning=true -PreleaseVersionCode=7
# Для подписанного APK вместо AAB:
./gradlew :app:assembleRelease -PverifyReleaseSigning=true -PreleaseVersionCode=7
```

`verifyReleaseSigning` — отдельный preflight task; `-PverifyReleaseSigning=true`
включает ту же проверку уже при конфигурации. Значения свойства кроме `true`/`false`
недопустимы. `false` **не отключает** защиту release: разрешённый graph задач
`assembleRelease`/`bundleRelease`, package/sign/validateSigning release и агрегатные
сборки проверяется до выполнения задач, включая сокращённые имена Gradle. Без
полного signing или явного versionCode сборка завершается ошибкой, а не создаёт
новый unsigned release artifact. Старые artifacts при ошибке не удаляются: CI должен
использовать чистый workspace и загружать artifact только после успешной сборки.
Configuration cache для release gate не поддерживается — не включайте его.

AAB: `app/build/outputs/bundle/release/`; APK: `app/build/outputs/apk/release/`.
Эти команды только собирают, ничего не публикуют в Play.

## Повторяемость сборки и контракт CI

Wrapper Gradle 8.7 проверяет SHA-256 дистрибутива по официальному
`https://services.gradle.org/distributions/gradle-8.7-bin.zip.sha256`.
При обновлении wrapper заменяйте **и URL, и checksum** значениями для одной версии.
Уже распакованный локальный Gradle wrapper повторно не проверяет этим checksum.

Release workflow должен требовать обязательный input versionCode и передавать его
как `-PreleaseVersionCode` вместе с `-PverifyReleaseSigning=true`; пустой input
должен приводить к ошибке, а не default. Закрепляйте workflow actions полными commit
SHA (включая checkout/setup-java/upload-artifact), JDK — точной версией поставщика,
а SDK/build-tools и toolchain — согласованными фиксированными версиями. Не меняйте
versionCode в Git автоматически и не публикуйте artifacts провалившегося job.

Checksum wrapper не заменяет dependency verification: после объединения modern
toolchain/Compose нужно разрешить **все** зависимости (включая debug/release,
unit/instrumentation tests и shared), создать Gradle verification metadata через
`--write-verification-metadata sha256`, независимо проверить происхождение
checksum и повторить сборки с `--dependency-verification strict`. До этого metadata
для устаревшего baseline намеренно не генерируется. Байтовая идентичность APK/AAB
не измерялась и не гарантируется: проверять её нужно двумя чистыми сборками одного
commit с одинаковыми inputs/toolchain и сравнением artifacts.

## Лицензия

Собственный программный код LINKa в этом репозитории распространяется на условиях
GNU General Public License версии 3 или, по выбору получателя, любой более поздней
версии (SPDX: `GPL-3.0-or-later`). Полный неизменённый текст GPLv3: [LICENSE](LICENSE).

Разрешены использование, форки, изменения и коммерческое использование. При
распространении покрываемого GPL кода и производных программ необходимо соблюдать
GPL: сохранять уведомления об авторстве, лицензии и отсутствии гарантий, отмечать
изменения и их даты, предоставлять соответствующий исходный код получателям и
лицензировать покрываемую производную программу на условиях GPL. Личные изменения,
которые не распространяются, публиковать не требуется. Гарантии не предоставляются
в пределах разделов 15–16 GPLv3.

Авторы и правообладатели сохраняют права на свои вклады; существующие имена и годы
авторства сохраняются в исходниках, уведомлениях и истории Git. Поддержка или участие
АНО «Линка» сами по себе не означают передачу ей исключительных прав. Copyright FSF
в тексте GPL относится к тексту лицензии, а не к авторству программы. Ранее
предоставленные права на версии под MIT/BSD/ISC не отзываются.

Эта GPL-декларация относится только к собственному программному коду и не
перелицензирует сторонний код, SDK/DLL Tobii, Unity-компоненты, зависимости,
изображения, звуки, шрифты, бренды и учебные материалы. Их исходные лицензии,
атрибуция и ограничения сохраняются; права на товарные знаки не предоставляются.
Публичная доступность файла не заменяет разрешение правообладателя. Совместимость
всей готовой сборки с GPL и права на материалы без подтверждённой лицензии этим
изменением не устанавливаются.
