# linka.pictures-android

## Сборка и тесты

Контракт наборов и совместимость: [docs/linka-format.md](docs/linka-format.md).

Поддерживаемая платформа версии 2.0 — Android 7.0+ (API 24). Наборы `.linka`,
изображения, аудио и родительский пароль хранятся локально. Автоматический Android
backup и перенос данных должны быть отключены в release manifest; перед удалением
приложения или сменой устройства экспортируйте нужные наборы вручную и сохраните
копии вне приложения. Экспорт набора не является резервной копией всех настроек.

Нужны JDK 21 для Gradle/lint, установленный JDK 17 для `shared.jvmToolchain(17)`,
Android SDK platform 36 и Build Tools 36.0.0, принятые лицензии SDK и доступ к
Maven-репозиториям при первом запуске. Toolchain: AGP 9.3.1, Gradle 9.7.0,
Kotlin 2.4.21, Compose Multiplatform 1.11.1; JVM bytecode приложения — 17.
Используйте только wrapper из репозитория.

```sh
export JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home" # macOS/Homebrew; иначе задайте путь к JDK 21
export ANDROID_HOME="$HOME/Library/Android/sdk" # замените своим путём SDK
./gradlew :app:verifyReleasePolicy
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:testReleaseUnitTest
./gradlew :shared:jvmTest :app:lint :app:verifyReleasePrivacy
# На подключённом тестовом устройстве/эмуляторе API 24+:
./gradlew :app:connectedDebugAndroidTest
```

CI проверяет instrumentation на API 24 и 36; подробности gates и публикации —
[`.github/CI.md`](.github/CI.md). API 21–23 в версии 2.0 не поддерживаются;
это явное изменение минимальной платформы, а не результат manifest merge.

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
./gradlew --no-configuration-cache :app:verifyReleaseBundle -PverifyReleaseSigning=true -PreleaseVersionCode=7
# Для подписанного APK вместо AAB:
./gradlew :app:assembleRelease -PverifyReleaseSigning=true -PreleaseVersionCode=7
```

`verifyReleaseBundle` собирает AAB и читает **protobuf manifest внутри этого AAB**
через bundletool из AGP: package должен быть `su.linka.pictures`, versionCode —
равен явному input, minSdk/targetSdk — 24/36. Несовпадение останавливает выпуск
до загрузки кандидата. `version-code.txt` и checksum служат дополнительной
идентификацией, а не заменой проверки manifest.

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

Wrapper Gradle 9.7.0 проверяет SHA-256 дистрибутива по официальному
`https://services.gradle.org/distributions/gradle-9.7.0-bin.zip.sha256`.
При обновлении wrapper заменяйте **и URL, и checksum** значениями для одной версии.
Уже распакованный локальный Gradle wrapper повторно не проверяет этим checksum.

Release workflow требует обязательный input versionCode (для release event —
repository variable `ANDROID_VERSION_CODE`) и передаёт его
как `-PreleaseVersionCode` вместе с `-PverifyReleaseSigning=true`; пустой input
должен приводить к ошибке, а не default. Закрепляйте workflow actions полными commit
SHA (включая checkout/setup-java/upload-artifact), JDK — точной версией поставщика,
а SDK/build-tools и toolchain — согласованными фиксированными версиями. Не меняйте
versionCode в Git автоматически и не публикуйте artifacts провалившегося job.

Checksum wrapper не заменяет dependency verification. Проверенный файл
`gradle/verification-metadata.xml` закрепляет SHA-256 зависимостей и их metadata;
CI использует strict verification без исключений для доверенных групп.
При обновлении зависимостей разрешите **все** конфигурации (включая debug/release,
unit/instrumentation tests и shared) и обновите metadata:

```sh
./gradlew --write-verification-metadata sha256 :shared:jvmTest :app:lint \
  :app:testDebugUnitTest :app:testReleaseUnitTest :app:verifyReleasePolicy \
  :app:verifyReleasePrivacy :app:assembleDebug :app:assembleDebugAndroidTest
# В защищённом окружении с явным versionCode и тестовыми signing inputs:
./gradlew --no-configuration-cache --write-verification-metadata sha256 \
  :app:verifyReleaseBundle -PverifyReleaseSigning=true -PreleaseVersionCode=7
```

Генерация — только инвентаризация, **не подтверждение доверия**. Независимо сверить
checksums/подписи с официальными источниками артефактов, проверить весь
`gradle/verification-metadata.xml`, затем повторить те же gates с
`--dependency-verification strict`. CI уже требует strict и tracked metadata с
SHA-256 для каждого artifact, проверкой metadata и без `trusted-artifacts`;
изменение версии без проверенного hash блокирует сборку. Не коммитить
непроверенные автоматически сгенерированные hashes. Первичная проверка сверяет
hashes с опубликованными checksums, Gradle module metadata или байтами файлов
официальных Maven Central, Google Maven и Gradle Plugin Portal, а не только с
локальным Gradle cache. Для Linux CI отдельно закреплён Linux-вариант AAPT2.
Байтовая идентичность APK/AAB
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
