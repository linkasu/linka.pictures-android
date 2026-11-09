#!/usr/bin/env python3
"""Own synthetic fixture generator; host-only ffmpeg, never an application dependency.

Pattern/provenance: own P01 scripts/probe-codec-fixtures.py:26-69, read-only.
COORD approved native vorbis -strict experimental on 2026-11-05, test data only.
Outputs go exclusively to a must-create UUID directory in the approved temp root.
Copy the generated Kotlin through the normal source-review workflow, not at build time.
"""

import array
import base64
import hashlib
import io
import json
import math
from pathlib import Path
import shutil
import subprocess
import sys
import textwrap
import uuid
import wave


TEMP = Path("/private/var/folders/l4/r3plyjwj7pz_nq7zz50x04880000gn/T/opencode")
AMR = TEMP / "p01-amr-fixtures"
PINNED = {
    "synthetic-440hz.amr": (358, "94f6facdcb17482cba9590b2f83068cc61c85a8adb0270032557e7487c125934"),
    "synthetic-440hz-amr.3gp": (962, "f81c04f29cffcba93be208c36cfc0e2df9196c3fb2e097d5e7234a17ed72426c"),
}


def run(command):
    return subprocess.run(command, check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30).stdout


def sha(data):
    return hashlib.sha256(data).hexdigest()


def write_new(path, data):
    with path.open("xb") as output:
        output.write(data)


def wav_bytes(channels, samples):
    if sys.byteorder != "little":
        samples.byteswap()
    output = io.BytesIO()
    with wave.open(output, "wb") as wav:
        wav.setparams((channels, 2, 16000, len(samples) // channels, "NONE", "not compressed"))
        wav.writeframes(samples.tobytes())
    data = output.getvalue()
    assert len(data) == 44 + len(samples) * 2
    assert data[:4] == b"RIFF" and data[8:12] == b"WAVE"
    return data


def main():
    ffmpeg, ffprobe = shutil.which("ffmpeg"), shutil.which("ffprobe")
    assert ffmpeg and ffprobe, "Use existing host tools; do not install dependencies"
    version = run([ffmpeg, "-version"]).decode()
    assert version.splitlines()[0].startswith("ffmpeg version 8.1.2 "), version.splitlines()[0]
    probe_version = run([ffprobe, "-version"]).decode()
    assert probe_version.splitlines()[0].startswith("ffprobe version 8.1.2 "), probe_version.splitlines()[0]
    encoders = {line.split()[1] for line in run([ffmpeg, "-hide_banner", "-encoders"]).decode().splitlines()
                if line.startswith(" A") and len(line.split()) >= 2}
    assert {"vorbis", "libmp3lame"} <= encoders, sorted(encoders)
    immutable = {}
    for name, (size, expected) in PINNED.items():
        data = (AMR / name).read_bytes()
        assert len(data) == size and sha(data) == expected, name
        immutable[name] = data
    assert TEMP.is_dir()
    destination = TEMP / ("p06-legacy-codecs-" + str(uuid.uuid4()))
    destination.mkdir()  # Must create, never reuse/overwrite a fixture directory.
    write_new(destination / "ffmpeg-version.txt", version.encode())
    write_new(destination / "ffprobe-version.txt", probe_version.encode())
    samples = array.array("h")
    for frame in range(3200):
        samples.extend(int(6000 * math.sin(2 * math.pi * frequency * frame / 16000)) for frequency in (440, 660))
    source = destination / "tone.wav"
    write_new(source, wav_bytes(2, samples))
    commands = []
    encoded = []
    for name, encoder, arguments in (
        ("vorbis", "vorbis", ["-strict", "experimental", "-f", "ogg"]),
        ("mp3", "libmp3lame", ["-b:a", "64k", "-f", "mp3"]),
    ):
        paths = []
        for suffix in ("first", "repeat"):
            path = destination / (name + "-" + suffix + (".ogg" if name == "vorbis" else ".mp3"))
            command = [ffmpeg, "-hide_banner", "-loglevel", "error", "-nostdin", "-n", "-i", str(source),
                       "-map_metadata", "-1", "-fflags", "+bitexact", "-flags:a", "+bitexact",
                       "-c:a", encoder, *arguments, str(path)]
            run(command)
            commands.append(command)
            paths.append(path)
        assert paths[0].read_bytes() == paths[1].read_bytes(), "Encoder/muxer output is not reproducible"
        encoded.append((name, paths[0], name, "ogg" if name == "vorbis" else "mp3", 16000, 2))
    fixtures = encoded + [
        ("amr", AMR / "synthetic-440hz.amr", "amr_nb", "amr", 8000, 1),
        ("threeGp", AMR / "synthetic-440hz-amr.3gp", "amr_nb", "3gp", 8000, 1),
    ]
    report = []
    payloads = {}
    for name, path, codec, container, rate, channels in fixtures:
        data = path.read_bytes()
        assert 0 < len(data) <= 65536
        if name == "vorbis":
            assert data[:4] == b"OggS" and data[4] == 0 and data[5] & 2
            payload = 27 + data[26]
            assert data[payload:payload + 7] == b"\x01vorbis"
        if name == "amr":
            assert data.startswith(b"#!AMR\n")
        if name == "threeGp":
            assert data[4:8] == b"ftyp" and data[8:11] == b"3gp"
        probe_command = [ffprobe, "-v", "error", "-show_format", "-show_streams", "-of", "json", str(path)]
        probe = json.loads(run(probe_command))
        assert len(probe["streams"]) == 1
        stream = probe["streams"][0]
        assert stream["codec_type"] == "audio" and stream["codec_name"] == codec
        assert container in probe["format"]["format_name"].split(",")
        assert int(stream["sample_rate"]) == rate and stream["channels"] == channels
        duration = float(probe["format"]["duration"])
        assert 0 < duration <= 1
        decode_command = [ffmpeg, "-v", "error", "-nostdin", "-i", str(path), "-t", "1",
                          "-ar", str(rate), "-ac", str(channels), "-f", "f32le", "pipe:1"]
        pcm = run(decode_command)
        assert len(pcm) % (4 * channels) == 0
        frames = len(pcm) // (4 * channels)
        assert 0 < frames < rate, "Validation must finish before the 1s safety cap, not accept truncated output"
        values = array.array("f")
        values.frombytes(pcm)
        if sys.byteorder != "little":
            values.byteswap()
        assert all(math.isfinite(value) for value in values)
        rms = math.sqrt(sum(value * value for value in values) / len(values))
        assert math.isfinite(rms) and rms > 0
        entry = dict(name=name, bytes=len(data), sha256=sha(data), codec=codec, container=container,
                     sample_rate=rate, channels=channels, probe_seconds=duration,
                     decoded_frames=frames, decoded_seconds=frames / rate, rms=rms, pcm_sha256=sha(pcm))
        report.append(entry)
        payloads[name] = data
        write_new(destination / (name + ".ffprobe.json"), json.dumps(probe, indent=2).encode())
        commands.extend([probe_command, decode_command])
    # Independently reproduce the existing TinyMediaFixtures.wave() bytes, not a new Android fixture generator.
    control = wav_bytes(1, array.array("h", (100 if frame % 2 == 0 else -100 for frame in range(1600))))
    assert len(control) == 3244
    lines = ["package su.linka.pictures.platform.capabilities", "", "import android.util.Base64", "import java.security.MessageDigest", "",
             "// Generated own synthetic test data; ffmpeg 8.1.2, native vorbis experimental + libmp3lame.",
             "// Reproduce: generate_legacy_codec_fixtures.py; P01 AMR/3GP bytes are hash-pinned and read-only.",
             "internal object LegacyCodecFixtures {", f'    const val WAVE_SHA256 = "{sha(control)}"']
    for name, data in payloads.items():
        identifier = "THREE_GP" if name == "threeGp" else name.upper()
        lines.append(f'    const val {identifier}_SHA256 = "{sha(data)}"')
        lines.append(f'    fun {name}(): ByteArray = decode({name}Base64, {identifier}_SHA256)')
    lines += ["", "    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance(\"SHA-256\").digest(bytes)",
              "        .joinToString(\"\") { (it.toInt() and 255).toString(16).padStart(2, '0') }", "",
              "    private fun decode(encoded: String, expected: String): ByteArray = Base64.decode(encoded, Base64.NO_WRAP).also {",
              "        check(it.isNotEmpty() && it.size <= 65536 && sha256(it) == expected)", "    }"]
    for name, data in payloads.items():
        chunks = textwrap.wrap(base64.b64encode(data).decode(), 112)
        lines += ["", f'    private const val {name}Base64 =']
        lines += ['        "' + chunk + ('" +' if index < len(chunks) - 1 else '"') for index, chunk in enumerate(chunks)]
    lines.append("}")
    write_new(destination / "LegacyCodecFixtures.kt", ("\n".join(lines) + "\n").encode())
    manifest = dict(ffmpeg=version.splitlines()[0], ffprobe=probe_version.splitlines()[0], commands=commands, fixtures=report,
                    control_wave_sha256=sha(control), bitexact_repeat_verified=True)
    write_new(destination / "manifest.json", (json.dumps(manifest, indent=2) + "\n").encode())
    for name, data in immutable.items():
        assert (AMR / name).read_bytes() == data, "Read-only P01 fixture changed"
    print(json.dumps(dict(destination=str(destination), **manifest), indent=2))


if __name__ == "__main__":
    main()
