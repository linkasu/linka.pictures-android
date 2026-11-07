# V15: production Android admission — BLOCKED

7 ноября2026 проверен фактический путь запуска. Визуальный перенос не даёт
разрешения открыть новую библиотеку вместо существующей пользовательской.

## Подтверждённый вызов

`app/src/main/AndroidManifest.xml` → `.activity.MainActivity` →
`activity_main` / `SetsManager` / legacy editor/importer/preferences.
Debug manifest выключает эти Activity и запускает `DevelopmentActivity` →
`AndroidProductRuntime.openDevelopment` → `LinkaApp`.
`AndroidProductRuntime` прямо ограничен development sandbox; отсутствует доказанный
production root binding к active receipt cross-store migration.

## Почему manifest swap запрещён

Legacy `filesDir/sets`/prefs, новая SQLite+immutable media и credential record —
разные persisted stores. Разрешение показать новое оформление не доказывает
атомарную активацию этих stores. Простое подключение development runtime может
показать другую/пустую библиотеку вместо пользовательских данных.

## Условия снятия блокировки

1. Исполнить [принятый upgrade protocol](legacy-upgrade-protocol.md) на owned
   синтетической копии: запрет legacy writers до inventory, один coordinator,
   staging generation, отдельные credentials и authoritative active receipt.
2. Проверить все source/prefs/credential sentinels до/после, включая retained,
   settings0..3, empty/Unicode/legacy password states и mixed source versions.
3. Выполнить fault/termination matrix journal/media/settings/credential/active
   rename, explicit Resume и authoritative independent reopen. UNKNOWN не rollback.
4. Проверить повторный startup после пользовательских изменений: никакого
   reimport/reset; отсутствие credential у active root — fail closed.
5. Проверить входящие intents и release privacy без debug fixture provider.
6. Только после этого менять production composition root/manifest и проверять
   upgrade. Старый1.4 binary не объявляется безопасным downgrade нового состояния.

Эти prerequisites не закрываются screenshot/parsing/JVM-green. Production app не
устанавливалась и не подписывалась; пользовательские stores не менялись. Store,
physical/AAC и shipping/legal gates также не выводятся из визуального результата.
Следующий шаг требует отдельного миграционного исполнения и проверки primary,
а не удаления предохранителя ради завершения V15.
