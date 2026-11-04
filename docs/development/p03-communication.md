# P03: общий сеанс общения

База: `0816394017adefd3fc77aeebca2a1a052493206e`, frozen G2. Это реализация чистых правил и application state machine, не native/storage/audio адаптеров и не готового продукта. G0/G1/G4 и AAC/физическая/выпускная приёмка остаются OPEN.

## Поведение и владение

- `CommunicationSession` имеет frozen constructor/state/dispatch/interrupt/close. Один actor владеет состоянием; callbacks только ставят события в очередь. UI callback не выполняет storage IO.
- Загрузка восстанавливает сообщение/режим/cursor, но не звук/undo/request. Ошибка чтения/acquire блокирует редактирование, чтобы не перезаписать непрочитанное сообщение пустым; восстановление — новый сеанс после устранения ошибки.
- COMPOSE добавляет immutable snapshots или дополняет последний literal run даже через навигацию. DIRECT заменяет собственный playback, не изменяет общую фразу; InsertText/Backspace/Clear/Speak не произносят и не очищают её неявно. Активный Speak работает как Stop.
- `renderMessage` задаёт разделитель только между смысловыми фрагментами, сохраняет literal whitespace/пунктуацию. Backspace использует существующий Unicode16 `dropLastGrapheme`. `speechPlan` возвращает весь ordered recording/TTS plan либо SPEECH_UNAVAILABLE, без None fallback или частичного успешного плана. Native preflight/voices остаются обязанностью AudioPort.
- OpenBoard/ChangePage не меняют текущий playback snapshot. Изменение содержимого/режима, CloseBoard, interrupt и close останавливают звук, не очищая фразу.
- CommandId dedup: последние 1024 доставки. Новые IDs допускают одинаковый осознанный выбор. Лимиты 4096 parts/65536 UTF16 units rendered text отклоняют всю команду с LIMIT_EXCEEDED, не обрезают прежний текст.
- Навигация имеет generation, звук — owner/request ID. Поздний результат не меняет новый запрос/закрытый сеанс. Некооперативный поздний board read освобождает lease.
- Мгновенное отображение предшествует асинхронным saves с возрастающей version. Каждый save имеет отдельную retained lease до terminal result, включая отмену до старта coroutine. Старый success/failure не меняет статус новой версии. Save failure остаётся FAILED, текущие assets pinned. Следующее явное изменение/cursor/mode сохраняет актуальный полный снимок; новой публичной Retry-команды нет.
- MessageLease объединяет независимые retains из текущего сообщения/leased board/undo без повторного acquire/GC gap. Clear держит один undo, UndoClear потребляет его, следующий clear заменяет. Undo восстанавливает parts, не старую навигацию или режим. close не удаляет persistent message и освобождает leases после собственных pending saves.
- Изменение library epoch инвалидирует сеанс, останавливает playback, запрещает дальнейшие команды и показывает STALE_REQUEST. Старый текст не переносится в новый epoch; explicit reload означает создание нового CommunicationSession. Storage обязан атомарно проверять epoch/version/refs; session не заменяет storage writer.

## Проверки и correctness review

Focused-тесты реализации прошли до общего gate. Review coherent diff проверил режимы, snapshots, atomicity boundary storage, monotonic versions, GC pins, closing/cancellation и stale epoch. Исправлены сохранение выбранного COMPOSE при последующем открытии default DIRECT, one-use undo и cleanup lease при отмене save до старта coroutine.

Common tests используют контролируемые fakes настоящих seams: delayed/error/version/epoch message saves; leased board reads, включая некооперативный поздний ответ; reference-counted leases и GC; playback ownership и поздние audio callbacks. Неиспользуемые операции fake портов падают, а не возвращают успешные заглушки.

Матрица: междосочное «Хочу кашу»; card + посимвольное «мёд»; ведущие space/пунктуация; combining/emoji/Indic backspace; доставка vs осознанный повтор; DIRECT с общей фразой и literal клеткой; playing mutation/stop/navigation/exit; clear/undo/restart; source revision/edit/delete; задержанный и failed save без false Saved; close с pending saves/GC; stale epoch; пределы текста/parts и dedup window; load failure без overwrite.

Окончательный focused gate 5 ноября 2026: `:shared:jvmTest` без filters — **31/31 PASS, 0 skipped**, включая исходные 14 тестов и Unicode16 corpus (1093 vectors); новые проверки — 15 session + 2 rendering/plan. `:shared:compileKotlinIosSimulatorArm64` — **PASS**, без запуска/выбора устройств. Команда: `./gradlew :shared:jvmTest :shared:compileKotlinIosSimulatorArm64 --console=plain`, BUILD SUCCESSFUL (6s). Окружение: JAVA_HOME `/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`, ANDROID_HOME `/Users/aacidov/Library/Android/sdk`, DEVELOPER_DIR `/Applications/Xcode.app/Contents/Developer`.

Не проверены реальные storage/GC/audio implementations, голоса/кодеки, device lifecycle и интеграция shells: их принимают отдельные пакеты и COORD. Данные пользователя, сеть, permissions, CI/push/signing и установки приложения не затрагивались.
