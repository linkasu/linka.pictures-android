# P01: реальные codec-ограничения iOS17

4 ноября 2026 года. Это результаты decoder preflight, не готовый P07 адаптер и не G1 PASS.

## Проверенные файлы и пути

В `implementation/p01-platform-probe` commits `108d252`/`776dfc1` содержат bounded Swift harnesses и полный отчёт `docs/development/codec-capabilities.md`. На owned iOS17.0/21A328 проверены **AVAudioFile** и **AVURLAsset + AVAssetReaderTrackOutput**, с настоящим PCM-чтением, ограничениями bytes/frames/duration и отрицательными проверками границ.

| Синтетический формат | Реальные PCM frames | Результат на17.0 |
|---|---:|---|
| WAV PCM16, stereo16kHz | 3200 | PASS обоими API |
| MP3, stereo16kHz | 4608, включая padding | PASS обоими API |
| Настоящий Ogg Vorbis | 0 | FAIL: AudioFile1954115647; AssetReader track-load -11828 / underlying -12847 |

FFmpeg/ffprobe используются только как уже установленный host fixture-tool, не как shipped GPL app dependency. Микрофон, личные/bundled записи, playback, сетевой TTS, разрешения и настройки audio-route не использовались. Padding не подрезался ради ожидаемого результата.

## AMR-NB/3GPP: fixture gap устранён без микрофона

Отдельная branch `implementation/p01-amr-fixture`, commit `9f335b1`: test-only package `.platformprobe.codecs`, public Android21 `OMX.google.amrnb.encoder`, синтетический PCM mono8kHz/440Hz,1600 samples. Encoder EOS дал11 frames. Raw AMR358 bytes, SHA256 `94f6facdcb17482cba9590b2f83068cc61c85a8adb0270032557e7487c125934`; host remux `-c:a copy` дал3GP962 bytes, SHA256 `f81c04f29cffcba93be208c36cfc0e2df9196c3fb2e097d5e7234a17ed72426c`.

Host действительно декодировал оба в одинаковые **1760 finite samples/0.22s**, RMS≈0.09661. Это настоящий AMR-NB, не AAC и не придуманная compressed-frame последовательность. AudioRecord/AudioSource/RECORD_AUDIO не использовались; новый тестовый UID не читает библиотеку или БД другого приложения.

COORD затем выполнил оба native iOS17 harnesses на этих exact fixtures:

- AVAudioFile: raw AMR открылся, но readPCM вернул1718449215 (`fmt?`),0 frames; 3GP не открылся (2003334207).
- AVAssetReader: raw AMR имеет один track, но не прошёл duration guard (`AssetProbeError`3); у3GP audio tracks0 (`AssetProbeError`2). Reader не создан, PCM0; ошибки собственного guard не выдаются за OS decoder error.
- Независимый public AudioToolbox query: AMR decoder description bytes0 при status0; `AudioConverterNew` AMR→PCM16 вернул1718449215, converter не создан. Диагностический source `iosApp/AMRAvailabilityProbe.swift` и результат сохранены в probe-ветке.

## Следствие для реализации

На проверенном simulator17.0 штатные пути не покрыли весь обязательный codec-набор. Нужен подтверждённый локальный decoder/compatibility adapter либо дополнительное доказательство на целевом физическом устройстве. Это не разрешение молча заменить запись TTS, пропустить звук, перекодировать исходный пользовательский архив на месте или повысить минимум ОС.

Новая библиотека/лицензия пока **не выбрана и не интегрирована**. Physical codecs, Android decoder-путь, malformed/large media, playback/cancellation и полный AAC quality gate открыты. Fixture binaries остаются в approved session temp; production media и исходники соседнего Electron не изменялись.
