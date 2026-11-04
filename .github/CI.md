# Android CI и production

`android.yml` запускается для PR с **любой** base-веткой (включая стек
`fix/audit-issue-*`) и push в `main`. Общий `android-verify.yml` проверяет один
неизменяемый SHA на Ubuntu 24.04 / Temurin **21.0.13+9**:

- `:shared:jvmTest`, `:app:lint`, `:app:testDebugUnitTest`,
  `:app:testReleaseUnitTest`, `:app:verifyReleasePrivacy`;
- `:app:assembleDebug`, `:app:assembleDebugAndroidTest`;
- instrumentation на эмуляторах API **24 и 36**, используя именно APK из build.
  Требуется хотя бы один выполненный тест; ошибки runner и тестов блокируют gate.

PR не требуют Firebase, signing или Play secrets. Firebase Test Lab/Robo заменены
эмуляторами; это не заявление об эквивалентном покрытии Robo. Device gate тестирует
**debug**, не release APK/AAB. Release покрывается unit/privacy/lint gates;
миграция UI instrumentation на Compose — зависимость от issue #12.

## Production

`workflow_dispatch` требует `ref` (tag или полный SHA, достижимый из `origin/main`)
и уникальный `version_code` от 1 до 2100000000. Для `release: published` используется
tag релиза и repository variable **`ANDROID_VERSION_CODE`**: отсутствие/ошибочное
значение останавливает workflow. Обновлять code перед каждым релизом обязан оператор;
проверки уникальности на Play до публикации здесь нет.

До передачи signing secrets `resolve` проверяет доверенную историю `main`, разрешает
tag в SHA и проверяет ancestry. Проверка повторяется в build до выполнения Gradle.
Ref передаётся через env; произвольные fetch refspec запрещены. Все последующие
проверки и bundle build используют этот SHA, а не повторно разрешённый tag.

Необходимы secrets `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`,
`ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`, `PLAY_SERVICE_ACCOUNT_JSON`.
Keystore декодируется во временный файл, передаётся как `ANDROID_KEYSTORE_PATH`,
удаляется trap; значения secrets не печатаются. Контракт signing из issue #20:

```sh
./gradlew --no-configuration-cache :app:bundleRelease \
  -PverifyReleaseSigning=true -PreleaseVersionCode="$VERSION_CODE"
```

Все четыре signing env обязательны, fallback недопустим. Этот Gradle gate и
dependency verification metadata должны прибыть из issue #20 до production.
Release host tests явно включены в `app/build.gradle` через публичный
`androidComponents.beforeVariants` / `HostTestBuilder.UNIT_TEST_TYPE`.
Причина: в sources JAR AGP 9.3.1 `BooleanOption.kt:158–163` устанавливает
`android.onlyEnableUnitTestForTheTestedBuildType=true`, а
`VariantManager.kt:1058–1063` выключает UnitTest для остальных build types.
Это default AGP 9, не переименование задачи и не локальный скрытый конфиг.
Исходники: `https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/9.3.1/gradle-9.3.1-sources.jar`.
Доверенный SHA должен уже содержать итоговые gates; старый tag не обновляется
кодом workflow и не получает отсутствующую реализацию signing автоматически.

Build сохраняет подписанный кандидат, SHA, version code и SHA-256 AAB. Только после
успеха **обоих** device jobs `verified-artifact` проверяет manifest и выдаёт финальный
артефакт. Publish зависит от всего reusable workflow, сверяет SHA, artifact ID,
GitHub artifact digest и run ID, затем скачивает этот ID и проверяет SHA-256 AAB.
В publish нет checkout/Gradle/пересборки: в Play отправляется тот же подписанный AAB.
SHA, ID и digest записываются в workflow summary. При ошибке любой gate публикация
не запускается. Required check для branch protection: `verify / verified-artifact`
(финальный job запускается через `always()` и явно падает при failed/skipped/cancelled
build или device, не маскируя их пропуском финального check)
(имя подтвердить первым Actions run); branch protection здесь не меняется.

## Локальные проверки workflow

```sh
python3 -B -m unittest discover -s .github/tests -v
actionlint .github/workflows/*.yml
```

Тесты читают реальные workflow, проверяют цепочку gates/ref/artifact, матрицу,
triggers и pins; shell разрешения ref выполняется на временном локальном git repo,
включая попытку подмены `origin/main` через refspec. Они не запускают Play/Firebase.
Это contract tests, а не замена реального GitHub emulator run.

## Источники pins

SHA получены read-only через `gh api repos/<repo>/commits/<tag> --jq .sha`:

| Action | Tag | SHA |
| --- | --- | --- |
| actions/checkout | v4 | 11d5960a326750d5838078e36cf38b85af677262 |
| actions/setup-java | v4 | cf277c60eb25467037889841efdb72551f06f6c3 |
| gradle/actions | v4 | ed408507eac070d1f99cc633dbcf757c94c7933a |
| actions/upload-artifact | v4 | ea165f8d65b6e75b540449e92b4886f43607fa02 |
| actions/download-artifact | v4 | d3f86a106a0bac45b974a628896c90dbdf5c8093 |
| ReactiveCircus/android-emulator-runner | v2 | a421e43855164a8197daf9d8d40fe71c6996bb0d |
| r0adkll/upload-google-play | v1.1.3 | 935ef9c68bb393a8e6116b1575626a7f5be3a7fb |

Temurin patch подтверждён metadata:
`gh api repos/adoptium/temurin21-binaries/releases/latest --jq .tag_name`
вернул `jdk-21.0.13+9` (2026-11-04). Adoptium assets API вернул HTTP 403;
использована metadata официального репозитория Adoptium, версия не угадана.
Metadata также содержит Linux x64 asset
`OpenJDK21U-jdk_x64_linux_hotspot_21.0.13_9.tar.gz`. Android SDK metadata
`https://dl.google.com/android/repository/sys-img/android/sys-img2-3.xml`
подтверждает наличие `system-images;android-24;default;x86_64` и
`system-images;android-36;default;x86_64`.
