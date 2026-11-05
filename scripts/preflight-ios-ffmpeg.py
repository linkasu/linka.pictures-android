#!/usr/bin/env python3
"""Isolated FFmpeg 8.1.2 capability experiment. Not an application dependency/build."""

import argparse
import array
import hashlib
import json
import math
import os
from pathlib import Path, PurePosixPath
import re
import runpy
import signal
import shlex
import struct
import subprocess
import sys
import tarfile
import time
import uuid
import wave

VERSION = "8.1.2"
SOURCE = f"https://ffmpeg.org/releases/ffmpeg-{VERSION}.tar.xz"
KEY = "https://ffmpeg.org/ffmpeg-devel.asc"
VERIFICATION = "https://ffmpeg.org/download.html"
FINGERPRINT = "FCF986EA15E6E293A5644F10B4322F04D67658D8"
DEVELOPER = "/Applications/Xcode.app/Contents/Developer"
UDID = "DD0F9A3F-DC32-4373-BA08-400FECE3A1CC"
TEMP = Path("/private/var/folders/l4/r3plyjwj7pz_nq7zz50x04880000gn/T/opencode")
FIXTURES = TEMP / "p06-legacy-codecs-48acb16b-88d6-49cf-bae0-a1f5bbd35050"
AMR = TEMP / "p01-amr-fixtures"
EXPECTED = {
    "vorbis": (FIXTURES / "vorbis-first.ogg", 5063, "98305ce0d4bf14c7189c25835e2f87b8a602b462232a4e8e285fae0480cf7208"),
    "mp3": (FIXTURES / "mp3-first.mp3", 2612, "796a8d642760fd8bb1e4e216b5ab2ef02e73dd633bf3a27bfcce6e9450c9b026"),
    "amr": (AMR / "synthetic-440hz.amr", 358, "94f6facdcb17482cba9590b2f83068cc61c85a8adb0270032557e7487c125934"),
    "threeGp": (AMR / "synthetic-440hz-amr.3gp", 962, "f81c04f29cffcba93be208c36cfc0e2df9196c3fb2e097d5e7234a17ed72426c"),
    "wav": (FIXTURES / "tone.wav", 12844, "f408f45e38a040910c40ac3851fafa8ed5248d04fc074d841b8ecbe0b93f2e51"),
}
FLAGS = [
    "--enable-cross-compile", "--target-os=darwin", "--arch=aarch64",
    "--disable-autodetect", "--disable-everything", "--disable-gpl", "--disable-nonfree",
    "--disable-version3", "--disable-network", "--disable-devices", "--disable-hwaccels",
    "--disable-audiotoolbox", "--disable-videotoolbox", "--disable-securetransport",
    "--disable-zlib", "--disable-bzlib", "--disable-lzma", "--disable-iconv",
    "--disable-xlib", "--disable-libxcb", "--disable-avdevice", "--disable-swscale",
    "--disable-doc", "--disable-debug", "--enable-small", "--enable-static", "--disable-shared",
    "--disable-programs", "--enable-ffmpeg", "--enable-protocol=file",
    "--enable-demuxer=ogg,amr,mov,wav,mp3", "--enable-decoder=vorbis,amrnb,pcm_s16le,mp3",
    "--enable-muxer=pcm_f32le,wav", "--enable-encoder=pcm_f32le,pcm_s16le",
    "--enable-swresample", "--enable-avfilter",
    "--enable-filter=abuffer,abuffersink,aresample,aformat,anull",
    "--disable-filter=atrim,crop,format,hflip,null,rotate,transpose,trim,vflip",
]


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def sha(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def pcm_stats(data):
    require(0 < len(data) <= 1024 * 1024 and len(data) % 8 == 0, "PCM size/alignment")
    samples = [x[0] for x in struct.iter_unpack("<f", data)]
    require(all(math.isfinite(x) for x in samples), "nonfinite PCM")
    frames = len(data) // 8
    duration = frames / 16000
    rms = math.sqrt(sum(x * x for x in samples) / len(samples))
    require(0.1 <= duration <= 0.5 and rms > 1e-6, "PCM duration/energy")
    return dict(bytes=len(data), frames=frames, seconds=duration, rms=rms,
                sha256=hashlib.sha256(data).hexdigest())


def safe_member(member):
    path = PurePosixPath(member.name)
    require(not path.is_absolute() and ".." not in path.parts, "unsafe tar path")
    require(path.parts and path.parts[0] == f"ffmpeg-{VERSION}", "unexpected source root")
    require(member.isfile() or member.isdir() or member.issym() or member.islnk(), "special tar member")


def self_test():
    assert __debug__, "assert self-checks require non-optimized Python"
    assert len(FINGERPRINT) == 40 and len(EXPECTED) == 5
    sample = struct.pack("<f", 0.125) * 6400
    assert pcm_stats(sample)["frames"] == 3200
    for data in (b"", b"short", struct.pack("<f", float("nan")) * 6400):
        try:
            pcm_stats(data)
        except RuntimeError:
            pass
        else:
            raise AssertionError("bad PCM accepted")
    for name in ("/escape", "../escape", f"ffmpeg-{VERSION}/../escape", "another/file"):
        member = tarfile.TarInfo(name)
        try:
            safe_member(member)
        except RuntimeError:
            pass
        else:
            raise AssertionError("bad source path accepted")
    link = tarfile.TarInfo(f"ffmpeg-{VERSION}/escape")
    link.type, link.linkname = tarfile.SYMTYPE, "../../outside"
    try:
        tarfile.data_filter(link, Path(__file__).resolve().parents[1] / "build/ios-ffmpeg-preflight/self-check-anchor")
    except tarfile.FilterError:
        pass
    else:
        raise AssertionError("symlink escape accepted")
    assert not any("--enable-gpl" == x or "--enable-nonfree" == x or "-Wno" in x for x in FLAGS)


class Preflight:
    def __init__(self):
        self.project = Path(__file__).resolve().parents[1]
        parent = self.project / "build" / "ios-ffmpeg-preflight"
        ignored = subprocess.run(["git", "check-ignore", str(parent / "probe")], cwd=self.project,
                                 capture_output=True, timeout=10)
        require(ignored.returncode == 0, "build outputs must already be ignored")
        parent.mkdir(parents=True, exist_ok=True)
        self.root = parent / str(uuid.uuid4())
        self.root.mkdir()
        (self.root / "logs").mkdir()
        self.env = dict(os.environ, DEVELOPER_DIR=DEVELOPER)
        for key in ("CFLAGS", "CXXFLAGS", "CPPFLAGS", "LDFLAGS", "LIBRARY_PATH", "CPATH", "PKG_CONFIG_PATH"):
            self.env.pop(key, None)
        self.result = dict(version=VERSION, root=str(self.root), status="RUNNING", commands=[], downloads=[])
        self.originals = {}
        self.started = time.monotonic()

    def save(self):
        self.result["elapsed_seconds"] = time.monotonic() - self.started
        (self.root / "result.json").write_text(json.dumps(self.result, indent=2) + "\n")

    def run(self, command, cwd=None, timeout=30, success=True):
        record = dict(command=list(map(str, command)), cwd=str(cwd or self.root), timeout=timeout)
        log = self.root / "logs" / f"{len(self.result['commands']):03d}.log"
        record["log"] = str(log)
        self.result["commands"].append(record)
        begin = time.monotonic()
        with log.open("xb") as output:
            process = subprocess.Popen(record["command"], cwd=record["cwd"], env=self.env,
                                       stdin=subprocess.DEVNULL, stdout=output, stderr=subprocess.STDOUT,
                                       start_new_session=True)
            try:
                record["returncode"] = process.wait(timeout=timeout)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)  # Only this command's freshly owned process group.
                process.wait()
                record["timed_out"] = True
                raise RuntimeError(f"process deadline: {record['command']}; log={log}")
            finally:
                record["seconds"] = time.monotonic() - begin
                self.save()
        require(log.stat().st_size <= 16 * 1024 * 1024, f"oversized command log: {log}")
        text = log.read_text(errors="replace")
        if success:
            require(record["returncode"] == 0, f"command failed: {record['command']}; log={log}\n{text[-6000:]}")
        return record["returncode"], text

    def download(self, url, name, limit, optional=False):
        record = dict(url=url)
        self.result["downloads"].append(record)
        path = self.root / name
        headers = self.root / f"{name}.headers"
        require(url in (SOURCE, SOURCE + ".asc", SOURCE + ".sha256", VERIFICATION, KEY), "unapproved download URL")
        require(not path.exists() and not headers.exists(), "download destination already exists")
        try:
            status, text = self.run(["/usr/bin/curl", "--disable", "--silent", "--show-error", "--fail",
                "--proto", "=https", "--connect-timeout", "15", "--max-time", "90", "--max-filesize", str(limit),
                "--dump-header", headers, "--output", path, "--write-out", "\n%{http_code}\n", url], timeout=100, success=False)
            codes = re.findall(r"^([0-9]{3})$", text, re.M)
            record.update(status=int(codes[-1]) if codes else None, transport="system curl, TLS verification enabled, no redirects",
                          headers=headers.read_text() if headers.exists() else "")
            if optional and record["status"] == 404:
                return None
            require(status == 0 and record["status"] == 200, f"source HTTP failure: {url}; {text}")
            require(path.is_file() and 0 < path.stat().st_size <= limit, "download byte cap")
            record.update(bytes=path.stat().st_size, sha256=sha(path), path=str(path))
            return path
        finally:
            self.save()

    def intake(self):
        self.result["stage"] = "source-authentication"
        archive = self.download(SOURCE, f"ffmpeg-{VERSION}.tar.xz", 50 * 1024 * 1024)
        signature = self.download(SOURCE + ".asc", "source.asc", 65536)
        checksum = self.download(SOURCE + ".sha256", "source.sha256", 65536, optional=True)
        if checksum:
            require(re.search(r"\b" + sha(archive) + r"\b", checksum.read_text()), "published checksum mismatch")
        page = self.download(VERIFICATION, "release-verification.html", 1024 * 1024)
        require(FINGERPRINT in page.read_text(), "official release-key fingerprint changed/unknown")
        key = self.download(KEY, "release-key.asc", 65536)
        home = self.root / "gnupg"
        home.mkdir(mode=0o700)
        keyring = self.root / "release-key.gpg"
        self.run(["gpg", "--no-options", "--homedir", home, "--batch", "--no-autostart",
                  "--dearmor", "--output", keyring, key])
        _, verification = self.run(["gpgv", "--homedir", home, "--keyring", keyring,
                                    "--status-fd", "1", signature, archive])
        require(f"[GNUPG:] VALIDSIG {FINGERPRINT} " in verification, "unknown/invalid release signature")
        self.result["signature"] = dict(fingerprint=FINGERPRINT, verified=True,
                                        key_url=KEY, fingerprint_source=VERIFICATION)
        self.inspect_extract(archive)
        return archive

    def reuse_source(self, identifier):
        require(str(uuid.UUID(identifier)) == identifier, "source run must be an owned UUID directory")
        previous = self.root.parent / identifier
        report = json.loads((previous / "result.json").read_text())
        require(report["signature"]["fingerprint"] == FINGERPRINT and report["signature"]["verified"], "unverified source cache")
        archive = previous / f"ffmpeg-{VERSION}.tar.xz"
        expected = next(item["sha256"] for item in report["downloads"] if item["url"] == SOURCE)
        require(archive.stat().st_size <= 50 * 1024 * 1024 and sha(archive) == expected, "source cache changed")
        _, verification = self.run(["gpgv", "--homedir", previous / "gnupg", "--keyring", previous / "release-key.gpg",
                                    "--status-fd", "1", previous / "source.asc", archive])
        require(f"[GNUPG:] VALIDSIG {FINGERPRINT} " in verification, "source cache signature failed")
        self.result.update(downloads=report["downloads"], signature=report["signature"], source_reused_from=str(previous))
        self.inspect_extract(archive)

    def inspect_extract(self, archive):
        self.result["stage"] = "source-license-inspection"
        names = ["LICENSE.md", "COPYING.LGPLv2.1", "libavcodec/vorbisdec.c",
                 "libavcodec/amrnbdec.c", "libavformat/amr.c", "libavformat/mov.c"]
        inspection = {}
        with tarfile.open(archive, "r:xz") as source:
            for name in names:
                file = source.extractfile(f"ffmpeg-{VERSION}/{name}")
                require(file is not None, f"missing license/source file {name}")
                data = file.read(256 * 1024 + 1 if name in names[:2] else 32768)
                if name in names[:2]:
                    require(len(data) <= 256 * 1024, f"inspection size cap {name}")
                else:
                    require(b"*/" in data, f"missing license header: {name}")
                    data = data.split(b"*/", 1)[0] + b"*/"
                header = data.decode("utf-8")
                require("Lesser General Public License" in header or "LGPL" in header, f"unknown license: {name}")
                inspection[name] = dict(inspected_bytes=len(data), sha256=hashlib.sha256(data).hexdigest(), notice=header)
            configure = source.extractfile(f"ffmpeg-{VERSION}/configure").read().decode()
            inspection["decoder_dependencies"] = [line for line in configure.splitlines()
                if re.match(r"(?:vorbis|amrnb)_decoder_(?:deps|select)=", line)]
            inspection["ffmpeg_cli_select"] = re.search(r'^ffmpeg_select="([^"]*)"', configure, re.M).group(1).split()
            self.result["source_inspection"] = inspection
            self.save()
            extracted = self.root / "source"
            extracted.mkdir()
            members = source.getmembers()
            require(len(members) <= 50000 and sum(x.size for x in members) <= 512 * 1024 * 1024, "source extraction caps")
            for member in members:
                safe_member(member)
                tarfile.data_filter(member, extracted)  # Absolute/traversal/special/link escape checks.
            source.extractall(extracted, members=members, filter="data")
        self.source = extracted / f"ffmpeg-{VERSION}"
        require((self.source / "VERSION").read_text().strip() == VERSION, "release VERSION mismatch")

    def fixtures(self):
        manifest = json.loads((FIXTURES / "manifest.json").read_text())
        generator = self.project / "shared/src/androidInstrumentedTest/kotlin/su/linka/pictures/platform/capabilities/generate_legacy_codec_fixtures.py"
        functions = runpy.run_path(str(generator))  # Only definitions; never its host-encoding main().
        samples = array.array("h")
        for frame in range(3200):
            samples.extend(int(6000 * math.sin(2 * math.pi * frequency * frame / 16000)) for frequency in (440, 660))
        require(hashlib.sha256(functions["wav_bytes"](2, samples)).hexdigest() == EXPECTED["wav"][2], "own sine generator mismatch")
        control = functions["wav_bytes"](1, array.array("h", (100 if n % 2 == 0 else -100 for n in range(1600))))
        require(hashlib.sha256(control).hexdigest() == manifest["control_wave_sha256"], "independent square-wave control mismatch")
        self.result["wave_generator"] = dict(path=str(generator), sha256=sha(generator),
            note="tone.wav is stereo sine; manifest control_wave_sha256 is a DIFFERENT mono square-wave control")
        self.result["fixture_producers"] = {k: manifest[k] for k in ("ffmpeg", "ffprobe", "commands")}
        manifest_records = {item["name"]: item for item in manifest["fixtures"]}
        paths = {item for command in manifest["commands"] for item in command if item.startswith(str(TEMP) + "/")}
        target = self.root / "fixtures"
        target.mkdir()
        copied = {}
        for name, (path, size, digest) in EXPECTED.items():
            require(path.is_file() and not path.is_symlink(), f"missing/not-owned fixture: {path}")
            require(path.resolve().is_relative_to(TEMP.resolve()) and str(path) in paths, "fixture not from approved manifest")
            require(path.stat().st_size <= 65536 and (size is None or path.stat().st_size == size), "fixture size mismatch")
            require(sha(path) == digest, f"fixture SHA mismatch: {name}")
            if name != "wav":
                require(manifest_records[name]["sha256"] == digest, "manifest fixture mismatch")
            else:
                with wave.open(str(path)) as wav:
                    require((wav.getnchannels(), wav.getsampwidth(), wav.getframerate(), wav.getnframes()) == (2, 2, 16000, 3200), "WAV generator parameters")
            self.originals[path] = digest
            output = target / path.name
            with output.open("xb") as destination:
                destination.write(path.read_bytes())
            require(sha(output) == digest, "fixture copy mismatch")
            copied[name] = output
        self.result["fixtures"] = {k: dict(source=str(EXPECTED[k][0]), bytes=v.stat().st_size, sha256=sha(v)) for k, v in copied.items()}
        return copied

    def runtime(self):
        _, text = self.run(["xcrun", "simctl", "list", "devices", "--json"])
        devices = [dict(item, runtime_id=key) for key, values in json.loads(text)["devices"].items()
                   for item in values if item["udid"] == UDID]
        require(len(devices) == 1 and devices[0]["name"] == "linka_platform_probe_ios17"
                and devices[0]["state"] == "Booted" and devices[0]["isAvailable"], "owned simulator not booted/recognized")
        _, text = self.run(["xcrun", "simctl", "list", "runtimes", "--json"])
        matches = [item for item in json.loads(text)["runtimes"] if item["identifier"] == devices[0]["runtime_id"]]
        require(len(matches) == 1 and matches[0]["version"] == "17.0" and matches[0]["buildversion"] == "21A328", "runtime mismatch")
        self.result["runtime"] = dict(device={k: devices[0][k] for k in ("udid", "name", "state", "runtime_id")},
                                      version=matches[0]["version"], build=matches[0]["buildversion"])

    def macho(self, file, platform):
        _, arch = self.run(["xcrun", "lipo", "-archs", file])
        require(arch.strip() == "arm64", f"wrong architecture {file}: {arch}")
        _, load = self.run(["xcrun", "otool", "-l", file])
        builds = re.findall(r"cmd LC_BUILD_VERSION\s+cmdsize \d+\s+platform (\S+)\s+minos (\S+)", load)
        require(builds, f"missing Mach-O platform/minimum: {file}")
        if file.suffix == ".a":
            _, members = self.run(["xcrun", "ar", "-t", file])
            require(len(builds) == sum(name.endswith(".o") for name in members.splitlines()), "object missing Mach-O minimum")
        require(all(p in platform and tuple(map(int, v.split('.'))) in ((17, 0), (17, 0, 0)) for p, v in builds), f"wrong Mach-O minimum/platform {file}")
        return dict(bytes=file.stat().st_size, sha256=sha(file), architecture="arm64", build_commands=len(builds), platforms=sorted(set(p for p, _ in builds)), minimums=sorted(set(v for _, v in builds)))

    def configuration(self, build, sdk, triple, flags):
        config = (build / "config.h").read_text()
        components = (build / "config_components.h").read_text()
        for name in ("GPL", "NONFREE", "VERSION3", "NETWORK", "LIBX264", "LIBMP3LAME", "LIBVORBIS", "AUDIOTOOLBOX", "VIDEOTOOLBOX", "SECURETRANSPORT", "ZLIB", "BZLIB", "LZMA", "ICONV"):
            require(re.search(rf"^#define CONFIG_{name} 0$", config, re.M), f"unexpected config {name}")
        enabled = {}
        for category in ("DECODER", "ENCODER", "DEMUXER", "MUXER", "PROTOCOL", "FILTER", "HWACCEL"):
            enabled[category] = re.findall(rf"^#define CONFIG_(\w+)_{category} 1$", components, re.M)
        cli = re.search(r"^#define CONFIG_FFMPEG (\d+)$", config, re.M)
        self.result.setdefault("targets", {})[sdk] = dict(triple=triple, configure=flags, enabled=enabled,
            config_ffmpeg=int(cli.group(1)) if cli else None, config_sha256=sha(build / "config.h"),
            components_sha256=sha(build / "config_components.h"), make_config_sha256=sha(build / "ffbuild/config.mak"))
        self.save()
        for category, expected in dict(DECODER={"VORBIS", "AMRNB", "PCM_S16LE", "MP3"},
                                       ENCODER={"PCM_F32LE", "PCM_S16LE"}, DEMUXER={"OGG", "AMR", "MOV", "WAV", "MP3"},
                                       MUXER={"PCM_F32LE", "WAV"}, PROTOCOL={"FILE"}, HWACCEL=set()).items():
            require(set(enabled[category]) == expected, f"unexpected enabled {category}: {enabled[category]}")
        require(set(enabled["FILTER"]) <= {"ABUFFER", "ABUFFERSINK", "ARESAMPLE", "AFORMAT", "ANULL"}, "unexpected filters")
        require(cli and cli.group(1) == "0", "audio-only harness requires no FFmpeg CLI/video-filter expansion")

    def libraries(self, build, sdk):
        prefix = build / "install"
        libraries = sorted((prefix / "lib").glob("*.a"))
        require({p.name for p in libraries} == {"libavcodec.a", "libavformat.a", "libavutil.a", "libavfilter.a", "libswresample.a"}, "unexpected library set")
        platform = {"7", "IOSSIMULATOR"} if sdk == "iphonesimulator" else {"2", "IOS"}
        self.result["targets"][sdk]["libraries"] = {file.name: self.macho(file, platform) for file in libraries}
        self.save()
        return prefix

    def reuse_simulator(self, identifier):
        require(str(uuid.UUID(identifier)) == identifier, "simulator build must be an owned UUID directory")
        previous = self.root.parent / identifier
        report = json.loads((previous / "result.json").read_text())
        require(report["signature"] == self.result["signature"], "simulator/source signature correspondence")
        expected = next(x["sha256"] for x in self.result["downloads"] if x["url"] == SOURCE)
        require(next(x["sha256"] for x in report["downloads"] if x["url"] == SOURCE) == expected, "simulator/source archive correspondence")
        build = previous / "iphonesimulator"
        target = report["targets"]["iphonesimulator"]
        self.configuration(build, "iphonesimulator", "arm64-apple-ios17.0-simulator", target["configure"])
        self.result["targets"]["iphonesimulator"]["reused_from"] = str(build)
        return self.libraries(build, "iphonesimulator")

    def build(self, sdk, triple):
        self.result["stage"] = f"build-{sdk}"
        build = self.root / sdk
        build.mkdir()
        prefix = build / "install"
        _, sysroot = self.run(["xcrun", "--sdk", sdk, "--show-sdk-path"])
        tools = {}
        for tool in ("clang", "ar", "ranlib", "nm"):
            _, output = self.run(["xcrun", "--sdk", sdk, "--find", tool])
            tools[tool] = output.strip()
        flags = FLAGS + [f"--prefix={prefix}", f"--cc={tools['clang']}", f"--ar={tools['ar']}",
                         f"--ranlib={tools['ranlib']}", f"--nm={tools['nm']}", "--pkg-config=/usr/bin/false",
                         f"--sysroot={sysroot.strip()}", f"--extra-cflags=-target {triple}", f"--extra-ldflags=-target {triple}"]
        self.run([self.source / "configure", *flags], cwd=build, timeout=180)
        self.configuration(build, sdk, triple, flags)
        self.run(["make", "-j4", "install-libs", "install-headers"], cwd=build, timeout=600)
        return self.libraries(build, sdk)

    def harness(self, prefix):
        self.result["stage"] = "compile-audio-only-harness"
        source = self.project / "scripts/preflight-ios-ffmpeg-decode.c"
        binary = self.root / "decode-simulator"
        _, clang = self.run(["xcrun", "--sdk", "iphonesimulator", "--find", "clang"])
        _, sdk = self.run(["xcrun", "--sdk", "iphonesimulator", "--show-sdk-path"])
        make_config = (prefix.parent / "ffbuild/config.mak").read_text()
        extra = []
        for library in ("avformat", "avcodec", "swresample", "avutil"):
            value = re.search(rf"^EXTRALIBS-{library}=(.*)$", make_config, re.M).group(1)
            extra.extend(shlex.split(value))
        require(set(extra) <= {"-lm", "-pthread", "-framework", "CoreFoundation", "CoreVideo", "CoreMedia"}, "new system dependency needs review")
        self.run([clang.strip(), "-target", "arm64-apple-ios17.0-simulator", "-isysroot", sdk.strip(),
            "-std=c17", "-O2", "-Wall", "-Wextra", "-Werror", "-Werror=partial-availability",
            "-I", prefix / "include", source, "-L", prefix / "lib", "-lavformat", "-lavcodec", "-lswresample", "-lavutil",
            *extra, "-o", binary], timeout=120)
        self.result["harness"] = dict(source_sha256=sha(source), binary=self.macho(binary, {"7", "IOSSIMULATOR"}),
                                      linked_libraries=["avformat", "avcodec", "swresample", "avutil"], system_libraries=extra)
        self.save()
        return binary

    def proof(self, binary, fixtures):
        self.result["stage"] = "simulator-native-file-decode"
        self.runtime()
        base = ["xcrun", "simctl", "spawn", UDID, binary]
        _, text = self.run([*base, "--info"])
        require("version=8.1.2\n" in text and "license=LGPL version 2.1 or later" in text, "unexpected native version/license")
        require(all(flag in text for flag in ("--disable-gpl", "--disable-nonfree", "--disable-version3", "--disable-network", "--disable-autodetect")), "native config correspondence")
        require(not any(flag in text for flag in ("--enable-gpl", "--enable-nonfree", "--enable-libvorbis", "--enable-libmp3lame", "--enable-libx264")), "native unwanted feature")
        self.result["native_info"] = text
        output = self.root / "pcm"
        output.mkdir()
        matrix = {}
        expected_codecs = dict(vorbis="vorbis", mp3="mp3", amr="amr_nb", threeGp="amr_nb", wav="pcm_s16le")
        for name, fixture in fixtures.items():
            target = output / f"{name}.f32"
            _, text = self.run([*base, fixture, target], timeout=30)
            native = json.loads(next(line for line in text.splitlines() if line.startswith('{"status":')))
            require(native["status"] == "ok", "native decoder did not finish")
            require(native["codec"] == expected_codecs[name] and native["input_bytes"] == fixture.stat().st_size, "native input/codec mismatch")
            require(target.is_file() and target.stat().st_size <= 1024 * 1024, "missing/oversized native PCM")
            matrix[name] = pcm_stats(target.read_bytes())
            require(matrix[name]["frames"] == native["frames"], "native/host PCM frame mismatch")
            require(abs(matrix[name]["rms"] - native["rms"]) < 1e-10, "native/host PCM energy mismatch")
            matrix[name]["native"] = native
            self.result["format_matrix"] = matrix
            self.save()
        damaged = self.root / "fixtures" / "truncated.ogg"
        with damaged.open("xb") as file:
            file.write(fixtures["vorbis"].read_bytes()[:64])
        target = output / "negative.f32"
        status, text = self.run([*base, damaged, target], success=False)
        require(status != 0 and (not target.exists() or target.stat().st_size == 0), "truncated fixture wrongly accepted")
        self.result["negative"] = dict(status=status, stderr=text, scope="one bounded truncated OGG, not malformed-input security validation")
        cancelled = output / "cancelled.f32"
        status, text = self.run([*base, fixtures["vorbis"], cancelled, "--cancel-after-frames", "1"], success=False)
        native = json.loads(next(line for line in text.splitlines() if line.startswith('{"status":')))
        require(status != 0 and native["status"] == "cancelled" and not cancelled.exists(), "cancellation/partial output cleanup failed")
        self.result["cancellation"] = dict(status=status, native=native, scope="self-raised SIGINT after first PCM; not OS background integration")
        oversized = self.root / "fixtures" / "oversized-sparse.bin"
        with oversized.open("xb") as file:
            file.truncate(64 * 1024 * 1024 + 1)
        rejected = output / "oversized.f32"
        status, text = self.run([*base, oversized, rejected], success=False)
        native = json.loads(next(line for line in text.splitlines() if line.startswith('{"status":')))
        require(status != 0 and native["reason"] == "input-size-limit" and native["read_bytes"] == 0 and not rejected.exists(), "source size cap failed")
        self.result["source_cap"] = dict(status=status, native=native, sparse=True)
        long_wave = self.root / "fixtures" / "over-duration.wav"
        with long_wave.open("xb") as file:
            with wave.open(file, "wb") as wav:
                wav.setparams((1, 2, 1, 601, "NONE", "not compressed"))
                wav.writeframes(bytes(601 * 2))
        rejected = output / "over-duration.f32"
        status, text = self.run([*base, long_wave, rejected], success=False)
        native = json.loads(next(line for line in text.splitlines() if line.startswith('{"status":')))
        require(status != 0 and native["reason"] == "metadata-duration-limit" and not rejected.exists(), "duration cap failed")
        self.result["duration_cap"] = dict(status=status, native=native, scope="tiny 601-second metadata fixture, no large media generation")
        existing = output / "existing-owned-sentinel.f32"
        sentinel = b"do not overwrite"
        with existing.open("xb") as file:
            file.write(sentinel)
        status, text = self.run([*base, fixtures["vorbis"], existing], success=False)
        require(status != 0 and existing.read_bytes() == sentinel, "existing output was overwritten/deleted")
        self.result["exclusive_output"] = dict(status=status, unchanged=True, stderr=text)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--intake-only", action="store_true")
    parser.add_argument("--verified-source-run", help="reverify an earlier owned UUID intake; extract into a NEW run")
    parser.add_argument("--simulator-libs-run", help="validate/reuse earlier owned simulator static libs; never overwrite them")
    args = parser.parse_args()
    self_test()
    if args.self_test:
        print("self-checks PASS")
        return
    task = Preflight()
    print(task.root, flush=True)
    try:
        _, xcode = task.run(["xcodebuild", "-version"])
        require(xcode.startswith("Xcode 27.2\n"), "stable Xcode27.2 required")
        task.result["xcode"] = xcode
        if args.verified_source_run:
            task.reuse_source(args.verified_source_run)
        else:
            task.intake()
        if not args.intake_only:
            fixtures = task.fixtures()
            prefix = task.reuse_simulator(args.simulator_libs_run) if args.simulator_libs_run else task.build("iphonesimulator", "arm64-apple-ios17.0-simulator")
            binary = task.harness(prefix)
            task.proof(binary, fixtures)
            task.build("iphoneos", "arm64-apple-ios17.0")
        task.result["status"] = "INTAKE_ONLY" if args.intake_only else "PASS"
    except Exception as error:
        task.result.update(status="STOP", error=str(error))
        raise
    finally:
        changed = [str(path) for path, digest in task.originals.items() if not path.is_file() or sha(path) != digest]
        if changed:
            task.result.update(status="STOP", error=f"ORIGINAL FIXTURE CHANGED: {changed}")
        task.result["original_fixture_hashes_unchanged"] = not changed
        task.result["original_fixture_files_verified"] = len(task.originals)
        task.save()
        require(not changed, f"ORIGINAL FIXTURE CHANGED: {changed}")


if __name__ == "__main__":
    main()
