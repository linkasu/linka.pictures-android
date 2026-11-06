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

## Статическая проверка и runtime gate

Implementation lane, 6 ноября 2026: Swift frontend parse, `plutil -lint` проекта и
`git diff --check` прошли. В isolated worktree отсутствует `shared/build` с decoder
seed/framework: Native compile/link и generated-header/Swift ABI пока **не проверены**.
Загрузки, signing queries и runtime не выполнялись; это **0 runtime PASS**.

Перед execution собрать `:shared:linkAppHostDebugFrameworkIosSimulatorArm64` из
доступного native decoder seed/cache и проверить exported header для обоих новых
методов, включая Swift closure ABI. Затем typecheck/build XCTest bundle. Один
Swift parse не доказывает bridge ABI, а compile/link не доказывает callback PASS.

Runtime выполняет primary agent только после security review, со штатной ранее
разрешённой Simulator signing в том же genuine UIKit host, explicit owned iOS17
destination и только `LinkaKeychainHostTests/AudioTests`. Существующие entitlement
и signature gates из [host инструкции](ios-keychain-xctest-host.md) сохраняются.
Signing/account/certificate queries, simulator/device actions и audio execution
не входят в implementation lane. Голоса не устанавливаются и не скачиваются;
network/volume host не меняются. Фиксировать 2 отдельных XCTest outcomes.

**Не доказано этой проверкой:** offline/network independence (speechVoices metadata
недостаточно), слышимость/качество/громкость, physical device, AAC/все форматы,
recording, synthesis-to-file, полный G4. Stop-during-playback остаётся отдельным
product UI gate: публичный backend не даёт точного playback-start marker; production
hooks ради такой видимости не добавляются. Runtime PASS нельзя заявлять без запуска.
