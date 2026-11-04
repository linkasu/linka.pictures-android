# Предварительная проверка G1: блокеры до bootstrap

Дата: 4 ноября 2026 года. Это **не выполненный P01**: его вход P00 ещё не принят, dependency patches/новый bootstrap не выполнялись.

## Проверенное окружение

| Команда / источник | Результат |
|---|---|
| `xcodebuild -version` | Xcode 27.2, build 27B52 |
| `/usr/libexec/java_home -V` | OpenJDK 17.0.19 arm64, единственный зарегистрированный JDK |
| `xcrun simctl list runtimes -j` | Доступны iOS26.5, 27.0, 27.1, 27.2; iOS14 нет |
| SDK `system-images/` | Только `android-35` |
| ADB MCP / проверка AVD | Два выделенных API35 arm64 emulator, см. baseline evidence |
| `/Applications/*Xcode*.app` | Только `Xcode.app`; дополнительная совместимая установка не обнаружена этим ограниченным поиском |

API21/armeabi-v7a, iOS14 и физические AAC-устройства не проверены. Наличие современного SDK, `minSdk 21` либо deployment target14 не подменяет runtime-проверку.

## Кандидат из плана не принят

Официальная [Compose compatibility](https://www.jetbrains.com/help/kotlin-multiplatform-dev/compose-compatibility-and-versioning.html), прочитанная в эту дату, называет Compose Multiplatform1.12.2, Android21, iOS14, общую оговорку «only 64-bit platforms» и соответствие Android Jetpack Compose1.12.1. Оговорка сама по себе не доказывает отсутствие Android armv7: Android использует Google artifacts.

Однако реальный Google Maven artifact `androidx.compose.ui:ui-android:1.12.1` содержит:

```xml
<uses-sdk android:minSdkVersion="23" />
```

URL: `https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-android/1.12.1/ui-android-1.12.1.aar`.

SHA-256 AAR: `3f1ae17e237d9dc0a2a0f42cb04b85b8de6e12f67adc5f5c12173fe31e501cb2`.

Архив скачан только в память с ограничением 40MiB, manifest прочитан через Python `zipfile`. JNI entries у этого отдельного AAR отсутствуют; это не ABI evidence всего графа. Понижать минимум через manifest override нельзя. Этот кандидат не удовлетворяет R15 по фактическому manifest независимо от таблицы документации.

Для ограниченного сравнения прочитаны manifests двух более старых AAR:

| Jetpack `ui-android` | minSdk в manifest | SHA-256 |
|---|---|---|
| `1.8.2` | 21 | `7b2e0aff2b575004536e9c7634b51868545d4873e4627432878835b0a7e56851` |
| `1.9.4` | 21 | `f49671f5b9174cc9f25206ff23d5234ef0b927ef04b2a847bd361e12c79c9cee` |

Их URL имеют тот же шаблон Maven. Официальная таблица сопоставляет CMP1.8.2→Jetpack1.8.2 и CMP1.9.3→Jetpack1.9.4. Это кандидаты дальнейшей проверки, **не принятая lock-таблица**: остальные artifacts, compiler, SQLite/ZIP/grapheme/audio, ABI, iOS link/runtime ещё не проверены.

Официальный [KMP compatibility guide](https://www.jetbrains.com/help/kotlin-multiplatform-dev/multiplatform-compatibility-guide.html) указывает для Kotlin2.4.20–2.4.21 Gradle7.6.3–9.7.0, AGP8.5.2–9.3.1, Xcode26.4. Установленный Xcode27.2 этой строкой не подтверждён. Это отсутствие доказательства совместимости, не воспроизведённая ошибка компилятора.

## Следующее допустимое действие

До dependency patch COORD должен принять воспроизводимую связку, сохраняющую Android21/armv7/arm64 и iOS14. Нужны разрешённые среды минимальных ОС и совместимый Xcode (при необходимости отдельная установка с явным разрешением, выбор через `DEVELOPER_DIR`). Не менять глобальный `xcode-select`, не принимать лицензии и не ставить SDK/Xcode автоматически.

P01/G1 остаются открытыми. По действующему плану P02 и параллельная реализация P03–P13 пока запрещены; перепрыгнуть этот gate можно только после явного изменения порядка приёмки пользователем, не объявляя недоступные проверки успешными и не меняя R15 молча.

## Первый изолированный probe: Kotlin2.4.21 отклонён

В `implementation/p01-platform-probe`, commit `15dad5dac3c66cb77265e5753aa7a5388366016f` (base `3deade7`), проверен кандидат KMP/compiler2.4.21 + CMP1.9.3 + SQLite2.5.2 + AGP8.5.2/Gradle8.7/JDK17/Xcode27.2. В основной implementation-ветке его сборочные изменения **не интегрированы**. Полный отчёт доступен через `git show 15dad5d:docs/development/platform-probe.md`.

- SQLite2.5.2 Android manifests и фактические native entries подтверждают minSdk21, `armeabi-v7a` и `arm64-v8a`. Это artifact evidence, не runtime-test.
- Минимальный `:shared:linkDebugFrameworkIosSimulatorArm64` успешно выполнен с установленным Xcode27.2, без version/minimum overrides.
- Неправленый Kotlin/Native2.4.21 `konan.properties` задаёт `minVersion.ios = 15.0`, полученный framework `Info.plist` содержит `MinimumOSVersion=15.0`. Проверочный assert `minimum <= (14,0)` упал. `IPHONEOS_DEPLOYMENT_TARGET=14.0` не делает этот framework совместимым с14.
- Archive member inspection также обнаружил два object с minimum17.2; происхождение и reachability пока не доказаны, и это не объявлено безвредным.
- UI/SQLite behavior smoke, Android probe APK и runtime не выполнены: агент остановился до их реализации, не подменив G1 успешной линковкой.

COORD принял следующий конкретный кандидат для изолированной проверки: Kotlin/KMP/compiler **2.2.21**, остальные версии без изменения. Нужны собственный Native minimum, attribution object17.2, повторная линковка и actual smoke. Это решение о следующем эксперименте, **не** готовая lock-таблица и не разрешение P02.

## Kotlin2.2.21: compiler blocker снят, проверка итогового приложения продолжается

Экспериментальный commit `8d14b6d` в той же probe-ветке: неизменённая Native distribution имеет minVersion.ios12; simulator framework успешно линкуется с Xcode27.2 и содержит plist minimum14. Два higher-min object атрибутированы по dependency graph и SHA-256 к CMP1.9.3 → Skiko0.9.22.2 → `libicu.a`/`libskunicode_icu.a` → `libicu.icudtl_dat.o`, а не SQLite. Их SHA-256 совпадает: `a05864b10cb096e35bc318de76cecdb748b97e19485f1821abf12302106a2780`.

COORD отдельно проверил actual `otool -l` и `nm -m` extracted object: `__text` size0, `__data` size0, единственная непустая секция `__TEXT,__const` size0x6014e0; все reloc counts0, undefined symbols0, внешний символ только `_icudt_skiko74_dat`. Таким образом, поле minos17.2 здесь относится к data-only object и само по себе не доказывает наличие iOS17 кода/API. Метаданные/библиотеки не переписаны, предупреждения не подавляются.

Диагностический assert «максимум minos всех членов static archive <=14» оставлен в истории как FAILED; он строже нормативного контракта deployment/runtime и не подменяет проверку исполняемого приложения. COORD разрешил продолжить только изолированный probe с проверяемым data-only assert для обоих точных объектов, инспекцией **финального executable** minimum/API и реальным UI/SQLite smoke. Любой другой higher-min executable object — blocker. Runtime14, весь G1 и интеграция по-прежнему не приняты.
