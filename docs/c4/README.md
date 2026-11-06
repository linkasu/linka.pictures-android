# Архитектура LINKa «Покажи»

**Целевая система — локальное средство альтернативной коммуникации для Android и iOS/iPadOS. Её задача — помогать человеку выражать собственные мысли, а не управлять коллекцией файлов.**

Принятая основа: **Kotlin Multiplatform, Compose Multiplatform, общие правила поведения и продуктовые экраны, нативные адаптеры возможностей Android и iOS. Без сервера, аккаунтов и обязательного подключения к интернету.** Зафиксированные версии — в [toolchain](../development/toolchain.md).

Это принятая целевая архитектура, не описание готового продукта. C1/C2 описывают цель; C3 отдельно отмечает проверенное ядро и ещё не подключённые части. **Плана реализации здесь нет.** Фактическое состояние и evidence — в [статусе реализации](../development/implementation-status.md).

**Визуальная цель: UI 1.4 + согласованные функции**, не новый редизайн.
При изменении экранов/навигации/тем сначала читать [контракт и атлас 1.4](../development/ui-1.4-parity.md).
Новый development UI не принят; реализация остановлена пользователем, текущая
работа — только документация. Первый gate продолжения — парные заполненные экраны
и подтверждение пользователя, затем оставшаяся интеграция.

Проверенное ядро: CommunicationSession, StorageRuntime, LinkaCodec/BackupCodec,
CheckedZip и AudioCoordinator. Authoring/transfer/library workflows и UI-кандидаты
реализованы частично в составе нового пути, но не приняты как готовые приложения.
Normal Android остаётся legacy; Android development root — WIP, iOS root — отдельные
неинтегрированные commits. Исторические full257/361 и последующие focused116/116
не заменяют текущую полную приёмку. Keychain app-host и native/physical/release gates
OPEN/BLOCKED; Android21/22 exception сохраняется. Actual evidence — в
[статусе](../development/implementation-status.md), точные seams — в [контрактах](../development/code-contracts.md).

## Содержание

1. [Цели приложения](01-goals.md) — назначение, роли, приоритеты, границы и критерии успешности.
2. [Кроссплатформенная система](02-cross-platform.md) — стек, доменная модель, модули, состояния, аудио, хранение, переносимость, защита и свойства качества.
3. [Правила дизайна](03-design-system.md) — пространства работы, экраны, адаптивность, карточки, ввод, доступность, палитра, типографика, формы и ошибки.
4. [C4](04-c4.md) — C1, C2, C3, динамические схемы произнесения и импорта, развёртывание и границы детализации.

Mermaid-схемы встроены в Markdown. Для графического просмотра нужен просмотрщик с поддержкой Mermaid.

## Зафиксированный объём

- Android API 21+: телефоны и планшеты, ARMv7 и ARM64.
- iOS/iPadOS 17+: iPhone и iPad; iOS 14 не является актуальной целью.
- Касание, мышь/трекпад и аппаратная клавиатура.
- Системная доступность, включая TalkBack и VoiceOver.
- Локальные данные, без сервера и аккаунтов.
- Файловый перенос и резервное копирование.
- Один MessageDraft между досками: неизменяемые снимки карточек и буквальный текст, последовательное произнесение записей и локального TTS.
- Чтение whitelist legacy `.linka` и pages/3.0, запись standard pages/3.0; отдельный открытый backup библиотеки, черновиков и переносимых настроек.
- Без собственной системы управления взглядом или сканирования переключателями.

## Основание обследования

Архитектура опирается на повторно обследованную **1.4 / versionCode 5** и прочитанный код тега **`v1.4`**, коммит `2073d912f6c70ab0165823f37e4b5704cf72e953`. Рабочая ветка при сохранении документации — `audit/google-play-v1.4`; история `main` не переписывалась.

В Google Play на момент проверки указаны версия 1.4 и обновление от 19 июля 2026 года. Исследовалась локальная debug-сборка исходников релизного тега, **не извлечённый из магазина APK**. Побайтовая идентичность и привязка загрузки Play Console к Git-коммиту не подтверждены.

Материалы: [повторный аудит 1.4](../redesign-audit/README.md). [Предыдущий аудит локальной 2.0](../redesign-audit-local-2.0/README.md) — архив ошибочно выбранного baseline; его выводы нельзя автоматически переносить на 1.4.

Подтверждённые архитектурные особенности 1.4:

- `shared/src/androidMain/kotlin/su/linka/pictures/SetManifest.kt` связывает документ с файлами.
- `shared/src/androidMain/kotlin/su/linka/pictures/SetsManager.kt` определяет порядок библиотеки временем изменения файлов.
- `app/src/main/java/su/linka/pictures/components/GridButton.kt` превращает подпись в bitmap.
- `app/src/main/java/su/linka/pictures/components/OutputLine.kt` связывает поведение вывода с `withoutSpace`.
- Редактор, аудио и файловые операции тесно связаны с Android.
- Наличие `shared` не означает готовую кроссплатформенность: в 1.4 настроен только Android, а содержательный код в основном находится в `androidMain`.

## Источники

В качестве нормативных ориентиров использованы:

- [Android: Accessibility](https://developer.android.com/design/ui/mobile/guides/foundations/accessibility).
- [Apple: Accessibility](https://developer.apple.com/design/human-interface-guidelines/accessibility).
- [Compose Multiplatform: Accessibility](https://kotlinlang.org/docs/multiplatform/compose-accessibility.html).
- [Compose Multiplatform: iOS accessibility](https://kotlinlang.org/docs/multiplatform/compose-ios-accessibility.html).
- [Android: свойства TTS Voice](https://developer.android.com/reference/android/speech/tts/Voice).
- [Apple: AVSpeechSynthesizer](https://developer.apple.com/documentation/avfaudio/avspeechsynthesizer).
- [WCAG 2.2](https://www.w3.org/WAI/WCAG22/quickref/?versions=2.2).
- [C4 model](https://c4model.com/diagrams).

## Ограничения достоверности

- Повторный скриншот-аудит 1.4 ещё не завершён: библиотека, настройки и шесть встроенных наборов обследованы, редактор не пройден полностью.
- Архитектура не подменяет незавершённый визуальный аудит редактора.
- Проверка iOS, старых аудиоформатов и физических устройств не завершена.
- Минимумы Android21 ARMv7/ARM64 и iOS17 — обязательства приёмки, а не автоматически доказанные свойства стека.
- Численные показатели скорости, размера элементов и контраста — проектные ориентиры или расчёты отдельных пар, не подтверждение качества всей системы.
- G0/G1, реальный ARMv7-прогон и native codec gaps остаются **OPEN**. Условный coding P02–P14 уже разрешён на проверенных Android21 ARM64/iOS17; это не PASS gates и не разрешение работы с пользовательской библиотекой до G4 или выпуска.
- Bootstrap не равен готовому целевому продукту; документ не содержит этапов, задач или плана внедрения.
