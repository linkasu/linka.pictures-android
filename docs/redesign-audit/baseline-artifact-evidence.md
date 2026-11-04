# P00: исходный артефакт и состав baseline

Проверено 4 ноября 2026 года. Это evidence локальной tag-сборки, **не APK Google Play**. G0 целиком не закрыт.

## Сохранение исходной работы

- Начальная ветка: `audit/google-play-v1.4`, HEAD/tag `v1.4`: `2073d912f6c70ab0165823f37e4b5704cf72e953`.
- До изменения ветки `git status --short --branch` показывал только `?? docs/`.
- Все 140 исходных файлов `docs/`, включая пять игнорируемых `.DS_Store`, сохранены в архив; каждый файл проверен по SHA-256 после чтения архива без распаковки.
- Snapshot: `/private/var/folders/l4/r3plyjwj7pz_nq7zz50x04880000gn/T/opencode/linka-pokazhi-p00-snapshot/preimplementation-docs.tar.gz`.
- SHA-256 архива: `8e68fb7ccb033ed1b4e4f67a78dc76753a29069a6ba8380dd524e62f3453a11b`.
- Рядом сохранён `sha256.json` со всеми исходными путями и хешами. Это временная локальная страховка, не долговременное backup-хранилище.
- В implementation-ветке `implementation/linka-pokazhi` коммит `3deade7` сохраняет 135 Markdown/PNG; `.DS_Store` остаются на месте и не добавляются в Git.

## Локальный APK

| Поле | Наблюдаемый результат |
|---|---|
| Файл | `app/build/outputs/apk/debug/app-debug.apk` |
| Размер | 14 842 079 bytes |
| SHA-256 | `8f33a33449645b94733839bac058e48759f6069c7fe7513fcabb423520882f04` |
| Package | `su.linka.pictures` |
| Версия | `1.4` / `5` |
| minimum / target SDK | `21` / `35` |
| Сертификат | `C=US, O=Android, CN=Android Debug` |
| SHA-256 сертификата | `e327834d61c7cc1c145dafebbb279a0c2fed0ffa14d8ea54d1235daeaf392041` |

Существовавший APK скопирован без изменения в `baseline-v1.4-debug.apk` рядом со snapshot. Хеш копии проверен. Затем `./gradlew :app:assembleDebug --no-daemon` прошёл: **BUILD SUCCESSFUL**, 59 tasks, 5 executed / 54 up-to-date. Хеш APK после сборки совпадает с сохранённым. Это не clean-room/CI доказательство воспроизводимости.

Перед добавлением новых characterization tests `git diff v1.4 -- app shared build.gradle settings.gradle gradle gradle.properties .github` был пуст: исходники/сборочные файлы baseline не изменены. Сборка сообщает предупреждения совместимости KMP/AGP, compileSdk35/AGP8.5.2 и deprecated `MediaRecorder()`. Это baseline-предупреждения, не исправления.

`aapt dump badging` подтвердил package/версию/SDK/debuggable. `apksigner verify --print-certs` успешно проверил сертификат; утилита предупреждает о не защищённых JAR-подписью META-INF metadata. Эти предупреждения не скрываются и не исправлялись изменением baseline. `main.jks`, production signing и его секреты не использовались.

## Fixtures из Git-объектов

Источники прочитаны через `git ls-tree -rz` и `git show <baseline>:<path>`, а не через case-insensitive working tree. Все пути ниже имеют префикс `app/src/main/assets/sets/`.

| Путь в Git | Wire version | Cards / ZIP entries | SHA-256 |
|---|---|---|---|
| `Болит.linka` | `1.3` | 12 / 25 | `ae6f1e58f2d7e7a3826e3586aab3d862f38a43d9cecf59168766dc536ca95985` |
| `Еда.linka` | `1.2` | 9 / 19 | `d77a113a5be5bf2773b5ebc70bc1757a4ab48761203c538504a6dabbbb08b14d` |
| `Клавиатура.linka` | `1.0` | 96 / 95 | `c7b1d07cf4c3e926dbf7256d5c3f10acb1de329df70a4753f834128b1320e3b8` |
| `ХОЧУ.linka` | `1.2` | 9 / 19 | `a0589a029f77bfff7307d6ee3c4dbd41e1b2badd52ca90896ca5c835ccfb293b` |
| `Цифры.linka` | `1.0` | 24 / 31 | `4e2a43a6b62ff34a8675fb9e02a3998a0c4db09558044b611bb40c81115c798c` |
| `ШУМЫ ДОМА.linka` | `1.0` | 9 / 19 | `04b37130e6c10e0806951c503a0916042c38bd41a601bfa134c98d8f0646dcf1` |
| `цифры.linka` | `1.0` | 24 / 31 | `4e2a43a6b62ff34a8675fb9e02a3998a0c4db09558044b611bb40c81115c798c` |

Все `config.json` прочитаны как JSON; это плоские legacy-документы без `pages`. Cards — длина wire-массива, не утверждение количества содержательных карточек. Наблюдаемые версии не дают разрешения принимать произвольный `1.x`.

В **локальном** APK шесть `.linka`: перечисленные выше без lowercase `цифры.linka`; каждый payload совпадает по SHA-256 с соответствующим Git-объектом. Имена ZIP entries без UTF-8 flag корректно декодированы из сырых UTF-8 bytes; вывод Python по CP437 сам по себе не доказывает испорченные имена Android assets. Состав Linux/Play APK этим не установлен: в Git семь путей и case collision.

### Наблюдаемые аудиосигнатуры

Отдельный read-only проход по тем же Git blobs прочитал media bytes и RIFF `fmt ` chunks; это не воспроизведение нативным декодером.

| Набор | Extension / bytes evidence |
|---|---|
| `Болит` | 12 `.mp3`, ID3/MPEG-frame signature candidates |
| `Еда` | 9 `.mp3`, ID3/MPEG-frame signature candidates |
| `Клавиатура` | Audio entries не обнаружены |
| `ХОЧУ` | 9 `.ogg`, фактически RIFF/WAVE format1 (PCM), mono, 48kHz |
| `Цифры`, отдельно `цифры` | По 10 `.ogg`, фактически тот же RIFF/WAVE PCM mono48kHz; оба архива идентичны, не два независимых codec samples |
| `ШУМЫ ДОМА` | 9 `.ogg`, фактически RIFF/WAVE PCM mono48kHz |

Эти fixtures **не покрывают настоящий Ogg/3GPP/AMR**. Расширение нельзя использовать как единственное основание codec/MIME policy; для V5 нужны дополнительные разрешённые samples и реальные decode tests. Байты и имена исходных архивов не менялись.

## Установленные тестовые среды

ADB MCP `list_devices` нашёл два устройства. Все последующие read-only команды явно задавали serial; выбор устройства по умолчанию не использовался.

| Serial | Подтверждённый AVD | API / ABI | Установленный package |
|---|---|---|---|
| `emulator-5554` | `linka_audit_phone` | 35 / arm64-v8a | `su.linka.pictures`, 1.4 / 5 |
| `emulator-5556` | `linka_audit_tablet` | 35 / arm64-v8a | `su.linka.pictures`, 1.4 / 5 |

Проверены `ro.boot.qemu.avd_name`, `ro.build.version.sdk`, `ro.product.cpu.abilist` и version fields `dumpsys package`. Затем на каждом явно заданном serial через ADB MCP получен `pm path su.linka.pictures` и вычислен `sha256sum` каждого APK: оба `base.apk` имеют SHA-256 `8f33a33449645b94733839bac058e48759f6069c7fe7513fcabb423520882f04`, совпадающий с сохранённым локальным APK. Это связывает снятый UI с локальным debug baseline по digest, не только versionName. APK не извлекался; private library/preferences не читались. Личные/физические устройства не подключались.

## Не доказано

- Идентичность APK магазинному, Play signing chain, максимальные versionCode всех треков и реальный Play upgrade.
- Upgrade на новый бинарник: целевого бинарника ещё нет, baseline только сохранён.
- Полная семантика миграции и покрытие UI: см. отдельные результаты P00. Чтение fixtures не доказывает их декодирование обеими платформами.
- Сохранение будущих пользовательских данных, G1 и остальные gates. Этот отчёт не разрешает обход зависимостей пакетов.
