# Android native audio prerequisite: owned-device evidence

5 ноября 2026. Это component/runtime evidence, **не полный P06/G1/G4 или выпуск**.
Все device actions выполнялись через ADB MCP на проверенных owned AVD:

| Serial | AVD | API / ABI |
|---|---|---|
| emulator-5554 | linka_platform_probe_api21_arm64 | 21 / arm64-v8a |
| emulator-5556 | linka_audit_tablet | 35 / arm64-v8a |

## Изоляция и артефакты

Self-instrumented package/target — `su.linka.pictures.shared.test`, не normal
`su.linka.pictures`. Перед первой установкой package отсутствовал; дальнейшие
reinstall обновляли только этот уже owned test UID, без clear/uninstall/grants.
После прогона test package остановлен, data/AVD не сбрасывались.

aapt подтвердил min21, test target34, ARMv7/ARM64 packaging, отсутствие
RECORD_AUDIO, удалённый FirebaseInitProvider и analytics enabled=false /
deactivated=true. Legacy INTERNET/AD_ID permissions и SDK classes остаются:
это не общая privacy acceptance и не production cleanup P14.
Normal app target35 не менялся; target34 tests не доказывают его foreground
Activity/audio-focus policy. Реальный ARMv7 не запускался.

| Frozen APK | SHA256 | Результат |
|---|---|---|
| branch5875143 native review fixes | `fd68c16cf0e1a2eac4d1606b05d789865846c8e03f5ef95d2d1e039296c7bca3` | API21 25/25, API35 25/25 |
| main728f1da integration | `9885fbd55552712f7be66f0696efcc6c90b2406474d394a627ea825f5951431e` | API21 25/25 (0.437s), API35 25/25 (0.793s) |
| main152cbb9 legacy fixtures + Unicode guard | `b121b60b77e3422900d130aab2aa5030a1699254871256528281cf912cbd9231` | API21 **30/31**, API35 **31/31** |

Последняя строка — **красный API21 gate**, не успешная поддержка всего набора.
Единственная ошибка — `rawAmrNbDecodedDespiteWaveNameAndHint`, результат
UNSUPPORTED_MEDIA. Тест не ослаблялся и не исключался. Root cause исследуется
по extractor metadata/decoder selection; MIME/причина пока не объявлены фактом.

## Что реально проверено

Обязательные real paths: bounded PNG decode, WAV → MediaExtractor/MediaCodec
PCM, MediaPlayer prepare/start/terminal callback; malformed/CRC/size/duration
refusal, denied/cancelled/synthetic-success-with-real-denial mic permission,
request generation/cancellation, nonempty-temp preservation, float-buffer
finite validation и NUL/malformed speech refusal. Float helper tests не
являются actual float-codec proof. Микрофон не запускался/не разрешался.
TTS проверен отрицательно с отсутствующим voice; positive local TTS, запись,
route/background/silent/headset/Bluetooth/physical AAC остаются OPEN.

Positive legacy fixtures — собственные sine bytes, не voice/личные media.
Host producer/embedded SHA описаны в [P06 report](../development/p06-android-audio.md).
Source hashes до/после inspection совпали; extension/MIME hints намеренно неверны.
Ни одна исходная запись не переписывалась ради совместимости.

| Actual native decoder | API21 | API35 |
|---|---|---|
| WAV под .ogg именем/hint | PCM-derived100ms | PCM-derived100ms |
| True MP3, SHA796a8d… | PCM-derived200ms | PCM-derived200ms |
| True Vorbis, SHA98305c… | PCM-derived200ms | PCM-derived200ms |
| AMR-NB-in-3GP, SHAf81c04… | PCM-derived220ms | PCM-derived220ms |
| Raw AMR-NB, SHA94f6fa… | **UNSUPPORTED_MEDIA** | PCM-derived220ms |

Host Vorbis output256ms включает decoder padding, native200ms не подгонялся.
Положительные значения получены из actual decoded frames, не file metadata;
fixture asserts допускают честное platform padding. Эти decoder tests не
означают отдельный actual MediaPlayer playback каждого legacy формата.

## Логи и ограничения

Raw instrumentation logs сохранены MCP pull только в approved session temp:
`p06-android-5875143-api{21,35}-instrumentation.txt`,
`p06-android-main-728f1da-api{21,35}.txt`,
`p06-legacy-unicode-api{21,35}.txt`. Их статус-коды и final summaries проверены;
INSTRUMENTATION_CODE=-1 сам по себе **не** использовался как proof успеха.
Нативные duration rows прочитаны только по unique tag `P06CodecFixture`,
без общего logcat/личных сообщений. Compilation и packaging не заменяют
runtime; эмитированные bad/missing/denied результаты не выдаются за positive proof.
