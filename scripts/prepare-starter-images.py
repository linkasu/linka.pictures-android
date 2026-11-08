#!/usr/bin/env python3
"""Offline stdlib crops from CSV plus source PNG acquisition receipts."""
import argparse
import csv
import hashlib
import importlib.util
from pathlib import Path
import sys

sys.dont_write_bytecode = True
import starter_png as png

spec = importlib.util.spec_from_file_location("starter_packer", Path(__file__).with_name("pack-starter-sets.py"))
packer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(packer)
ROOT = Path(__file__).resolve().parents[1]
BINDING_FIELDS = ["file", "rows", "columns", "inset_pixels", "keys", "reference_files"]


def layout_hash(row):
    return hashlib.sha256(packer.json_bytes({key: row[key] for key in BINDING_FIELDS})).hexdigest()


def read_sheets(path):
    if path.is_symlink() or path.stat().st_size > 1024 * 1024:
        raise ValueError("Sheet CSV too large")
    with path.open(newline="", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        if reader.fieldnames != [*BINDING_FIELDS, "prompt_status", "prompt"]:
            raise ValueError("Unexpected sheet CSV header")
        rows = list(reader)
    if len(rows) != 5 or len({r["file"] for r in rows}) != 5:
        raise ValueError("Expected 5 unique source sheets")
    for row in rows:
        if (None in row or any(not isinstance(v, str) for v in row.values())
                or not row["prompt"] or len(row["prompt"]) > 32000
                or row["prompt_status"] not in {"exact_historical", "editable_regeneration"}
                or any(not row[k].isascii() or not row[k].isdigit() for k in ["rows", "columns", "inset_pixels"])):
            raise ValueError("Invalid sheet CSV row")
    return rows


def write_generated(path, data):
    if path.is_symlink():
        raise ValueError("Symlink output")
    if not path.exists() or path.read_bytes() != data:
        with path.open("wb") as f:
            f.write(data)


def prepare(output_dir, *, root=ROOT):
    root, output_dir = root.resolve(), Path(output_dir)
    packer.check_output(output_dir, root)
    base = root / "assets/starter-pack"
    if not base.resolve().is_relative_to(root) or (base / "previews").is_symlink():
        raise ValueError("Unsafe source directory")
    receipt_path = base / "source-manifest.json"
    receipt = packer.load_json(receipt_path)
    if (receipt.get("schema") != "linka-starter-sources-v1" or receipt.get("technicalReview") != "approved"
            or receipt.get("approvalActor") != "primary_assistant" or len(receipt.get("sheets", [])) != 5):
        raise ValueError("Invalid/unreviewed source receipt")
    sheets = read_sheets(base / "sheets.csv")
    acquired = {r["file"]: r for r in receipt["sheets"]}
    if set(acquired) != {r["file"] for r in sheets} or len(acquired) != 5:
        raise ValueError("Sheet CSV/receipt file mismatch")
    manifest_path = root / "docs/development/starter-pack-manifest.json"
    rows = packer.audio.requests(manifest_path.with_name("starter-pack-recordings.csv"),
                                packer.load_json(manifest_path), base / "audio")
    by_key = {row["key"]: row for row in rows if row["cell_kind"] == "CONTENT"}
    keys = [key for sheet in sheets for key in sheet["keys"].split(";") if key]
    if len(keys) != len(set(keys)) or set(keys) != set(by_key) or len(keys) != 41:
        raise ValueError("Sheet CSV must cover exactly 41 approved core keys")
    sources = {}
    for sheet in sheets:
        record = acquired[sheet["file"]]
        prompt_sha = hashlib.sha256(sheet["prompt"].encode("utf-8")).hexdigest()
        if prompt_sha != record.get("promptSha256") or layout_hash(sheet) != record.get("layoutSha256"):
            raise ValueError("Sheet CSV prompt/layout differs from acquisition receipt; regenerate/review explicitly")
        refs = sheet["reference_files"].split(";") if sheet["reference_files"] else []
        if any(not ref.startswith("previews/") or ref[9:] not in acquired for ref in refs):
            raise ValueError("Unsafe/missing sheet reference")
        if {ref: acquired[ref[9:]]["sha256"] for ref in refs} != record.get("referenceSha256"):
            raise ValueError("Sheet CSV reference differs from acquisition receipt")
        _, sources[sheet["file"]] = packer.checked_file(base / "previews", record, ".png")
    manifest = {"schema": "linka-starter-images-v1", "purpose": "production", "status": "approved",
        "approvalActor": receipt["approvalActor"], "approvalScope": receipt["approvalScope"],
        "humanVisualApproval": receipt["humanVisualApproval"], "clinicalApproval": False, "usabilityApproval": False,
        "source": receipt["source"], "modelId": receipt["modelId"], "license": receipt["license"],
        "externalArtworkUsed": False, "cropTool": "Python stdlib RGB/RGBA8 unfilter 0-4 + filter0 zlib9 encode",
        "sourceReceiptSha256": hashlib.sha256(receipt_path.read_bytes()).hexdigest(),
        "sheetsCsvSha256": hashlib.sha256((base / "sheets.csv").read_bytes()).hexdigest(),
        "rescaled": False, "sources": receipt["sheets"], "images": {}}
    outputs = {}
    for sheet in sheets:
        record = acquired[sheet["file"]]
        decoded = png.decode(sources[sheet["file"]])
        width, height = decoded[:2]
        grid_rows, columns, inset = (int(sheet[k]) for k in ["rows", "columns", "inset_pixels"])
        cell_keys = sheet["keys"].split(";")
        if (not 0 < grid_rows <= 64 or not 0 < columns <= 64 or not 0 <= inset <= 64
                or (width, height) != (record["width"], record["height"])
                or width % columns or height % grid_rows or len(cell_keys) != grid_rows * columns):
            raise ValueError("Source PNG dimensions/grid mismatch")
        crop_width, crop_height = width // columns - 2 * inset, height // grid_rows - 2 * inset
        if min(crop_width, crop_height) < 2:
            raise ValueError("Crop smaller than inset")
        for position, key in enumerate(cell_keys):
            if not key:
                continue
            rect = [(position % columns) * (width // columns) + inset,
                    (position // columns) * (height // grid_rows) + inset, crop_width, crop_height]
            result = png.crop(decoded, *rect)
            filename = by_key[key]["expected_filename"].removesuffix(".mp3") + ".png"
            outputs[filename] = result
            manifest["images"][key] = {"file": filename, "size": len(result), "sha256": hashlib.sha256(result).hexdigest(),
                "label": by_key[key]["exact_phrase"], "sourceFile": "assets/starter-pack/previews/" + sheet["file"],
                "sourceSha256": record["sha256"], "sourceCell": position + 1, "cropRect": rect,
                "acquisitionPromptSha256": record["promptSha256"],
                "provenance": "User-authorized Codex source; primary technical review; CSV/acquisition-bound stdlib pixel-exact crop without rescale",
                "visualApproval": "approved", "approvalActor": receipt["approvalActor"],
                "humanVisualApproval": receipt["humanVisualApproval"], "approvalScope": receipt["approvalScope"], "license": None}
    outputs["manifest.json"] = packer.json_bytes(manifest)
    if any((output_dir / filename).is_symlink() for filename in outputs):
        raise ValueError("Symlink generated image")
    output_dir.mkdir(parents=True, exist_ok=True)
    for filename, data in outputs.items():
        write_generated(output_dir / filename, data)
    return manifest


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    print(f"Prepared {len(prepare(args.output_dir)['images'])} build-owned PNG crops")
