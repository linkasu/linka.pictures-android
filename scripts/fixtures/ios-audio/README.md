# Synthetic audio regression fixtures

Exact bytes retained from the original iOS17 codec preflight
`1c1cd004-e6c7-41e5-bfed-3c4c39933762`; no user recordings. Hashes remain the
original pins in `scripts/preflight-ios-ffmpeg.py:EXPECTED` and are checked before
each bridge proof. They are test inputs, not packaged application resources.

| File | Bytes | SHA256 |
|---|---:|---|
| tone.wav | 12844 | f408f45e38a040910c40ac3851fafa8ed5248d04fc074d841b8ecbe0b93f2e51 |
| mp3-first.mp3 | 2612 | 796a8d642760fd8bb1e4e216b5ab2ef02e73dd633bf3a27bfcce6e9450c9b026 |
| vorbis-first.ogg | 5063 | 98305ce0d4bf14c7189c25835e2f87b8a602b462232a4e8e285fae0480cf7208 |
| synthetic-440hz.amr | 358 | 94f6facdcb17482cba9590b2f83068cc61c85a8adb0270032557e7487c125934 |
| synthetic-440hz-amr.3gp | 962 | f81c04f29cffcba93be208c36cfc0e2df9196c3fb2e097d5e7234a17ed72426c |

WAV is a 3200-frame stereo PCM16 sine signal, 16kHz, channels440/660Hz,
amplitude6000. The existing `generate_legacy_codec_fixtures.py` supplies the WAV
envelope helper. MP3/Vorbis were encoded with host FFmpeg8.1.2, `-map_metadata -1
-fflags +bitexact -flags:a +bitexact`, respectively `-c:a libmp3lame -b:a 64k -f mp3`
and `-c:a vorbis -strict experimental -f ogg`. AMR/3GP are the original synthetic
440Hz, 8kHz mono AMR-NB fixtures from P01, retained unchanged rather than re-encoded.
See `docs/redesign-audit/codec-preflight-summary.md` and
`docs/development/ios-ffmpeg-preflight.md` for provenance and original results.
