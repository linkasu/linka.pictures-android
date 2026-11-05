#!/usr/bin/env python3
"""Pinned development bridge build + optional owned-simulator proof. No product wiring."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import runpy
import shutil
import struct
import wave

PREFLIGHT = runpy.run_path(str(Path(__file__).with_name("preflight-ios-ffmpeg.py")))
Preflight = PREFLIGHT["Preflight"]
require = PREFLIGHT["require"]
sha = PREFLIGHT["sha"]
UDID = PREFLIGHT["UDID"]
SOURCE_SHA = "464beb5e7bf0c311e68b45ae2f04e9cc2af88851abb4082231742a74d97b524c"
FLAGS = [x for x in PREFLIGHT["FLAGS"] if not x.startswith(("--enable-ffmpeg", "--enable-avfilter", "--enable-filter=", "--disable-filter=", "--enable-muxer=", "--enable-encoder="))]
FLAGS += ["--disable-avfilter", "--disable-pthreads", "--disable-w32threads", "--disable-os2threads"]


def target(task, sdk, triple, cached=None):
    build = task.root / sdk
    build.mkdir()
    prefix = build / "install"
    _, sysroot = task.run(["xcrun", "--sdk", sdk, "--show-sdk-path"])
    tools = {}
    for tool in ("clang", "ar", "ranlib", "nm"):
        _, text = task.run(["xcrun", "--sdk", sdk, "--find", tool])
        tools[tool] = text.strip()
    flags = FLAGS + [f"--prefix={prefix}", f"--cc={tools['clang']}", f"--ar={tools['ar']}",
        f"--ranlib={tools['ranlib']}", f"--nm={tools['nm']}", "--pkg-config=/usr/bin/false",
        f"--sysroot={sysroot.strip()}", f"--extra-cflags=-target {triple}", f"--extra-ldflags=-target {triple}"]
    task.result["stage"] = f"development-build-{sdk}"
    if cached:
        previous = cached['development_targets'][sdk]
        old = Path(previous['prefix'])
        require(previous['config_sha256'] == sha(old.parent / 'config.h') and
                previous['components_sha256'] == sha(old.parent / 'config_components.h'), 'cached configuration changed')
        prefix.mkdir()
        (prefix / 'lib').mkdir()
        (prefix / 'include').mkdir()
        for name in ('avcodec', 'avformat', 'avutil', 'swresample'):
            lib = old / f'lib/lib{name}.a'
            require(sha(lib) == previous['libraries'][lib.name]['sha256'], 'cached FFmpeg library changed')
            shutil.copyfile(lib, prefix / 'lib' / lib.name)
            shutil.copytree(old / 'include' / f'lib{name}', prefix / 'include' / f'lib{name}')
        (build / 'ffbuild').mkdir()
        for name in ('config.h', 'config_components.h', 'ffbuild/config.mak'):
            shutil.copyfile(old.parent / name, build / name)
        flags = previous['configure']
    else:
        task.run([task.source / "configure", *flags], cwd=build, timeout=180)
    config = (build / "config.h").read_text()
    components = (build / "config_components.h").read_text()
    for name in ("GPL", "NONFREE", "VERSION3", "NETWORK", "FFMPEG", "AVFILTER", "LIBVORBIS", "LIBMP3LAME", "LIBX264"):
        require(re.search(rf"^#define CONFIG_{name} 0$", config, re.M), f"forbidden config: {name}")
    require(re.search(r"^#define HAVE_PTHREADS 0$", config, re.M), "FFmpeg adaptive pthreads must be disabled")
    enabled = {}
    expected = dict(DECODER={"VORBIS", "AMRNB", "PCM_S16LE", "MP3"}, DEMUXER={"OGG", "AMR", "MOV", "WAV", "MP3"},
        PROTOCOL={"FILE"}, ENCODER=set(), MUXER=set(), FILTER=set(), HWACCEL=set())
    for kind, values in expected.items():
        enabled[kind] = re.findall(rf"^#define CONFIG_(\w+)_{kind} 1$", components, re.M)
        require(set(enabled[kind]) == values, f"unexpected {kind}: {enabled[kind]}")
    if not cached:
        task.run(["make", "-j4", "install-libs", "install-headers"], cwd=build, timeout=600)
    header = task.project / "shared/src/nativeInterop/cinterop/include/linka_audio_decoder.h"
    source = task.project / "shared/src/nativeInterop/c/linka_audio_decoder.c"
    obj = build / "linka_audio_decoder.o"
    compile_flags = [tools["clang"], "-target", triple, "-isysroot", sysroot.strip(), "-std=c17", "-O2",
        "-Wall", "-Wextra", "-Werror", "-Werror=partial-availability", "-I", prefix / "include", "-I", header.parent]
    task.run([*compile_flags, "-c", source, "-o", obj], timeout=120)
    bridge = prefix / "lib/liblinka_audio_decoder.a"
    task.run([tools["ar"], "rcs", bridge, obj])
    with (prefix / "include/linka_audio_decoder.h").open("xb") as output:
        output.write(header.read_bytes())
    platform = {"7", "IOSSIMULATOR"} if sdk == "iphonesimulator" else {"2", "IOS"}
    libraries = {p.name: task.macho(p, platform) for p in sorted((prefix / "lib").glob("*.a"))}
    require(set(libraries) == {"liblinka_audio_decoder.a", "libavformat.a", "libavcodec.a", "libswresample.a", "libavutil.a"}, "unexpected libraries")
    task.result.setdefault("development_targets", {})[sdk] = dict(prefix=str(prefix), configure=flags,
        config_sha256=sha(build / "config.h"), components_sha256=sha(build / "config_components.h"),
        make_config_sha256=sha(build / "ffbuild/config.mak"), enabled=enabled, libraries=libraries,
        bridge_source_sha256=sha(source), bridge_header_sha256=sha(header))
    if cached:
        task.result['development_targets'][sdk]['ffmpeg_reused_from'] = previous['prefix']
    task.save()
    executable = build / "bridge-test"
    if sdk == "iphonesimulator":
        task.run([*compile_flags, task.project / "scripts/test-ios-audio-decoder.c", "-L", prefix / "lib",
            "-llinka_audio_decoder", "-lavformat", "-lavcodec", "-lswresample", "-lavutil", "-lm", "-pthread",
            "-framework", "CoreFoundation", "-framework", "CoreVideo", "-framework", "CoreMedia", "-o", executable], timeout=120)
        task.result["bridge_test_binary"] = task.macho(executable, platform)
    return prefix, executable


def negative_wave(path, channels=1, rate=16000, seconds=0.2, width=2):
    count = int(seconds * rate)
    require(count * channels * width < 1024 * 1024, "test fixture cap")
    with path.open("xb") as file:
        with wave.open(file, "wb") as output:
            output.setparams((channels, width, rate, count, "NONE", "not compressed"))
            output.writeframes(bytes(count * channels * width))


def atom(kind, payload, extended=False, size0=False):
    if size0:
        return struct.pack('>I4s', 0, kind) + payload
    return (struct.pack('>I4sQ', 1, kind, len(payload) + 16) if extended else struct.pack('>I4s', len(payload) + 8, kind)) + payload


def reference_entries(data, entries, version=0, count=None):
    # Owned negative-fixture MP4 atom transform, not a product media parser.
    containers = {b"moov", b"trak", b"mdia", b"minf", b"dinf"}
    output = b""
    offset = 0
    while offset < len(data):
        size, kind = struct.unpack_from(">I4s", data, offset)
        require(8 <= size <= len(data) - offset, "fixture MP4 size")
        payload = data[offset + 8:offset + size]
        if kind in containers:
            payload = reference_entries(payload, entries, version, count)
        elif kind == b"dref":
            payload = struct.pack('>II', version << 24, len(entries) if count is None else count) + b''.join(entries)
        output += struct.pack(">I4s", len(payload) + 8, kind) + payload
        offset += size
    return output


def external_reference(data, reference):
    return reference_entries(data, [atom(b'url ', bytes(4) + reference.encode() + b'\0')])


def moov_header(data, extended=False, size0=False):
    # Only own small genuine fixture; moov follows mdat, so sample offsets do not move.
    position = 0
    output = b''
    while position < len(data):
        size, kind = struct.unpack_from('>I4s', data, position)
        require(8 <= size <= len(data) - position, 'fixture atom bounds')
        body = data[position + 8:position + size]
        if kind == b'moov':
            require(position + size == len(data), 'fixture moov must be last')
            output += atom(kind, body, extended, size0)
        else:
            output += data[position:position + size]
        position += size
    return output


def proof(task, executable, fixtures):
    task.runtime()
    base = ["xcrun", "simctl", "spawn", UDID, executable]
    _, info = task.run([*base, "info"])
    require("version=8.1.2\n" in info and "license=LGPL version 2.1 or later" in info, "native bridge license/version")
    task.result["development_native_info"] = info
    output = task.root / "bridge-output"
    output.mkdir()

    def invoke(mode, source, destination=None):
        task.runtime()  # Owner check immediately before each native execution.
        args = [*base, mode, source] + ([destination] if destination else [])
        status, text = task.run(args, timeout=35, success=False)
        records = [json.loads(line) for line in text.splitlines() if line.startswith('{"status":')]
        require(len(records) == 1, f"missing structured native result: {text}")
        require(status == records[0]["status"], "native exit/status mismatch")
        return records[0]

    matrix = {}
    for name, source in fixtures.items():
        inspection = invoke("inspect", source)
        require(inspection["status"] == 0 and inspection["frames"] > 0, f"inspect failed {name}")
        destination = output / f"{name}.wav"
        decoded = invoke("wave", source, destination)
        require(decoded["status"] == 0, f"WAV failed {name}")
        require(destination.stat().st_size <= 1024 * 1024, "tiny fixture output cap")
        with wave.open(str(destination)) as wav:
            require((wav.getnchannels(), wav.getsampwidth(), wav.getframerate()) == (2, 2, 16000), "compatibility WAV format")
            frames = wav.getnframes()
            samples = struct.unpack('<' + 'h' * (frames * 2), wav.readframes(frames))
            rms = (sum((x / 32768) ** 2 for x in samples) / len(samples)) ** 0.5
            require(frames == decoded["frames"] == inspection["frames"] and 0.1 <= frames / 16000 <= 0.5 and rms > 0, "WAV inspection/energy mismatch")
        matrix[name] = dict(inspect=inspection, decoded=decoded, wave_bytes=destination.stat().st_size, wave_sha256=sha(destination), rms=rms)
    task.result["development_matrix"] = matrix
    silence = output / 'silence.wav'
    negative_wave(silence, channels=2)
    silent = invoke('inspect', silence)
    require(silent['status'] == 0 and silent['frames'] == 3200, 'finite silence must remain valid')
    task.result['development_silence'] = silent
    negative = {}
    truncated = output / "truncated.ogg"
    truncated.write_bytes(fixtures["vorbis"].read_bytes()[:64])
    cases = [("truncated", truncated, {2})]
    for name, kwargs, expected in (("channels", dict(channels=9), {3}), ("rate", dict(rate=192001), {3}),
                                  ("duration", dict(rate=1, seconds=601), {3}), ("unsupported", dict(width=1), {1})):
        path = output / f"{name}.wav"
        negative_wave(path, **kwargs)
        cases.append((name, path, expected))
    oversized = output / "oversized.bin"
    with oversized.open("xb") as file:
        file.truncate(64 * 1024 * 1024 + 1)
    cases.append(("source-cap", oversized, {3}))
    # Duplicate the OWN AMR audio stream without decoding/re-encoding any source.
    multitrack = output / "multitrack.3gp"
    task.run(["/opt/homebrew/bin/ffmpeg", "-hide_banner", "-loglevel", "error", "-nostdin", "-n", "-i", fixtures["threeGp"],
              "-map", "0:a:0", "-map", "0:a:0", "-c", "copy", multitrack])
    cases.append(("multitrack", multitrack, {1}))
    reference = output / "private-reference-sentinel"
    reference.write_bytes(b"never read this external payload")
    for name, url in (("external-file", str(reference)), ("external-http", "https://127.0.0.1:1/forbidden")):
        path = output / f"{name}.3gp"
        path.write_bytes(external_reference(fixtures["threeGp"].read_bytes(), url))
        cases.append((name, path, {1, 2}))
    genuine = fixtures['threeGp'].read_bytes()
    self_url = atom(b'url ', struct.pack('>I', 1))
    for name, payload, expected in (
        ('urn', reference_entries(genuine, [atom(b'urn ', bytes(4) + b'name\0https://invalid/\0')]), {1}),
        ('alias', reference_entries(genuine, [atom(b'alis', bytes(200))]), {1}),
        ('unknown-ref', reference_entries(genuine, [atom(b'zzzz', bytes(4))]), {1}),
        ('second-external', reference_entries(genuine, [self_url, atom(b'url ', bytes(4) + b'https://invalid/\0')]), {1}),
        ('self-location', reference_entries(genuine, [atom(b'url ', struct.pack('>I', 1) + b'location\0')]), {1}),
        ('dref-version', reference_entries(genuine, [self_url], version=1), {2}),
        ('dref-empty', reference_entries(genuine, []), {2}),
        ('dref-truncated', reference_entries(genuine, [self_url], count=2), {2}),
        ('dref-count', reference_entries(genuine, [self_url], count=4097), {3}),
        ('rmra', atom(b'moov', atom(b'rmra', b'')), {1}),
        ('extended-overflow', struct.pack('>I4sQ', 1, b'moov', 0xffffffffffffffff), {2}),
        ('extended-truncated', struct.pack('>I4s', 1, b'moov') + bytes(4), {2}),
        ('truncated-box', struct.pack('>I4s', 100, b'moov'), {2}),
        ('atom-count', atom(b'free', b'') * 4097, {3}),
    ):
        path = output / f'{name}.3gp'
        path.write_bytes(payload)
        cases.append((name, path, expected))
    nested = b''
    for _ in range(10):
        nested = atom(b'moov', nested)
    path = output / 'depth.3gp'
    path.write_bytes(nested)
    cases.append(('depth', path, {3}))
    path = output / 'oversized-moov.3gp'
    with path.open('xb') as file:
        file.write(struct.pack('>I4s', 8 * 1024 * 1024 + 1, b'moov'))
        file.truncate(8 * 1024 * 1024 + 1)
    cases.append(('oversized-moov', path, {3}))
    for name, source, expected in cases:
        destination = output / f"negative-{name}.wav"
        result = invoke("wave", source, destination)
        require(result["status"] in expected and not destination.exists(), f"negative failure/cleanup {name}: {result}")
        if name.startswith("external"):
            require(result["status"] == 1 and result['format'] == 0 and result['frames'] == 0,
                    'external reference must fail before FFmpeg open, not be silently ignored')
        negative[name] = result
    opaque = atom(b'moov', atom(b'rmra', b'')) + atom(b'dref', bytes(8)) + b'https://invalid/'
    positives = {
        'extended-moov': moov_header(genuine, extended=True),
        'size0-moov': moov_header(genuine, size0=True),
        'opaque-mdat': genuine + atom(b'mdat', opaque),
        'opaque-size0-mdat': genuine + atom(b'mdat', opaque, size0=True),
    }
    positive_records = {}
    for name, payload in positives.items():
        path = output / f'{name}.3gp'
        path.write_bytes(payload)
        destination = output / f'{name}.wav'
        result = invoke('wave', path, destination)
        require(result['status'] == 0 and sha(destination) == matrix['threeGp']['wave_sha256'], f'valid/opaque BMFF changed decode: {name}')
        positive_records[name] = result
    task.result['bmff_positive'] = positive_records
    destination = output / "cancelled.wav"
    cancelled = invoke("cancel", fixtures["vorbis"], destination)
    require(cancelled["status"] == 5 and not destination.exists(), "per-request cancellation cleanup")
    sentinel = output / "foreign.wav"
    sentinel.write_bytes(b"foreign nonempty output")
    prior = sha(sentinel)
    foreign = invoke("foreign", fixtures["vorbis"], sentinel)
    require(foreign["status"] == 4 and sha(sentinel) == prior, "foreign output changed")
    require(reference.read_bytes() == b"never read this external payload", "external source changed")
    task.result.update(development_negative=negative, development_cancellation=cancelled, development_foreign_output=foreign)
    task.save()


def scoped_kotlin(task, prefix):
    # Independent generated project: real selected sources, no tracked root wiring or skip flags.
    definition = task.root / 'linka_audio_decoder.def'
    definition.write_text(f'''headers = linka_audio_decoder.h
headerFilter = linka_audio_decoder.h
package = su.linka.pictures.platform.audio.decoder
compilerOpts = -I{prefix}/include
staticLibraries = liblinka_audio_decoder.a libavformat.a libavcodec.a libswresample.a libavutil.a
libraryPaths = {prefix}/lib
linkerOpts = -lm -pthread -framework CoreFoundation -framework CoreVideo -framework CoreMedia
''')
    project = task.root / 'kotlin-proof'
    project.mkdir()
    common = ['application/OperationResult.kt', 'application/ports/LocalFile.kt',
              'application/ports/PlatformFilesPort.kt', 'application/ports/MediaInspectionPort.kt', 'text/NativeText.kt']
    sources = {}
    for relative in common:
        source = task.project / 'shared/src/commonMain/kotlin/su/linka/pictures' / relative
        output = project / 'src/commonMain/kotlin/su/linka/pictures' / relative
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_bytes(source.read_bytes())
        sources[str(source)] = sha(source)
    source = task.project / 'shared/src/iosMain/kotlin/su/linka/pictures/platform/audio/IosAudioDecoder.kt'
    output = project / 'src/iosMain/kotlin/su/linka/pictures/platform/audio/IosAudioDecoder.kt'
    output.parent.mkdir(parents=True)
    output.write_bytes(source.read_bytes())
    sources[str(source)] = sha(source)
    (project / 'settings.gradle').write_text("pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }\nrootProject.name='ios-audio-decoder-proof'\n")
    (project / 'build.gradle').write_text(f'''plugins {{ id 'org.jetbrains.kotlin.multiplatform' version '2.2.21' }}
repositories {{ google(); mavenCentral() }}
kotlin {{
    iosSimulatorArm64()
    sourceSets {{ commonMain {{ dependencies {{
        implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2'
        implementation 'com.squareup.okio:okio:3.10.2'
    }} }} }}
    targets.getByName('iosSimulatorArm64').compilations.getByName('main').cinterops.create('linkaAudioDecoder') {{
        definitionFile.set(file('{definition}'))
    }}
}}
''')
    task.env.update(JAVA_HOME='/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home',
                    ANDROID_HOME='/Volumes/data/developer-state/android-sdk')
    task.run([task.project / 'gradlew', '-p', project, '--project-cache-dir', task.root / 'gradle-cache',
              'compileKotlinIosSimulatorArm64', '--console=plain'],
             cwd=task.project, timeout=300)
    task.result['scoped_kotlin_compile'] = dict(status='PASS', definition=str(definition), project=str(project), sources=sources,
        actual_root_binding='PENDING; independent generated cinterop compile proof only')
    task.save()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--verified-source-run')
    parser.add_argument('--reuse-build-run', help='owned completed development UUID; reverify source/libs, rebuild only bridge')
    parser.add_argument('--prove', action='store_true', help='run only the owned iOS17 simulator; never automatic on build')
    parser.add_argument('--compile-kotlin', action='store_true', help='independent generated cinterop compile proof, not root integration')
    args = parser.parse_args()
    PREFLIGHT["self_test"]()
    task = Preflight()
    print(task.root, flush=True)
    try:
        _, xcode = task.run(["xcodebuild", "-version"])
        require(xcode.startswith("Xcode 27.2\n"), "Xcode version")
        cached = None
        if args.reuse_build_run:
            import uuid
            require(str(uuid.UUID(args.reuse_build_run)) == args.reuse_build_run, 'invalid owned cache UUID')
            cached = json.loads((task.root.parent / args.reuse_build_run / 'result.json').read_text())
            archive_record = next(x for x in cached['downloads'] if x['url'] == PREFLIGHT['SOURCE'])
            archive = Path(archive_record['path'])
            require(archive.resolve().is_relative_to(task.root.parent.resolve()) and sha(archive) == SOURCE_SHA, 'source cache changed/outside owner')
            _, signature = task.run(['gpgv', '--homedir', archive.parent / 'gnupg', '--keyring', archive.parent / 'release-key.gpg',
                                    '--status-fd', '1', archive.parent / 'source.asc', archive])
            require(f"[GNUPG:] VALIDSIG {PREFLIGHT['FINGERPRINT']} " in signature, 'cached source signature')
            task.result.update(downloads=cached['downloads'], signature=cached['signature'], source_inspection=cached['source_inspection'],
                               development_cache=args.reuse_build_run)
        else:
            require(args.verified_source_run, 'provide verified source or completed build cache')
            task.reuse_source(args.verified_source_run)
        require(next(x['sha256'] for x in task.result['downloads'] if x['url'] == PREFLIGHT['SOURCE']) == SOURCE_SHA, "pinned release SHA")
        prefix, executable = target(task, 'iphonesimulator', 'arm64-apple-ios17.0-simulator', cached)
        if args.prove:
            fixtures = task.fixtures()
            proof(task, executable, fixtures)
        if args.compile_kotlin:
            scoped_kotlin(task, prefix)
        target(task, 'iphoneos', 'arm64-apple-ios17.0', cached)
        task.result['status'] = 'DEVELOPMENT_PROOF_PASS' if args.prove else 'BUILD_ONLY'
    except Exception as error:
        task.result.update(status='STOP', error=str(error))
        raise
    finally:
        changed = [str(p) for p, digest in task.originals.items() if sha(p) != digest]
        task.result['original_fixture_hashes_unchanged'] = not changed
        task.result['original_fixture_files_verified'] = len(task.originals)
        task.save()
        require(not changed, 'source fixture changed')


if __name__ == '__main__':
    main()
