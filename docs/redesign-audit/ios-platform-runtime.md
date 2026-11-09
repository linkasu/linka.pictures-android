# G1: частичный iOS runtime smoke

**Текущий результат:** официальный iOS17.0 runtime скачан через авторизованный Chrome/MCP, проверен, импортирован и загружен. Native SQLite assert и настоящий public XCTest ввода/фокуса/Save/callback прошли на17.0. Physical/AAC и весь G1 этим не закрыты. Предыдущие блокеры ниже сохранены как история.

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

## Chrome/MCP: доступ подтверждён, runtime17.0 установлен и исполнен

Пользователь явно поручил использовать MCP и **его Chrome**. `opencode mcp list` подтвердил подключённый Playwright; безопасно прочитаны только флаги его конфигурации: `--extension --browser chrome`. Chrome DevTools MCP в сессии отсутствовал; отдельный браузер не запускался, настройки MCP не менялись.

Через расширение в существующем Chrome открыты только собственные вкладки Account и каталога Downloads. Секция Agreements прямо показала: актуальное Apple Developer Program License Agreement **Accepted October29,2026**. Поэтому гипотеза о непринятой лицензии опровергнута; наличие `unauthorized` у запроса без браузерной сессии не доказывало проблему статуса пользователя.

В авторизованном каталоге найден стабильный `iOS17 Simulator Runtime` от September18,2023. `View Details` показал официальный DMG. Первые click attempts не прошли стадию visibility/stability: вкладка была background (`document.visibilityState=hidden`). После штатного `page.bringToFront()` visibility стала visible, один действительный click успешно отправил download. MCP network evidence: HTTP200, `content-type: application/x-apple-diskimage`, `content-length: 7559529352`. Cookies/пароли не извлекались, соглашения/permissions не подтверждались.

Завершение отслеживалось по появлению **готового** файла нужного размера, не fixed sleep. Созданный этой сессией файл перенесён из Downloads на data-диск:

```text
build/platform-runtime-downloads/iOS_17_Simulator_Runtime.dmg
bytes: 7559529352
SHA256: fb6bb000f4ed7b60275c21d3782f0ce079815dadf2093d7c260ae5996d774739
```

Это локально вычисленный digest, не выдуманный published Apple checksum. `hdiutil verify` exit0: image checksum **VALID**. `xcodebuild -importPlatform <DMG>` exit0, registered `69C5C790-0AB1-4873-9A65-2C9B4F06FA68`. `simctl list runtimes -j`: `iOS17.0`, identifier `com.apple.CoreSimulator.SimRuntime.iOS-17-0`, `isAvailable=true`, без availabilityError.

Создан новый собственный `linka_platform_probe_ios17`, iPhoneSE3, UDID `DD0F9A3F-DC32-4373-BA08-400FECE3A1CC`. `simctl bootstatus -b` завершился; runtime/device mapping и Booted подтверждены. Старые simulator не reset/erase/shutdown, Xcode остаётся27.2stable.

Установлен probe app exact17.0 из `d85fcf1`, SHA executable `8e354c0145dd69f4d419436e50109dc0d1042ee6bf01f1f69fcf65486deb6345`; `simctl launch` вернул PID88996. [Настоящий startup на iOS17.0](screenshots/platform-probe/ios17-startup.png) визуально прочитан: поле/Unicode, focus label, кнопка и начальный status отображаются. PNG SHA256 `8dc2e6509fb36910e168eff42bff00cdaab5ce211d01ff4a1331bcdd7f118828`.

На том же **runtime17.0** выполнен `simctl spawn <UDID> .../NativeSQLiteCheck-ios17`, exit0:

```text
PASS: native SQLite write/close/reopen/read, Unicode, quotes and replacement
```

Таким образом, прежняя проблема получения/запуска minimum runtime17 решена, не заменена тестом26.5. UI button/focus/native callback ещё проверяются отдельным public XCTest; это не полный G1 PASS. Собственные диагностические Apple-вкладки закрыты; пользовательские вкладки не закрывались. Шесть автоматически созданных MCP account/catalog snapshots/logs перенесены в approved session temp **вне Git**, не коммитятся; в evidence не сохраняются личные данные аккаунта.

## Public XCTest: iOS17.0 UI interaction PASS

Probe commit `67e6a83` добавляет только test-only `iosApp/ProbeUITests.xcodeproj`, shared scheme и один `ProbeUITests.swift`. Он запускает уже установленный `.platformprobe` на точном собственном UDID; app/framework source и SHA не менялись. Signing/team/profile отсутствуют, `CODE_SIGNING_ALLOWED=NO`, parallel-testing отключён, пользовательские simulator не затронуты.

Actual `xcodebuild test`: **TEST SUCCEEDED, 1 test, 0 failures, 0 skipped**, 10.743s; xcresult summary подтверждает runtime **17.0 /21A328**, iPhoneSE3/arm64 и нужный UDID. Пройдены:

- Save default Unicode → точные SQLite readback и native iOS callback в UI.
- Tap настоящего editable TextView → «Поле в фокусе».
- Public XCTest вводит ` P01'17`; actual текст `Привет, SQLite P01'17!` проверен, а не подставлен кодом приложения.
- Save → точный readback/callback actual текста; «Поле без фокуса», прежняя focus-метка отсутствует.

[До ввода](screenshots/platform-probe/ios17-ui-before.png) и [после Save](screenshots/platform-probe/ios17-ui-after.png) — прямые screenshot attachments из passing xcresult, не макеты. Каждый визуально просмотрен V; COORD дополнительно просмотрел итоговый PNG. Их SHA256: `38ce7f3052ec197207d097dd5e8e719b69c1ff1f10af570000b1fa3f2e72441f` и `facf2f31a2f4214971d854627aeb8c6c419f8afbb90074f5bbcee9aed947c567`.

В первых диагностических запусках неверно ожидалось содержимое в XCUIElement.value: Compose публикует его в TextView.label. Исправлен именно selector/assertion по реальному accessibility tree, все проверки содержимого и изменения текста сохранены; приложение не патчилось ради PASS. Подробные команды/три result bundles/raw evidence — `git show 67e6a83:docs/development/platform-probe.md`, последний раздел.

Это закрывает конкретный iOS17 minimum-simulator vertical smoke. Полные VoiceOver/AAC, codecs, permissions/lifecycle, physical iPhone/iPad и весь продукт остаются отдельными приёмочными проверками.
