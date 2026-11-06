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

Параметры доски, форма карточки и inline-сетка используют общий
`BufferedAuthoringInput<T>`: наблюдаемое локальное значение, один последний token
и нижнюю границу `editVersion`, без истории ввода. Payload охватывает все поля
формы; обработчики копируют текущее локальное значение, не предыдущий render.
Буфер привязан к owner/epoch/draft/phase и странице/карточке; создание Grid form
само по себе его не сбрасывает. Явная отмена инвалидирует pending token.

Перед UI-dispatch callback проверяет owner/epoch/draft и целевую форму, извлекает
только immutable payload и версию. Задача `rememberCoroutineScope` на
`Dispatchers.Main.immediate` не удерживает snapshot всей доски. StateFlow-модель
поступает через `SideEffect`; совпадение текста не считается подтверждением.
SaveGrid/SaveCard/Preview/Synthesize/ImageFromText ждут admission, но набор текста
не блокируется автосохранением. Отклонение команды в `LinkaApp` при недоступных
commands также вызывает onHandled с фактическим состоянием, не отправляя команду.

Первичный application gate: 62 focused authoring tests PASS, включая задержанный
durable save, denied/foreign/closed commands и throwing observer. Native input
RED→GREEN остаётся отдельной проверкой основной сессии, не предполагается.

UI gate: **63 JVM tests PASS**, 0 failures/errors/skips, включая **9 новых**
проверок production-helper: delayed echoes, A→AB→A/token ACK, старые версии после
ACK, refusal, целый двухполевой payload, cancel, новая authority и много edits без
зависимости от старых token. Проверены пакеты ui/editor, card, components,
communication, theme; `compileKotlinJvm` компилирует общие UI callers. Команда:

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew :shared:jvmTest \
  --tests 'su.linka.pictures.ui.editor.*' --tests 'su.linka.pictures.ui.card.*' \
  --tests 'su.linka.pictures.ui.components.*' --tests 'su.linka.pictures.ui.communication.*' \
  --tests 'su.linka.pictures.ui.theme.*' --offline --max-workers=1
```

Это проверки UI-state, не доказательство native IME/input, layout или release
готовности. Android/iOS keyboard, APK/framework и security/core review выполняет
основная сессия; storage/guard/доступы этим UI-изменением не меняются.
