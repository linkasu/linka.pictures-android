# Реальные audio callbacks в UIKit XCTest host

Два независимых app-hosted XCTest в `AudioTests.swift` вызывают asynchronous
`AudioAppHostChecks` из static `SharedAppHostTests.framework`, линкованного только
test bundle существующего UIKit host. Production root, library, credentials и
второй StorageRuntime не открываются. Keychain cases и signing config не меняются.

## Проверяемые границы

* `testClipCompletesThroughNativePlayerCallback`: создаёт зарегистрированный через
  `IosPlatformFilesPort` WAV (PCM16LE mono 16 kHz, 300 ms, 440 Hz), проверяет header,
  число frames и реальный decoder inspection; `IosAudioDecoder.decodeToWave` готовит
  другой owned файл; реальный `IosAudioBackend.playClip` ждёт успешный
  `AVAudioPlayerDelegate.audioPlayerDidFinishPlaying`.
* `testInstalledVoiceSpeechCompletesThroughNativeCallback`: выбирает конкретный
  voice ID и language из текущего native `voices`, передаёт непустую публичную фразу
  `Linka audio check.` в `speak`; реальный backend ждёт соответствующий
  `AVSpeechSynthesizerDelegate.didFinishSpeechUtterance`.

`null` передаётся в Swift только после `OperationResult.Completed` и cleanup.
Каждый check имеет реальный `SupervisorJob + Dispatchers.Main.immediate`, timeout
20 s; XCTest `wait(for:timeout:)` с 30 s обслуживает настоящий main run loop.
Нет runBlocking/runTest, подмены Main, fake decoder, file broker или AV delegates.
Admission требует actual UIApplication Active; backend проверяет actual state и
далее. Concurrent facade call получает bounded busy failure, без очереди/retry.
Нет голоса, inactive host, timeout, cancellation, native failure и cleanup failure
означают FAIL, не skip/assumption/no-op. Native exception details не логируются.

## Изоляция и завершение

У каждого check свежий UUID subtree только в `NSTemporaryDirectory`, с отдельными
temporary/media roots и настоящим `FileSystem.SYSTEM`. Root создаётся must-create;
recursive cleanup разрешён только после доказанного создания именно этого root.
После terminal operation backend stop/cancel/join и очередь disposal observers
завершаются до callback. Source broker handle отзывается, pending file deletions
дожидаются завершения, files job отменяется, удаляется только owned subtree.
Cleanup failure не превращается в успех и не стирает первичную причину.
Permission callback намеренно падает при вызове: запись/микрофон не используются.

## Runtime и причина первого clip failure

6 ноября 2026: Native framework/header и Swift XCTest ABI собраны. Первый
диагностический clip-only runtime дал **RED, 0.242s: `tone: IrLinkageError`**.
Это ошибка до AVAudioPlayer, не доказательство неработающего native playback.
`associateWith(main)` не передал CInterop декодера в custom `appHostTest`:
main/обычные tests имели его во входах, custom compilation/framework — нет.

Теперь custom compilation явно получает тот же CInterop output с producer task
dependency. `verifyAppHostTestFramework` проверяет decoder link input и оба audio
exports; partial-linkage ошибки останавливают сборку. Decoder seed прошёл
`VERIFIED_FASTPATH`, FFmpeg/голоса не пересобирались и не скачивались.

После исправления **actual clip callback XCTest 1/1 PASS, 0.672s** на explicit owned
iOS17.0. До запуска проверены strict signatures/own-group Mach-O payload/min17;
после запуска executable SHA host/test совпали с проверенными продуктами.
`audio-linked-clip.xcresult` и логи сохранены в локальном OpenCode temp.

Непустой TTS на minimum **iOS17 Simulator остаётся OPEN/RED**: runtime дал `INTERRUPTED`,
`TTSErrorDomain -4010`/audio-unit `-3000` и отсутствующий framework executable
в Simulator runtime. Перечисление voice descriptors не доказывает работоспособный
установленный офлайн-голос. Голоса, network/volume host не менялись; ошибки не
заменяются skip/no-op. Существующие entitlement/signature gates из
[host инструкции](ios-keychain-xctest-host.md) сохраняются.

На отдельном owned **iOS26.5** оба реальных hosted callback-теста прошли:
**2/2 PASS,2.480s** (clip0.605s, непустой TTS1.875s). Существующий xcresult повторно
проверен read-only; `evidence/audio-lease-2026-11-06/ios26-callback-evidence.json`
сохраняет результаты и SHA лога. Голоса не устанавливались, network/volume не
менялись. Это положительный callback proof на26.5, не исправление17.0 и не
minimum/offline/Russian/physical/AAC acceptance.

Отдельный product UI после исправления составных media proofs прошёл
`testExistingPhrasePlaybackStopAndReplay`: **1/1 PASS,45.232s**, recording-only
ХОЧУ КУШАТЬ действительно завершилась, Stop после PLAYING вернул IDLE, повторное
воспроизведение завершилось, library/reopen сохранил фразу. Обе записи — реальные
RIFF/WAVE PCM из опубликованной fixture, несмотря на расширение `.ogg`, не
сгенерированный tone. Это не Vorbis proof и не физическое
измерение задержки/тишины после Stop и не успешный TTS.

**Не доказано hosted clip проверкой:** offline/network independence (speechVoices metadata
недостаточно), слышимость/качество/громкость, physical device, AAC/все форматы,
recording, synthesis-to-file, полный G4. Публичный backend не даёт точного
native playback-start marker; production hooks ради такой видимости не добавлены.
