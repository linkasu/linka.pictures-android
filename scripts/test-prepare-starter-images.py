import csv
import importlib.util
from pathlib import Path
import shutil
import tempfile
import unittest
import json
import zipfile
from unittest.mock import patch
import starter_png as png

spec = importlib.util.spec_from_file_location("resources", Path(__file__).with_name("build-starter-resources.py"))
resources = importlib.util.module_from_spec(spec)
spec.loader.exec_module(resources)
images = resources.images


class ImagesTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.base = self.root / "assets/starter-pack"
        self.docs = self.root / "docs/development"
        self.docs.mkdir(parents=True)
        for filename in ["starter-pack-manifest.json", "starter-pack-recordings.csv"]:
            shutil.copyfile(resources.ROOT / "docs/development" / filename, self.docs / filename)
        shutil.copytree(resources.ROOT / "assets/starter-pack/audio", self.base / "audio")
        (self.base / "previews").mkdir()
        self.receipt = images.packer.load_json(resources.ROOT / "assets/starter-pack/source-manifest.json")
        self.receipt["source"] = "synthetic unittest PNG only; never shipping"
        self.sheets = images.read_sheets(resources.ROOT / "assets/starter-pack/sheets.csv")
        for record, sheet in zip(self.receipt["sheets"], self.sheets, strict=True):
            width, height = int(sheet["columns"]) * 32, int(sheet["rows"]) * 32
            data = png.encode(width, height, 3, bytes((i * 37) % 256 for i in range(width * height * 3)))
            (self.base / "previews" / sheet["file"]).write_bytes(data)
            record.update(width=width, height=height, size=len(data), sha256=images.hashlib.sha256(data).hexdigest())
        self.save_csv()
        self.refresh_receipt()
        self.output = self.root / "build/starter-resources"

    def save_csv(self):
        with (self.base / "sheets.csv").open("w", newline="", encoding="utf-8") as f:
            writer = csv.DictWriter(f, fieldnames=[*images.BINDING_FIELDS, "prompt_status", "prompt"], lineterminator="\n")
            writer.writeheader()
            writer.writerows(self.sheets)

    def refresh_receipt(self):
        records = {r["file"]: r for r in self.receipt["sheets"]}
        for sheet in self.sheets:
            record = records[sheet["file"]]
            record["layoutSha256"] = images.layout_hash(sheet)
            record["promptSha256"] = images.hashlib.sha256(sheet["prompt"].encode()).hexdigest()
            record["referenceSha256"] = {ref: records[ref[9:]]["sha256"] for ref in sheet["reference_files"].split(";") if ref}
        (self.base / "source-manifest.json").write_bytes(images.packer.json_bytes(self.receipt))

    def test_offline_compose_layout_idempotence_and_incremental_source(self):
        before = {p.relative_to(self.root): p.read_bytes() for p in self.root.rglob("*") if p.is_file()}
        with patch("subprocess.run", side_effect=AssertionError("No ffmpeg/process allowed")), \
                patch("shutil.which", side_effect=AssertionError("No external tool lookup")), \
                patch("urllib.request.OpenerDirector.open", side_effect=AssertionError("No network")):
            catalog = resources.build_resources(self.output, root=self.root)
            outputs = {p.relative_to(self.output): p.read_bytes() for p in self.output.rglob("*") if p.is_file()}
            self.assertEqual({p.name for p in self.output.iterdir()}, {"files"})
            self.assertEqual(len(catalog["sets"]), 5)
            self.assertEqual(sum(b["slots"] for b in catalog["sets"]), 90)
            self.assertEqual(catalog, resources.build_resources(self.output, root=self.root))
            self.assertEqual(outputs, {p.relative_to(self.output): p.read_bytes() for p in self.output.rglob("*") if p.is_file()})
            other = self.root / "build/another-output"
            resources.build_resources(other, root=self.root)
            self.assertEqual(outputs, {p.relative_to(other): p.read_bytes() for p in other.rglob("*") if p.is_file()})
            for path, data in before.items():
                self.assertEqual((self.root / path).read_bytes(), data)
            self.assertFalse((self.base / "images").exists())
            self.assertFalse((self.base / "archives").exists())
            self.sheets[0]["inset_pixels"] = "7"
            self.save_csv()
            with self.assertRaisesRegex(ValueError, "differs from acquisition"):
                resources.build_resources(self.output, root=self.root)
            self.refresh_receipt()  # Synthetic fixture review; no production receipt changes.
            changed = resources.build_resources(self.output, root=self.root)
            self.assertNotEqual(catalog["sets"][0]["sha256"], changed["sets"][0]["sha256"])

    def test_unsafe_output_and_wrong_source_hash(self):
        with self.assertRaisesRegex(ValueError, "under build"):
            resources.build_resources(self.base / "images", root=self.root)
        self.receipt["sheets"][0]["sha256"] = "0" * 64
        (self.base / "source-manifest.json").write_bytes(images.packer.json_bytes(self.receipt))
        with self.assertRaisesRegex(ValueError, "hash mismatch"):
            resources.build_resources(self.output, root=self.root)
        self.assertFalse(self.output.exists())
        link = self.root / "build/link"
        link.parent.mkdir(parents=True)
        link.symlink_to(self.base, target_is_directory=True)
        with self.assertRaises(ValueError):
            resources.build_resources(link, root=self.root)

    def test_prompt_edit_never_claims_existing_png_matches(self):
        self.sheets[0]["prompt"] += " Modified request."
        self.sheets[0]["prompt_status"] = "editable_regeneration"
        self.save_csv()
        with self.assertRaisesRegex(ValueError, "differs from acquisition"):
            resources.build_resources(self.output, root=self.root)
        self.assertFalse(self.output.exists())

    def test_acquisition_evidence_required_without_decode(self):
        path = self.base / "audio/manifest.json"
        original = images.packer.load_json(path)
        for field, value in [("strictDecode", "pending"), ("nonSilent", False)]:
            index = images.packer.load_json(path)
            index["records"]["ru.yes"]["validation"] = dict(original["records"]["ru.yes"]["validation"])
            index["records"]["ru.yes"]["validation"][field] = value
            path.write_bytes(images.packer.json_bytes(index))
            with self.assertRaisesRegex(ValueError, "acquisition evidence"):
                resources.build_resources(self.output, root=self.root)
        self.assertFalse((self.output / "files/starter").exists())

    def test_csv_layout_edit_reuses_recordings_but_speech_edit_requires_receipt(self):
        path = self.docs / "starter-pack-recordings.csv"
        with path.open(newline="", encoding="utf-8") as f:
            reader = csv.DictReader(f)
            fields, cards = reader.fieldnames, list(reader)
        cards[0]["used_in"], cards[1]["used_in"] = cards[1]["used_in"], cards[0]["used_in"]
        def save():
            with path.open("w", newline="", encoding="utf-8") as f:
                writer = csv.DictWriter(f, fieldnames=fields, lineterminator="\n")
                writer.writeheader()
                writer.writerows(cards)
        save()
        self.assertTrue(all("pages" not in b for b in images.packer.load_json(self.docs / "starter-pack-manifest.json")["proposal"]))
        resources.build_resources(self.output, root=self.root)
        with zipfile.ZipFile(self.output / "files/starter/ru.core.v1.linka") as archive:
            first = json.loads(archive.read("config.json"))["pages"][0]["cards"]
            self.assertEqual([c["title"] for c in first[:2]], ["Нет", "Да"])
        cards[0]["speech_text"] = "Changed speech requires new acquisition evidence"
        save()
        with self.assertRaisesRegex(ValueError, "acquisition receipt"):
            resources.build_resources(self.output, root=self.root)


if __name__ == "__main__":
    unittest.main()
