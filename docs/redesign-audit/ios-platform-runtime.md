# G1: частичный iOS runtime smoke

4 ноября 2026 года, COORD. Это **не G1 PASS** и не приёмка iOS14.

Позднейшая явная поправка пользователя: минимум iOS/iPadOS повышен до17. Этот отчёт остаётся историей запуска на26.5; он не доказывает runtime17. App target и assertions обновляются в отдельном probe commit, без изменения Xcode27.2 на beta.

## Артефакт и среда

- Изолированная ветка `implementation/p01-platform-probe`, исходный app/framework из `595061a`, Android-config follow-up `c7eb3a2`, native test/report commit `dedb578`.
- Воспроизводимые команды, assertions, history и hashes: `git show dedb578:docs/development/platform-probe.md`. Код probe пока не интегрирован в основную implementation-ветку.
- Новый собственный simulator `linka_platform_probe_ios`: iPhone SE3, iOS26.5, UDID `1074CF6C-3AF6-4DF3-A330-5F9013437F3E`. Чужие симуляторы/данные не использованы. Загрузка ожидалась через `simctl bootstatus -b`, не sleep.
- Установлен/запущен только `su.linka.pictures.platformprobe`, executable SHA256 `44970b73826b7c4adbfe0d585ffdc4cf960a12d3f0d01f0c33fdd5b2f342146e`.

## Actual results

![Настоящий startup общего Compose probe на iOS26.5](screenshots/platform-probe/ios26.5-startup.png)

PNG визуально просмотрен: общий экран, Unicode-текст, поле, метка фокуса, SQLite-кнопка и начальное состояние отрисованы. Это startup, **не** доказательство нажатия кнопки/ввода/callback. SHA256 PNG `5ec9546980c1d8edb54ae787e5db326afce15475873d91438c5a578de9ba8bf4`.

Дополнительно собран и выполнен `iosApp/NativeSQLiteCheck.swift` из probe-ветки: тот же Kotlin `ProbeDatabase` и реальный bundled SQLite iOS driver, собственный временный UUID-файл, write→close→reopen→read, кавычки/Unicode/emoji и замена пустой строкой. Оба этапа exit0, actual output:

```text
PASS: native SQLite write/close/reopen/read, Unicode, quotes and replacement
```

Harness binary SHA256 `09ea25e0f648af9d1201c761fc802c9e0b962373b194f53e38ce0c7c120bd3ed`, Mach-O minos14.0, реальное выполнение **26.5**. ICU data-only warning при линковке сохранён, метаданные не переписаны.

## Открыто

Нет iOS14 runtime/physical devices, UI-ввода/фокуса/активации/callback, VoiceOver/AAC и полного аудио/файлового стека. Сохраняется явно названный availability-case frame-rate API в CMP1.9.3; weak import и modern startup не доказывают все guarded paths на14. `simctl io` не предоставляет tap, idb/Maestro не установлены; native host permissions не запрашивались и не обходились.

Собственные simulator и probe app оставлены для продолжения. Reset/erase, shutdown чужих сред, profiles/signing, установка SDK/Xcode и изменение production не выполнялись.

## Поправка target17: сборка и повтор на текущей ОС

По явному решению пользователя probe commit `d85fcf1` устанавливает **exact app minimum17.0** в Info.plist и Swift target. App/plist assertions и отрицательные проверки прошли; отдельно слинкован device `iosArm64` framework без signing/установки. Более низкие minimum у используемых библиотек не повышают минимум приложения и не являются обещанием поддержки приложением старых ОС.

- Новый app executable SHA256 `8e354c0145dd69f4d419436e50109dc0d1042ee6bf01f1f69fcf65486deb6345`.
- `NativeSQLiteCheck-ios17` SHA256 `71e62aa5e5dc394b3cfd5ce11f0ee0a2505ad044c37c91ae6ca2d7b2b3bb5838`.
- COORD установил новую сборку только в свой прежний simulator26.5; `simctl launch` вернул PID84450. [Новый startup PNG](screenshots/platform-probe/ios26.5-target17-startup.png) визуально просмотрен: общий экран отрисован.
- `simctl spawn <тот же UDID> .../NativeSQLiteCheck-ios17` exit0, actual native SQLite assertion output снова PASS. Это выполнение **target17 binary на26.5**, не runtime17.

Старая availability-проблема API15 на iOS14 больше не блокирует выбранную цель17. Проверка runtime17/физических устройств и новых API выше17 всё ещё необходима.

## Разрешённая загрузка runtime17: остановка на доступе Apple

Пользователь отдельно разрешил скачать Android21/iOS17 и создать отдельные среды, но запретил автоматически подтверждать новые лицензии, вход Apple и native permissions. `xcodebuild -license check` прошёл без принятия/изменения лицензии.

Штатный `xcodebuild -downloadPlatform iOS -buildVersion 17.0 -architectureVariant arm64 -exportPath <project>/build/platform-runtime-downloads` вернул exit70: `iOS 17.0 (arm64Only) is not available for download`. Раздельные проверки universal17.0 и universal17.5 вернули тот же exit70 для своих версий. Готовый образ через CLI не получен; import/install не выполнялись.

Read-only официальный `https://devimages-cdn.apple.com/downloads/xcode/simulators/index2.dvtdownloadableindex` содержит архивы17.0 (7559529352 bytes, build21A328) и17.5 (7336630623 bytes). Архив17.0 с `download.developer.apple.com/Developer_Tools/iOS_17_Simulator_Runtime/iOS_17_Simulator_Runtime.dmg` при HEAD перенаправляет на `developer.apple.com/unauthorized/`, Content-Type text/html, а не DMG. Это отсутствие разрешённого доступа, не скачанный simulator и не доказательство, что ручной download заработает после login.

По согласованному ограничению остановка: личный браузер/cookies/credentials не использовались, вход не выполнялся, отказ не обходился. Для продолжения Apple-части нужен пользовательский доступ к официальному архиву либо разрешённая тестовая среда. Большой DMG не скачан, системные runtime не изменены.
