#!/usr/bin/env python3
"""Content-only canonical .linka 3.0 packer; never installs runtime defaults."""
import argparse
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import re
import struct
import tempfile
import zipfile
import zlib

spec = importlib.util.spec_from_file_location("starter_audio", Path(__file__).with_name("prepare-starter-audio.py"))
audio = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audio)
ROOT = Path(__file__).resolve().parents[1]
MEDIA_LIMIT = 64 * 1024 * 1024
ARCHIVE_LIMIT = 500 * 1024 * 1024


def json_bytes(value):
    return (json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n").encode()


def load_json(path):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError("Duplicate JSON key")
            result[key] = value
        return result
    if path.stat().st_size > 8 * 1024 * 1024:
        raise ValueError("Manifest too large")
    return json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=unique)


def checked_file(directory, record, suffix):
    filename = record.get("file")
    if not isinstance(filename, str) or not re.fullmatch(r"[a-z0-9][a-z0-9-]*" + re.escape(suffix), filename):
        raise ValueError("Unsafe media filename")
    path = directory / filename
    if directory.is_symlink() or path.is_symlink() or path.resolve().parent != directory.resolve():
        raise ValueError("Unsafe media target")
    size, sha = record.get("size"), record.get("sha256")
    if (type(size) is not int or not 0 < size <= MEDIA_LIMIT
            or not isinstance(sha, str) or not re.fullmatch(r"[0-9a-f]{64}", sha)):
        raise ValueError("Missing/invalid media size/hash")
    if not path.is_file() or path.stat().st_size != size:
        raise ValueError("Missing media or size mismatch")
    data = path.read_bytes()
    if hashlib.sha256(data).hexdigest() != sha:
        raise ValueError("Media hash mismatch")
    return path, data


def png_dimensions(data):
    """Bounded 8-bit RGB/RGBA, non-interlaced PNG subset, with CRC/raster checks."""
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError("Not PNG")
    offset, image, ended, idat_closed = 8, bytearray(), False, False
    dimensions = None
    while offset < len(data):
        if offset + 12 > len(data):
            raise ValueError("Truncated PNG chunk")
        length, kind = struct.unpack_from(">I4s", data, offset)
        end = offset + 12 + length
        if (end > len(data) or not re.fullmatch(rb"[A-Za-z]{4}", kind)
                or kind[2] & 32 or kind in {b"acTL", b"fcTL", b"fdAT"}):
            raise ValueError("Invalid PNG chunk")
        payload = data[offset + 8:end - 4]
        if zlib.crc32(kind + payload) & 0xffffffff != struct.unpack_from(">I", data, end - 4)[0]:
            raise ValueError("PNG CRC mismatch")
        if dimensions is None:
            if kind != b"IHDR" or length != 13:
                raise ValueError("PNG must start with IHDR")
            width, height, depth, color, compression, filtering, interlace = struct.unpack(">IIBBBBB", payload)
            if (not 0 < width <= 8192 or not 0 < height <= 8192 or width * height > 16000000
                    or depth != 8 or color not in (2, 6) or compression or filtering or interlace):
                raise ValueError("PNG bounds/encoding unsupported")
            dimensions = width, height, 3 if color == 2 else 4
        elif kind == b"IDAT":
            if idat_closed:
                raise ValueError("Noncontiguous PNG image data")
            image.extend(payload)
        elif kind == b"IEND":
            if length or not image or end != len(data):
                raise ValueError("Invalid PNG ending/trailing bytes")
            ended = True
            break
        else:
            if kind == b"IHDR" or kind[0] & 32 == 0:
                raise ValueError("Unsupported critical PNG chunk")
            idat_closed = bool(image)
        offset = end
    if not ended:
        raise ValueError("Missing PNG IEND")
    width, height, channels = dimensions
    stride = width * channels + 1
    expected = stride * height
    decoder = zlib.decompressobj()
    raw = decoder.decompress(image, expected + 1)
    if (len(raw) != expected or not decoder.eof or decoder.unused_data or decoder.unconsumed_tail
            or any(raw[row * stride] > 4 for row in range(height))):
        raise ValueError("Invalid PNG raster")
    return width, height


def zip_bytes(config, media):
    contents = {"config.json": json_bytes(config), **media}
    if len(contents) > 2000 or len(contents["config.json"]) > 8 * 1024 * 1024:
        raise ValueError("Canonical ZIP/config limits exceeded")
    stream = io.BytesIO()
    with zipfile.ZipFile(stream, "w", compression=zipfile.ZIP_STORED, allowZip64=False) as archive:
        for name in ["config.json", *sorted(media)]:
            info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            info.create_system = 3
            info.external_attr = 0o100644 << 16
            archive.writestr(info, contents[name])
    result = stream.getvalue()
    if len(result) > ARCHIVE_LIMIT:
        raise ValueError("Canonical archive limit exceeded")
    return result


def build(root, image_manifest_path, *, synthetic_test=False):
    root = root.resolve()
    if synthetic_test and (root == ROOT.resolve() or not root.is_relative_to(Path(tempfile.gettempdir()).resolve())):
        raise ValueError("Synthetic build allowed only in separate temporary test root")
    source = root / "assets/starter-pack"
    output = source / "archives"
    for directory in [source / "images", source / "audio", output]:
        if directory.is_symlink() or not directory.resolve().is_relative_to(root):
            raise ValueError("Unsafe media/output directory")
    manifest_path = root / "docs/development/starter-pack-manifest.json"
    manifest = load_json(manifest_path)
    rows = audio.requests(manifest_path.with_name("starter-pack-recordings.csv"), manifest, source / "audio")
    boards = manifest["proposal"]
    if (len(boards) != 5 or sum(len(b["pages"]) for b in boards) != 10
            or [b["seedKey"] for b in boards] != ["ru.core.v1", "ru.food.v1", "ru.activities.v1", "ru.feelings.v1", "ru.letters.v1"]):
        raise ValueError("Unexpected approved board matrix")
    index = load_json(source / "audio/manifest.json")
    audio.validate_index(index, rows)
    images = load_json(image_manifest_path)
    core = {row["key"]: row for row in rows if not row["key"].startswith("ru.letter.")}
    purpose = "synthetic-test-only" if synthetic_test else "production"
    if (images.get("schema") != "linka-starter-images-v1" or images.get("purpose") != purpose
            or images.get("status") != "approved" or not isinstance(images.get("images"), dict)
            or set(images["images"]) != set(core) or len(core) != 41):
        raise ValueError("Expected approved image manifest for all 41 core keys")
    media, paths, aggregate = {}, {}, 0
    for key in core:
        record = images["images"][key]
        if (not isinstance(record.get("provenance"), str) or not record["provenance"].strip()
                or record.get("visualApproval") != "approved"):
            raise ValueError("Missing image provenance/visual approval")
        _, image_data = checked_file(source / "images", record, ".png")
        dimensions = png_dimensions(image_data)
        if not synthetic_test and min(dimensions) < 2:
            raise ValueError("One-pixel placeholders forbidden in production")
        recording = index["records"].get(key)
        if not recording or recording.get("voice") != "anton":
            raise ValueError("Missing anton recording provenance")
        path, audio_data = checked_file(source / "audio", {
            "file": recording["expected_filename"], "size": recording["size"], "sha256": recording["sha256"]}, ".mp3")
        if recording.get("validation") != audio.inspect(path):
            raise ValueError("MP3 validation differs from receipt")
        pair = []
        for data in [image_data, audio_data]:
            name = "media/" + hashlib.sha256(data).hexdigest()
            if name not in media:
                aggregate += len(data)
                if aggregate > ARCHIVE_LIMIT:
                    raise ValueError("Source media budget exceeded")
                media[name] = data
            pair.append(name)
        paths[key] = pair
    locations = {loc: row for row in rows for loc in row["used_in"].split(";")}
    inputs = {"vocabulary": manifest_path, "recordingsCsv": manifest_path.with_name("starter-pack-recordings.csv"),
              "audioManifest": source / "audio/manifest.json", "imageManifest": image_manifest_path}
    outputs, catalog = {}, {"schema": "linka-starter-archives-v1", "archiveVersion": "3.0",
        "shipping": False, "purpose": purpose, "nativeCodecReview": "pending",
        "inputSha256": {key: hashlib.sha256(path.read_bytes()).hexdigest() for key, path in inputs.items()}, "sets": []}
    for board in boards:
        seed = board["seedKey"]
        letters = seed == "ru.letters.v1"
        config = {"version": "3.0", "withoutSpace": letters, "directSet": seed == "ru.core.v1", "pages": []}
        used = set()
        for pi, page in enumerate(board["pages"], 1):
            page_id = f"{seed}:p{pi}"
            wire_page = {"id": page_id, "mode": "standard", "rows": 3, "columns": 3, "cards": []}
            for ci, text in enumerate(page, 1):
                loc = f"{page_id}:c{ci}"
                card = {"id": loc, "cardType": 1 if text == "SPACE" else 0, "width": 1, "height": 1}
                if text != "SPACE":
                    card["title"] = text
                    if not letters:
                        key = locations[loc]["key"]
                        image_path, audio_path = paths[key]
                        recording = index["records"][key]
                        card.update(imagePath=image_path, audioPath=audio_path,
                                    audioText=recording["speechText"], audioVoice=recording["voice"])
                        used.update([image_path, audio_path])
                wire_page["cards"].append(card)
            config["pages"].append(wire_page)
        data = zip_bytes(config, {name: media[name] for name in used})
        filename = seed + ".linka"
        outputs[filename] = data
        catalog["sets"].append({"seedKey": seed, "displayName": board["displayName"], "file": filename,
            "size": len(data), "sha256": hashlib.sha256(data).hexdigest(), "pages": len(config["pages"]),
            "slots": sum(len(p["cards"]) for p in config["pages"]), "optional": letters,
            "speechCapability": "native TTS Russian voice required for whole words" if letters else "prerecorded offline"})
    outputs["catalog.json"] = json_bytes(catalog)
    # Validate every input first; never overwrite a different artifact.
    for name, data in outputs.items():
        path = output / name
        if path.is_symlink() or (path.exists() and path.read_bytes() != data):
            raise ValueError("Existing artifact differs; review/remove owned old output explicitly")
    output.mkdir(parents=True, exist_ok=True)
    for name, data in outputs.items():
        path = output / name
        if not path.exists():
            with path.open("xb") as f:
                f.write(data)
    return catalog


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--images-manifest", type=Path, required=True)
    args = parser.parse_args()
    result = build(ROOT, args.images_manifest)
    print(f"Built {len(result['sets'])} canonical 3.0 archives; native codec/media review still required")
