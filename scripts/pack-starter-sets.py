#!/usr/bin/env python3
"""Content-only canonical .linka 3.0 packer; never installs runtime defaults."""
import argparse
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import re
import tempfile
import zipfile
import sys

sys.dont_write_bytecode = True
from starter_png import dimensions as png_dimensions

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
    if path.is_symlink() or path.stat().st_size > 8 * 1024 * 1024:
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


def check_output(output, root):
    if output.is_symlink():
        raise ValueError("Symlink output directory")
    resolved = output.resolve()
    root = root.resolve()
    if resolved.is_relative_to(root) and "build" not in resolved.relative_to(root).parts:
        raise ValueError("Generated output inside project must be under build/, never source assets")
    # Check output components, not trusted system aliases such as macOS /var -> /private/var.
    anchor = root if resolved.is_relative_to(root) else Path(tempfile.gettempdir()).resolve()
    current = output.absolute()
    while current.resolve() != anchor and current != current.parent:
        if current.is_symlink():
            raise ValueError("Symlink output ancestor")
        current = current.parent


def validate_recording(recording, key):
    evidence = recording.get("validation", {})
    duration = evidence.get("durationSeconds", 0)
    peak = evidence.get("maxVolumeDb", float("-inf"))
    if (type(duration) not in (float, int) or type(peak) not in (float, int)
            or recording.get("voice") != "anton" or evidence.get("format") != "mp3"
            or evidence.get("codec") != "mp3" or evidence.get("strictDecode") != "passed"
            or evidence.get("nonSilent") is not True or not 0 < duration <= 30
            or not -50 <= peak <= 0 or evidence.get("channels") not in (1, 2)
            or type(evidence.get("sampleRate")) is not int or evidence["sampleRate"] <= 0):
        raise ValueError(f"Missing verified MP3 acquisition evidence: {key}")


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


def build(root, image_manifest_path, output_dir, *, synthetic_test=False):
    root = root.resolve()
    if synthetic_test and (root == ROOT.resolve() or not root.is_relative_to(Path(tempfile.gettempdir()).resolve())):
        raise ValueError("Synthetic build allowed only in separate temporary test root")
    source = root / "assets/starter-pack"
    if not source.resolve().is_relative_to(root) or not (source / "audio").resolve().is_relative_to(root):
        raise ValueError("Unsafe source audio directory")
    output = Path(output_dir)
    check_output(output, root)
    images_directory = image_manifest_path.parent
    for directory in [source / "audio", images_directory]:
        if directory.is_symlink():
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
    if set(index["records"]) != {row["key"] for row in rows}:
        raise ValueError("Missing acquisition records")
    acquired_audio = {}
    for row in rows:
        key = row["key"]
        record = index["records"][key]
        validate_recording(record, key)
        _, acquired_audio[key] = checked_file(source / "audio", {
            "file": record["expected_filename"], "size": record["size"], "sha256": record["sha256"]}, ".mp3")
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
        _, image_data = checked_file(images_directory, record, ".png")
        dimensions = png_dimensions(image_data)
        if not synthetic_test and min(dimensions) < 2:
            raise ValueError("One-pixel placeholders forbidden in production")
        audio_data = acquired_audio[key]
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
    if not synthetic_test:
        inputs.update(sheetsCsv=source / "sheets.csv", sourceReceipt=source / "source-manifest.json")
    outputs, catalog = {}, {"schema": "linka-starter-archives-v1", "archiveVersion": "3.0",
        "shipping": False, "purpose": purpose, "nativeCodecReview": "pending",
        "audioBuildValidation": "SHA256/size/HTTP receipt and prior strictDecode/nonSilent evidence; no decode during app build",
        "assetReview": {"actor": images.get("approvalActor"), "scope": images.get("approvalScope"),
                        "humanVisualApproval": images.get("humanVisualApproval", False)},
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
    # Validate every input first; only replace explicit build-owned generated targets.
    for name, data in outputs.items():
        path = output / name
        if path.is_symlink():
            raise ValueError("Symlink artifact")
    output.mkdir(parents=True, exist_ok=True)
    for name, data in outputs.items():
        path = output / name
        if not path.exists() or path.read_bytes() != data:
            with path.open("wb") as f:
                f.write(data)
    return catalog


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--images-manifest", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    result = build(ROOT, args.images_manifest, args.output_dir)
    print(f"Built {len(result['sets'])} canonical 3.0 archives; native codec/media review still required")
