# Ввод редактора и подтверждение обработки

Native API35 input `LINKa-EDITOR-6F9C` воспроизводимо возвращал искажённый текст
при прямом `TextField(document.name)` с асинхронным actor round-trip. UI должен
обновлять собственный ввод синхронно, не подменяя полномочия/валидацию actor.

`AuthoringCommand.onHandled` получает фактически принятую или отклонённую state
после обработки (либо отказа enqueue). Это **не** подтверждение durability:
`DraftSavePhase`/`durableVersion` остаются отдельными. Listener failure не должен
останавливать уже принятое изменение; используется существующая изоляция cleanup.

`AuthoringState.editVersion` — только RAM-порядок изменений данного черновика.
Он сбрасывается при initialize и увеличивается через существующий `change`.
JSON/SQLite/backup и writer/guard/lifecycle rules не изменены.

UI хранит только последний pending token и локальное значение. Старые callbacks
игнорируются; после последнего callback старые model echoes с меньшим editVersion
не перезаписывают ввод. Авторитетный callback при отказе возвращает принятое
значение, а сохранение/публикация по-прежнему выполняются только guarded actor.
Полный payload полей собирается из текущего локального ввода, не старого render.

Первичный application gate:62 focused authoring tests PASS, включая задержанный
durable save, denied/foreign/closed commands и throwing observer. Native input
RED→GREEN и UI-buffer проверки фиксируются после подключения UI, не предполагаются.
