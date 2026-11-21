#!/usr/bin/env python3
"""Pin approved LINKa launcher sources and verify the actual universal APK.

For AAB releases run bundletool build-apks --mode=universal first, then check
the extracted universal.apk with --apk and the Android SDK's --aapt executable.
"""
import argparse
import hashlib
from pathlib import Path
import re
import shutil
import struct
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile
import zlib

ROOT = Path(__file__).resolve().parents[1]
ANDROID = "{http://schemas.android.com/apk/res/android}"
# Approved original debug assets; round and regular launcher PNGs are identical.
APPROVED = {
    "mdpi": (48, "5a754a25932a1c2f03ed157205fe97885a101d288c65779839a2ce6081f764fa",
             "d7b2f89fa72b83c315152e7a028e3e740958670b6438256b7dfca98cfb1e554d"),
    "hdpi": (72, "eb0b6553a7db33fd13b1370df35e16257589aa199372e519072917bca1bea4ac",
             "22f2462387fbbedf867a81b72fff7b9dafba86917b9f22ca6534eb5e6fc6e503"),
    "xhdpi": (96, "c121f53c1150d75e75c514dc14aa0c14cd50c1ddc714066e86a5f08333575bf0",
              "47e3c2fced57c02daebe4ee332b8cb405979f93a1c4576b19fd3975f68d363bd"),
    "xxhdpi": (144, "d81606f2bfef580a316bf56ee37d8a5801f436572de2bdccc7c4aa329b2ce0ca",
               "e8946f2b84046b3fa194228cd7d1688ed0cce0e44f15140ebfed8d003b022b9a"),
    "xxxhdpi": (192, "1397ae1e5cc0c62dd5a196ac2a8962ed62359855c41da55257413f03700012b4",
                "8f6a3b2b5c7d0c176bfcd0f258d9dea186e3d28084bff43d2867de885c665950"),
}
NAMES = ("ic_launcher", "ic_launcher_round", "ic_launcher_foreground")


def require(condition, message):
    if not condition:
        raise ValueError(message)


def rgba(data):
    """Decode noninterlaced PNG, including aapt's palette/gray crunch output."""
    require(data[:8] == b"\x89PNG\r\n\x1a\n" and len(data) <= 16 * 1024 * 1024, "Invalid PNG")
    chunks, offset = {}, 8
    while offset < len(data):
        require(offset + 12 <= len(data), "Truncated PNG")
        size, kind = struct.unpack_from(">I4s", data, offset)
        end = offset + 12 + size
        require(end <= len(data), "Truncated PNG chunk")
        payload = data[offset + 8:end - 4]
        require(zlib.crc32(kind + payload) & 0xffffffff == struct.unpack_from(">I", data, end - 4)[0],
                "PNG CRC mismatch")
        require(kind not in chunks or kind == b"IDAT", "Duplicate PNG chunk")
        chunks[kind] = chunks.get(kind, b"") + payload
        offset = end
    require(b"IEND" in chunks and len(chunks[b"IHDR"]) == 13, "Missing PNG header/end")
    w, h, depth, color, compression, filtering, interlace = struct.unpack(">IIBBBBB", chunks[b"IHDR"])
    require(0 < w <= 512 and 0 < h <= 512 and not (compression or filtering or interlace),
            "Unsupported PNG dimensions/interlace")
    channels = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}.get(color)
    require(channels and depth in (1, 2, 4, 8) and (depth == 8 or color in (0, 3)),
            "Unsupported PNG color/depth")
    stride = (w * channels * depth + 7) // 8
    bpp = max(1, channels * depth // 8)
    decoder = zlib.decompressobj()
    raw = decoder.decompress(chunks[b"IDAT"], (stride + 1) * h + 1)
    require(len(raw) == (stride + 1) * h and decoder.eof and not decoder.unused_data
            and not decoder.unconsumed_tail, "Invalid PNG raster")
    previous, pixels = bytearray(stride), bytearray()
    palette, alpha = chunks.get(b"PLTE", b""), chunks.get(b"tRNS", b"")
    for y in range(h):
        start = y * (stride + 1)
        mode, row = raw[start], bytearray(raw[start + 1:start + 1 + stride])
        require(mode <= 4, "Invalid PNG filter")
        for x in range(stride):
            left = row[x - bpp] if x >= bpp else 0
            above, corner = previous[x], previous[x - bpp] if x >= bpp else 0
            estimate = left + above - corner
            distances = (abs(estimate - left), abs(estimate - above), abs(estimate - corner))
            paeth = (left, above, corner)[distances.index(min(distances))]
            row[x] = (row[x] + (0, left, above, (left + above) // 2, paeth)[mode]) & 255
        samples = row if depth == 8 else [
            (row[i * depth // 8] >> (8 - depth - i * depth % 8)) & ((1 << depth) - 1)
            for i in range(w)]
        for x in range(w):
            sample = samples[x * channels:(x + 1) * channels]
            if color == 3:
                index = sample[0]
                require(index * 3 + 3 <= len(palette), "Invalid PNG palette")
                pixel = (*palette[index * 3:index * 3 + 3], alpha[index] if index < len(alpha) else 255)
            elif color in (0, 4):
                gray = sample[0] * 255 // ((1 << depth) - 1)
                pixel = (gray, gray, gray, sample[1] if color == 4 else
                         0 if alpha and sample[0] == int.from_bytes(alpha, "big") else 255)
            else:
                pixel = (*sample[:3], sample[3] if color == 6 else
                         0 if alpha and tuple(sample) == struct.unpack(">HHH", alpha) else 255)
            # RGB under fully transparent pixels is not visible and may be rewritten by aapt.
            pixels.extend(pixel if pixel[3] else (0, 0, 0, 0))
        previous = row
    return w, h, bytes(pixels)


def check_source(root):
    main = root / "app/src/main"
    expected, images = set(), {}
    for density, (size, regular_hash, foreground_hash) in APPROVED.items():
        for name in NAMES:
            relative = f"mipmap-{density}/{name}.png"
            expected.add(relative)
            data = (main / "res" / relative).read_bytes()
            require(hashlib.sha256(data).hexdigest() ==
                    (foreground_hash if name.endswith("foreground") else regular_hash),
                    f"Unapproved launcher: {relative}")
            decoded = rgba(data)
            side = size * 9 // 4 if name.endswith("foreground") else size
            require(decoded[:2] == (side, side), f"Wrong dimensions: {relative}")
            images[density, name] = decoded
    for name in NAMES[:2]:
        relative = f"mipmap-anydpi-v26/{name}.xml"
        expected.add(relative)
        adaptive = ET.parse(main / "res" / relative).getroot()
        require(adaptive.tag == "adaptive-icon" and
                [(child.tag, child.get(ANDROID + "drawable")) for child in adaptive] ==
                [("background", "@color/ic_launcher_background"),
                 ("foreground", "@mipmap/ic_launcher_foreground")], f"Wrong adaptive mapping: {relative}")
    expected.add("values/ic_launcher_background.xml")
    background = ET.parse(main / "res/values/ic_launcher_background.xml").getroot()
    require([(child.tag, child.get("name"), child.text) for child in background] ==
            [("color", "ic_launcher_background", "#7A662E")], "Wrong background color")
    actual = {str(p.relative_to(main / "res")) for p in (main / "res").rglob("*ic_launcher*") if p.is_file()}
    require(actual == expected, f"Unexpected launcher resources: {actual ^ expected}")
    for variant in (root / "app/src").iterdir():
        if variant.is_dir() and variant.name != "main":
            require(not list(variant.rglob("*ic_launcher*.*")), f"Launcher override outside main: {variant}")
    store = (main / "ic_launcher-playstore.png").read_bytes()
    require(hashlib.sha256(store).hexdigest() ==
            "4644d037c88cfeeadcdb65766c4d05f43e365354bb0b54b7e1157ae23ac7bc0f",
            "Unapproved store PNG")
    require(rgba(store)[:2] == (512, 512), "Wrong store PNG size")
    application = ET.parse(main / "AndroidManifest.xml").getroot().find("application")
    require(application.get(ANDROID + "icon") == "@mipmap/ic_launcher" and
            application.get(ANDROID + "roundIcon") == "@mipmap/ic_launcher_round", "Wrong manifest icon")
    return images


def resource_table(text):
    entries, ids, config, pending = {}, {}, None, None
    for line in text.splitlines():
        match = re.match(r"\s*config (.*):$", line)
        if match:
            config = match[1]
        match = re.match(r"\s*resource (0x[0-9a-f]+) [^:]+:([^:]+): t=(0x[0-9a-f]+) d=(0x[0-9a-f]+)", line)
        if match:
            rid, name, kind, value = match.groups()
            ids[name] = rid
            pending = (name, config)
            entries[pending] = (kind, value)
        match = re.match(r'\s*\(string8\) "([^"]+)"$', line)
        if match and pending:
            entries[pending] = match[1]
            pending = None
    return entries, ids


def check_apk(apk, aapt, images):
    def dump(*args):
        return subprocess.check_output([str(aapt), "dump", *args], text=True)
    entries, ids = resource_table(dump("--values", "resources", str(apk)))
    manifest = dump("xmltree", str(apk), "AndroidManifest.xml")
    application = re.search(r"^\s*E: application\b[^\n]*\n", manifest, re.MULTILINE)
    require(application, "APK application element missing")
    attributes = []
    for line in manifest[application.end():].splitlines():
        if re.match(r"\s*E:", line):
            break
        attributes.append(line)
    application_attributes = "\n".join(attributes)
    for attr, name in (("icon", "ic_launcher"), ("roundIcon", "ic_launcher_round")):
        rid = ids.get(f"mipmap/{name}")
        require(rid and re.search(rf"android:{attr}\([^)]*\)=@{rid}\b", application_attributes),
                f"APK manifest does not reference mipmap/{name}")
    with zipfile.ZipFile(apk) as archive:
        for name in NAMES:
            expected_configs = set(APPROVED) | ({"anydpi-v26"} if name != NAMES[2] else set())
            configs = {config for resource, config in entries if resource == f"mipmap/{name}"}
            require(configs == expected_configs, f"APK configurations for {name}: {configs}")
            for density in APPROVED:
                path = entries[f"mipmap/{name}", density]
                require(isinstance(path, str), f"APK bitmap reference missing: {name}/{density}")
                try:
                    decoded = rgba(archive.read(path))
                except ValueError as exc:
                    raise ValueError(f"APK launcher {name}/{density} ({path}): {exc}") from exc
                require(decoded == images[density, name],
                        f"APK launcher pixels differ: {name}/{density} ({path})")
        background = {value for (name, _), value in entries.items() if name == "color/ic_launcher_background"}
        require(background and all(kind in ("0x1c", "0x1d") and value == "0xff7a662e"
                                   for kind, value in background), f"APK background differs: {background}")
        for name in NAMES[:2]:
            path = entries[f"mipmap/{name}", "anydpi-v26"]
            tree = dump("xmltree", str(apk), path)
            require(re.findall(r"E: ([\w-]+)", tree) == ["adaptive-icon", "background", "foreground"],
                    f"APK adaptive structure differs: {name}")
            require(re.findall(r"android:drawable\([^)]*\)=@(0x[0-9a-f]+)", tree) ==
                    [ids["color/ic_launcher_background"], ids["mipmap/ic_launcher_foreground"]],
                    f"APK adaptive references differ: {name}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--apk", type=Path)
    parser.add_argument("--aapt", type=Path, default=shutil.which("aapt"),
                        help="Android SDK build-tools/aapt executable (default: aapt on PATH)")
    args = parser.parse_args()
    if args.apk and not args.aapt:
        parser.error("--apk requires --aapt (Android SDK build-tools/aapt)")
    try:
        images = check_source(args.root)
        if args.apk:
            check_apk(args.apk, args.aapt, images)
    except (ValueError, OSError, KeyError, struct.error, zlib.error, ET.ParseError,
            zipfile.BadZipFile, subprocess.CalledProcessError) as exc:
        print(f"Android launcher check FAILED: {exc}", file=sys.stderr)
        return 1
    print("Android launcher check OK" + (f": {args.apk}" if args.apk else ": main resources"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
