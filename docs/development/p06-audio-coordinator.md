# P06/P07 prerequisite: общий AudioCoordinator

База: `08b7e64fa598f575de440f92319c01ea93850a61`; ветка `implementation/p06-audio-coordinator`. Реализован frozen `class AudioCoordinator internal constructor(backend, media, files, scope) : AudioPort`. Ports, модели, text, communication, storage, Gradle и native implementations не изменялись. Это общий координатор, **не native P06/P07 acceptance**, не доказательство codec/physical/G1. G0/G1/G4 и выпускные gates остаются OPEN.

## Арбитр и ограничение ресурсов

- Один actor владеет наблюдаемым состоянием и запуском native worker. Admission использует CAS immutable Control, wake-channel conflated и не содержит requests/resources. Один native job и один latest intent: отменённые pending requests немедленно освобождаются, очередь снимков/leases/jobs не растёт.
- Новый explicit запрос отменяет прежний. Actor вызывает `backend.stop(oldId)` и cancellation до нового preflight. Пока некооперативный старый primitive не завершится, следующий native job не запускается: ресурсы ограничены одним active и одним pending, без наложения звука и без бесконечного spawn/cancel jobs.
- `stop(owner,id)` проверяет текущего владельца и ID, не затрагивает чужой/новый запрос. Явный stop — CANCELLED/Cancelled, interrupt — INTERRUPTED/Failed(INTERRUPTED); autoreplay/retry нет. Record/synthesize проходят того же арбитра, не отдельную очередь.
- Duplicate RequestId отбрасывается в bounded окне последних 1024 принятых запросов. Каждая доставленная playback lease потребляется даже при duplicate/empty/replacement/preflight error. File-generating duplicate получает Cancelled, не повторно выданный чужой temp.
- Отмена parent scope закрывает admission даже до старта actor, отменяет/останавливает active, освобождает pending и завершает ожидающих callers. Native job completion освобождает active lease и при отмене до старта coroutine. Некооперативный поздний terminal result также имеет cleanup после закрытия actor.

## Preflight и текст

- Список segments копируется при play. Весь plan разрешает clips через открытую входную lease, проверяет membership assets и inspectClip всех записей до первого playClip/speak. Ошибка в позднем фрагменте не запускает ранний. Никаких silence/TTS replacements для записи.
- Порядок recording/text сохраняется, TTS не дублирует запись. Recording-only и исходные blank-only text segments не требуют голоса; blank-only исходный segment не вызывает speak.
- `voices` — исходный backend StateFlow, не optimistic копия capability. Нужен LOCAL_VOICE_READY для текста; explicit preferredVoiceId должен существовать. Явный language tag сверяется с объявленными local tags без учёта регистра, без угадывания alias/сетевого fallback. Null locale/voice передаются backend для его фактического локального default. Окончательную OS/local-only проверку выполняет native backend.
- Для playback положительный maxTextLength проверяется до речи. `speechChunks` использует существующие Unicode16 graphemeBoundaries; каждый chunk не больше max UTF16 units, одна слишком большая графема даёт LIMIT_EXCEEDED всему plan. Небланковый исходный text сохраняет все символы/whitespace, без trim. Числа 0/negative/Int.MIN_VALUE не оборачиваются в допустимый budget.
- Prepared chunk list копируется до исполнения. Изменение capabilities при preflight даёт отказ до первого звука; во время playback — interruption без повторения. SynthesisRequest передаётся native synthesize без изменения текста/locale/voice: объединения нескольких synthesized файлов frozen backend не предоставляет.

## TemporaryFile и callbacks

- Record показывает REQUESTING_PERMISSION, затем только разрешённые RECORDING/FINISHING transitions от актуального backend callback; callback READY не выдаёт файл. finishRecording действует только для точных active owner/id и RECORDING, не чужого/new request.
- Completed temp coordinator сначала владеет сам, затем inspectClip проверяет фактический результат. Только current generation после проверки становится READY и получает Completed. Failed/Cancelled/Interrupted, inspect exception и поздний old Completed удаляют только TemporaryFile через files.discard. Immutable clip LocalFile не передаётся discard.
- Передача caller-у использует cancellation-safe continuation resume: cancellation после проверки/authorization, но до возобновления caller, удаляет недоставленный temp. После фактического await result файл принадлежит caller, последующее закрытие coordinator/cancellation caller его не удаляет. Отдельный completion token не даёт отмене rejected duplicate изменить READY старого результата с тем же RequestId.
- UnconfirmedCommit запрещён штатным audio/read контрактом. Защитные branches сохраняют этот OperationResult и COMMIT_OUTCOME_UNKNOWN, не объявляя Completed/Cancelled/известный rollback; нового AudioPhase enum не добавлено, диагностика доступна в FAILED + problem. Автоматического повтора нет.
- Native backend по-прежнему обязан корректно останавливать OS primitives и удалять собственные incomplete/non-delivered результаты; shared может очистить только полученный handle. Без terminal response некооперативного backend следующий запрос остаётся pending, а не запускается параллельно.

## Focused checks и correctness review

25 common audio tests используют реальный coordinator и контролируемые backend/media/file fakes. Проверяются mixed order, entire preflight refusal, missing clip/lease asset/voice/language, recording-only/empty/blank, Unicode combining/Indic/flags/emoji и max budgets; immutable plan snapshot; voice change; exact stop/finish; stop vs interrupt; permission refusal; failure/exception/cancellation/unknown; valid synth/record transfer; поздние phases/temp; 500 replacements при некооперативном native; parent cancellation и pre-start cleanup; cancellation между validated resume и caller dispatch; rejected duplicate не изменяет предыдущий READY.

Focused JVM audio tests: **25/25 PASS**, 0 skipped. Coherent-diff review проверил single-worker ownership, bounded pending, cancellation cleanup и handoff linearization; добавлены asset membership preflight, cleanup при отмене actor до старта и уникальный completion token для duplicate/cancellation race.

Финальный gate после review, 5 ноября 2026: `./gradlew :shared:jvmTest :shared:compileKotlinIosSimulatorArm64 --console=plain` — BUILD SUCCESSFUL (4s). JVM **66/66 PASS**, 0 skipped (25 audio + все исходные 41, включая Unicode16 corpus 1093 vectors); Native simulator-arm64 compilation **PASS**, без запуска simulator. Окружение: JAVA_HOME `/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`, ANDROID_HOME `/Users/aacidov/Library/Android/sdk`, DEVELOPER_DIR `/Applications/Xcode.app/Contents/Developer`.

Не выполнялись устройства/simulators, native audio/permissions/codecs/OS sessions, реальный storage IO, сеть, CI/push/signing или установка приложения. Существующие 41 тест не редактировались; последующий Native runtime/integration gate выполняет COORD.
