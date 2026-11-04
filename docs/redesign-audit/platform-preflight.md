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
