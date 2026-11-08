# Реализация замечаний ручной приёмки — Android / iOS

База: d1493b26edd2651806a9d61305fe62a7a18d5ad1. Пользователь разрешил реализацию
и отдельный коммит каждого завершённого этапа; push/публикация не разрешены.

| Этап | Объём | Состояние |
|---|---|---|
| S0 | Frozen schema1 fixture, контракты | PASS: 1 JVM characterization; визуальные/content gates открыты |
| S1 | Schema2, старые данные и настройки | PASS: focused storage/backup; native old-binary gate ещё не выполнен |
| S2 | Папки и guarded операции | PASS: storage/controller focused; UI и backup в S3/S4 |
| S3 | Snapshot, backup/recovery | PASS: storage/backup/integration/transfer focused; native cross-platform pending |
| S4 | UI папок и меню по удержанию | PASS: JVM presentation/back; native gestures pending |
| S5 | Пароль до открытия настроек набора | TODO |
| S6 | Фраза 32% с адаптацией, боковые стрелки | TODO |
| S7 | Безопасное переключение карточек с автосохранением | TODO |
| S8 | Перетаскивание: обмен ячеек текущей страницы | TODO |
| S9 | Опциональный drag в COMPOSE, append во фразу | TODO |
| S10 | Новый единый стартовый комплект Android/iOS | BLOCKED: содержание и права не утверждены |

Опции листания/drag сохраняются для каждого набора. DIRECT остаётся tap-only.
Папки удаляются только пустыми. Пользовательские данные при обновлении сохраняются.
Компактный селектор карточки требует отдельного визуального утверждения.

План: /Users/aacidov/.opencode/plan/linka-manual-review-2026-11-08-coding-plan.md.
Нативные/визуальные PASS не предполагаются по результатам JVM-тестов.
