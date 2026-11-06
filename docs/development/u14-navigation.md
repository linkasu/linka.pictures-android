# UI 1.4: admission и lifecycle

| Состояние / намерение | Автоматически | Только явно |
| --- | --- | --- |
| Новый navigator | Один `start` открывает библиотеку; до завершения loading | Последующие переходы |
| Библиотека ↔ общение | Сохраняется CommunicationSession и immutable playback | Выбор доски / команды общения |
| Новый маршрут редактора, Unprotected, IDLE, canonical check Completed | Один первоначально выбранный New/Edit/ResumeDraft | Последующие команды |
| Protected / отказ admission / устаревший маршрут | Ничего | Контекстная авторизация, затем «Начать» |
| PAUSED / background / reauth | Нет повторного initial intent | Только AuthoringAction.Resume |
| Transfer pending / claimed / running | Существующие отмена, preview и safe stop без изменений | После picker свежий unlock и отдельное подтверждение |
| SETTINGS для выбранной доски | Чтение проверенного snapshot, без default-save | Сохранить pager и явный глобальный COMPOSE/DIRECT; SetMode только после Completed |
| Disposal / lifecycle failure | Invalidate owner до cancel child; поздний start не создаёт owner | При quarantine общение остаётся видимым, без auto-apply |

Persisted state, schema, writers и counters не меняются. Settings пишет только существующий LibraryController с captured epoch/revision и canonical guard. Lock order сохраняется: navigation transition → close; committed notification выполняется существующим путём. Никаких новых DB/FS/native вызовов в image demand или freshness checks. Старые и новые binaries используют ту же схему; rollback не требует миграций.

Image demand привязан к реальному route/epoch/session, берётся только из composed visible demand. Старый owner не должен очищать demand нового. UI auto-entry живёт исключительно в presentation: navigator не отправляет authoring initial commands; guard AuthoringSession неизменен.

## Integration seam (ещё не подключён в этой lane)

На базе `21bbbeb + a9fc68c` CommunicationScreen не имеет `onOpenBoardSettings`, а LibraryScreen ещё не имеет trailing `gate`. Чужие модули не изменены. После их интеграции parent должен:

- Передать настоящий `access` в LibraryScreen.gate.
- Связать communication callback с `navigate(SETTINGS, board = message.message.cursor?.boardId)`.
- Для SETTINGS с boardId показать CommunicationScreen под `BoardSettingsDialog`, закрывать обратно в COMMUNICATION. Обычный SETTINGS без boardId оставить глобальным экраном.
- Исключить только board-scoped SETTINGS из существующего audio.interrupt/CommunicationCommand.Stop при входе; глобальный SETTINGS сохраняет прежнее поведение. Сохранить canonical authorization owner и весь navigation close guard.
- В dialog.onSaved обновить portable settings; в onSetMode явно dispatch SetMode. Сам dialog вызывает onSetMode только после Completed и дополнительной актуальной context-проверки. После конфликта сохраняет snapshot и ввод; после unknown блокирует повтор.
- Учитывать communication image demand, пока CommunicationScreen видим под модальным окном; не запрашивать целую page и не сбрасывать message.

Встроенный icon pack в текущих dependencies отсутствует: back использует доступный 48dp IconButton с текстовым шевроном и contentDescription, без новой зависимости. Старый ImageDemand.kt сохранён, поскольку у него остаются собственные тесты; root больше его не вызывает.

Проверка: JDK 17, `./gradlew :shared:jvmTest --tests 'su.linka.pictures.ui.app.*' --tests 'su.linka.pictures.ui.access.*' --tests 'su.linka.pictures.ui.portability.*' --max-workers=1 --offline`. Native/ADB/FFmpeg и полный gate не запускались.
