#!/usr/bin/env python3
"""Offline build entry: PNG/CSV/MP3 -> Compose files/starter; sibling scratch only."""
import argparse
import importlib.util
from pathlib import Path
import sys

sys.dont_write_bytecode = True

spec = importlib.util.spec_from_file_location("starter_images", Path(__file__).with_name("prepare-starter-images.py"))
images = importlib.util.module_from_spec(spec)
spec.loader.exec_module(images)
ROOT = Path(__file__).resolve().parents[1]


def build_resources(output_dir, *, root=ROOT):
    output_dir, root = Path(output_dir), Path(root)
    images.packer.check_output(output_dir, root)
    if output_dir.exists() and any(path.name != "files" for path in output_dir.iterdir()):
        raise ValueError("Compose output directory must contain only files/; use a clean build-owned output")
    image_dir = output_dir.with_name(output_dir.name + "-work") / "images"
    archive_dir = output_dir / "files/starter"
    images.packer.check_output(image_dir, root)
    images.packer.check_output(archive_dir, root)
    images.prepare(image_dir, root=root)
    return images.packer.build(root, image_dir / "manifest.json", archive_dir)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    result = build_resources(args.output_dir)
    print(f"Built {len(result['sets'])} offline .linka3.0 resources in {args.output_dir / 'files/starter'}; not release approval")
