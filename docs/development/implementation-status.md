# LINKa «Покажи»: журнал исполнения

Дата начала: 4 ноября 2026 года. Основание: `/Users/aacidov/.opencode/plan/linka-pokazhi-implementation.md` и три соседних приложения contracts/work-packages/verification. Этот журнал отражает выполненное, не заменяет нормативный план.

## Разрешения и ограничения

- **Новая явная поправка R15:** после объяснения SDK/runtime пользователь выбрал «Поднять минимум до iOS17». Текущий минимум iOS/iPadOS теперь17+, не14. Android21/armv7/arm64 неизменны. Xcode остаётся stable27.2, не beta. Основной план и три приложения в `/Users/aacidov/.opencode/plan/` обновлены точечно; история прежних сборок14 сохранена. Это не согласие пропустить gates или установить дополнительные SDK/runtime.
- **Отдельное разрешение на среды:** следующим ответом «Да, Android21 и iOS17» пользователь разрешил скачать/установить соответствующие образы и создать отдельные тестовые среды. Существующие AVD/simulators не сбрасывать; новые лицензии, вход Apple и native permissions не подтверждать автоматически. Android21 ARM64 установку ведёт V, iOS runtime — COORD. `xcodebuild -license check` успешен без изменения лицензии; загрузка iOS17.0 ARM64 запрошена штатным `xcodebuild -downloadPlatform` с export на data-диск.

- Пользователь поручил выполнить план с параллельными агентами, worktrees и локальными ветками/коммитами. Основная ветка: `implementation/linka-pokazhi` от `v1.4`.
- На вопрос о недоступных minimum-runtime средах пользователь ответил «реши проблему». Это не разрешение поднять minimum OS, убрать ABI или объявить G1 пройденным без доказательств. Проверяется альтернативный совместимый кандидат; порядок stop-gates пока сохраняется.
- Явно разрешена штатная отмена только двух несохранённых тестовых состояний: `Audit14д` на `linka_audit_phone` и новый набор на `linka_audit_tablet`. Сохранённые наборы не удалять. Для продолжения аудитора передано это ограничение.
- Push/PR, загрузка в магазины, публикация, production signing/data, личные устройства, установка Xcode/SDK и принятие лицензий не выполнялись. Android state-changing ввод ограничен указанными тестовыми AVD и выданным разрешением.

## Пакеты и gates

| Пакет | Фактический статус |
|---|---|
| P00 / G0 | Частично: source/APK/fixtures сохранены, карты legacy writers и Electron подготовлены. UI-follow-up дал 30 новых PNG и tablet save; phone отключился, оставшиеся сценарии UNKNOWN/BLOCKED. Play provenance и физические устройства не доказаны |
| P01 / G1 | Не принят. Android APK min21/ARMv7+ARM64 собран; после поправки R15 iOS app exact17.0 тоже собран, device framework слинкован. Android35 и iOS26.5 smoke прошли, включая новый target17 binary. Загрузка runtime17 требует доступа Apple; Android21 provisioning продолжается |
| P02 / G2 | Не начат: входные G0/G1 не закрыты, code-contracts/SQLite schema/access policy не заморожены |
| P03–P14 / G3–G5 | Не начаты: независимые implementation-lanes до G2 не запускаются |
| P15 / G6 | Не начат: физические minimum/current устройства и AAC-участники недоступны в текущей проверке |
| P16 / G7 | Не начат; внешние review/signing/store permissions отдельно, публикация не разрешена |

## Выполненные независимые lanes

| Lane | Worktree suffix / ветка результата | Результат |
|---|---|---|
| Android P00 | `pokazhi-p00-android` / `implementation/p00-android-inventory` | Коммит `ce26641`, интегрирован как `890abe6`: [карта writers/readers/settings](../redesign-audit/legacy-storage-inventory.md) |
| Electron P00 | `pokazhi-p00-peer` / `implementation/p00-electron-contract` | Коммит `49f0a1d`, интегрирован как `3dc46d6`: [standard/audio/ZIP/fixtures evidence](../redesign-audit/electron-standard-contract-evidence.md) |
| UI P00 | `pokazhi-p00-ui-audit` / `implementation/p00-ui-followup` | `2604991`→`d7c1532`, `71b17cd`→`b2a15a4`, `47c6c79`→`74246db`: [evidence](../redesign-audit/p00-ui-audit.md), 30 просмотренных новых PNG; tablet types/TTS-submit/Tab+Enter/save проверены, phone disconnected |
| Platform preflight | `pokazhi-p01-platform-smoke` / `implementation/p01-platform-probe` | `15dad5d`→`8d14b6d`→`595061a`→`c7eb3a2`: отклонённые кандидаты и успешные final builds; `dedb578`: native iOS runtime. Эксперимент не интегрирован, minimum-runtime proof отсутствует |
| Android runtime | `pokazhi-p00-android` / `implementation/p01-android-runtime` | `a4dd51d`→`13bb635`: [API35 ARM64 UI/focus/SQLite/callback evidence](../redesign-audit/android-platform-runtime.md), 5 просмотренных PNG; baseline не затронут |

Текущий probe stack: Kotlin/KMP/compiler2.2.21, CMP1.9.3 (Android Compose1.9.4), SQLite2.5.2, AGP8.6.1, compileSdk35/minSdk21, Gradle8.7, JDK17.0.19, Xcode27.2. Исторический iOS executable имел min14; после явного изменения R15 commit `d85fcf1` собирает app exact17.0 и device-arm64 framework. `c7eb3a2` снимает AGP/compileSdk blocker, `dedb578` добавляет actual native iOS SQLite check. Это проверенный compile/current-runtime кандидат, не принятый G1 и не product stack, подключённый к библиотеке.

Все эти worktrees созданы штатным OpenCode tool под `/Users/aacidov/.local/share/opencode/worktree/67846f/`. Tool создал P00 worktrees с detached HEAD; завершённые Android/Electron commits дополнительно закреплены именованными ветками. Чужие существовавшие worktrees/ветки не изменяются. Вложенной делегации нет.

## Проверенное координатором

- [Baseline artifact evidence](../redesign-audit/baseline-artifact-evidence.md): snapshot 140 исходных файлов с проверкой хешей; коммит `3deade7` сохраняет 135 неигнорируемых docs/PNG. Проверено, что 138 неизменявшихся оригинальных файлов совпадают со snapshot; два Markdown обновлены явно. Исходные PNG сохранены.
- Локальный APK1.4/5 сохранён; `:app:assembleDebug` успешен, SHA до/после совпадает. Это локальный debug baseline, не магазинная подпись/chain.
- ADB MCP `pm path` + `sha256sum` подтвердили совпадение установленных `base.apk` обоих выделенных AVD с сохранённым локальным baseline. Private library/preferences при этом не читались.
- Старые PNG20/21 визуально прочитаны; phone21 — fullscreen IME, не редактор. В `screens.md` это теперь обозначено явно, без подмены изображения.
- [Платформенный preflight](../redesign-audit/platform-preflight.md): фактические AAR manifests проверены; доступное окружение инвентаризировано. Android21/armv7 и iOS14 runtime не подменены современными эмуляторами.
- Новые characterization tests legacy password writer/verification используют только synthetic Robolectric preferences. Они не меняют защиту приложения или настройки устройств и не являются P08.
- Focused `:app:testDebugUnitTest --tests su.linka.pictures.components.ParentPasswordDialogTest`: итог 5/5, 0 skipped. Первый запуск имел один infrastructure fetch failure (`Connection reset` до application setup); повтор той же команды без code changes прошёл. Сценарии не отключались.
- Та же focused class в `:app:testReleaseUnitTest`: **BUILD SUCCESSFUL**, 5/5, 0 skipped/failures/errors по XML. Production signing/key не использовались.
- Tablet test library намеренно дополнена новым `P00TabletSave` (6→7), исходные шесть наборов не открывались для изменения. COORD просмотрел save confirmation/library PNG; новый набор оставлен, не удаляется. Качество/валидность TTS-аудио и offline-only voice не доказаны UI success.
- Phone `emulator-5554` отключился при вводе имени собственного тестового набора; timeout/`error: closed`, повторный ADB MCP list показывает только tablet. Автоповтора ввода/перезапуска/очистки не было, несохранённое состояние phone неизвестно.
- [iOS runtime evidence](../redesign-audit/ios-platform-runtime.md): COORD создал отдельный собственный iOS26.5 simulator, установил только `.platformprobe`, визуально проверил startup PNG и выполнил native SQLite assert harness. Ни один чужой simulator не сбрасывался; iOS14/полный UI-ввод этим не проверены.
- Android35 ARM64 runtime: installed SHA совпал с probe artifact; реальные Unicode и ASCII/апостроф readback/callback, focus/IME/clearFocus успешны; собственный SQLite-файл 12288 bytes. Probe остановлен и оставлен установленным, baseline app/библиотека не использованы.

## Следующий переход

После поправки R15 проверяется новая сборка target17 в изолированном probe. Для перехода по прежнему порядку нужны Android21 ARMv7/ARM64 и **iOS17** runtime среды, оставшиеся G0 проверки; они пока не установлены. Официальный API21 ARM64 image доступен для скачивания; совместимость конкретного старого образа с Emulator нужно проверить запуском. ARMv7 runtime — отдельная проверка. Исторический availability-case API15 на iOS14 больше не блокирует новую цель17; общая minimum-runtime приёмка остаётся обязательной. Пользователь изменил минимум, но не разрешил пропустить gates; P02–P13 пока не начаты.
