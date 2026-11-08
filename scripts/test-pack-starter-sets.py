import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import struct
import tempfile
import unittest
import zipfile
import zlib

spec = importlib.util.spec_from_file_location("packer", Path(__file__).with_name("pack-starter-sets.py"))
packer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(packer)


def pixel():
    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xffffffff)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 1, 1, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(b"\x00\xff\xff\xff\xff")) + chunk(b"IEND", b""))


class PackerTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = self.root / "assets/starter-pack"
        self.output = self.root / "build/starter/files/starter"
        self.images_dir = self.source / "images"
        self.images_dir.mkdir(parents=True)
        self.docs = self.root / "docs/development"
        self.docs.mkdir(parents=True)
        for filename in ["starter-pack-manifest.json", "starter-pack-recordings.csv"]:
            shutil.copyfile(packer.ROOT / "docs/development" / filename, self.docs / filename)
        shutil.copytree(packer.ROOT / "assets/starter-pack/audio", self.source / "audio")
        rows = packer.audio.requests(self.docs / "starter-pack-recordings.csv",
                                     packer.load_json(self.docs / "starter-pack-manifest.json"), self.source / "audio")
        data = pixel()
        (self.images_dir / "test-pixel.png").write_bytes(data)
        self.images = {"schema": "linka-starter-images-v1", "purpose": "synthetic-test-only", "status": "approved",
            "images": {r["key"]: {"file": "test-pixel.png", "size": len(data),
                "sha256": hashlib.sha256(data).hexdigest(), "provenance": "Synthetic unittest pixel, never shipping",
                "visualApproval": "approved"} for r in rows if not r["key"].startswith("ru.letter.")}}
        self.images_path = self.images_dir / "manifest.json"
        self.save_images()

    def save_images(self):
        self.images_path.write_bytes(packer.json_bytes(self.images))

    def test_canonical_wire_namespace_slots_and_determinism(self):
        first = packer.build(self.root, self.images_path, self.output, synthetic_test=True)
        output = self.output
        before = {p.name: p.read_bytes() for p in output.iterdir()}
        self.assertEqual(first, packer.build(self.root, self.images_path, self.output, synthetic_test=True))
        self.assertEqual(before, {p.name: p.read_bytes() for p in output.iterdir()})
        self.assertEqual(sum(s["slots"] for s in first["sets"]), 90)
        self.assertEqual(sum(s["pages"] for s in first["sets"]), 10)
        ids, locations = set(), {}
        for board in first["sets"]:
            data = (output / board["file"]).read_bytes()
            self.assertEqual(hashlib.sha256(data).hexdigest(), board["sha256"])
            with zipfile.ZipFile(output / board["file"]) as archive:
                self.assertIsNone(archive.testzip())
                names = archive.namelist()
                self.assertEqual(len(names), len(set(names)))
                self.assertEqual(names[0], "config.json")
                for name in names[1:]:
                    self.assertRegex(name, r"^media/[0-9a-f]{64}$")
                    self.assertEqual(name[6:], hashlib.sha256(archive.read(name)).hexdigest())
                for info in archive.infolist():
                    self.assertEqual(info.compress_type, zipfile.ZIP_STORED)
                    self.assertLessEqual(info.extract_version, 20)
                    self.assertEqual(info.date_time, (1980, 1, 1, 0, 0, 0))
                    self.assertEqual(info.extra, b"")
                wire = json.loads(archive.read("config.json"))
                self.assertEqual(set(wire), {"version", "withoutSpace", "directSet", "pages"})
                self.assertEqual(wire["version"], "3.0")
                letters = board["optional"]
                self.assertEqual(wire["withoutSpace"], letters)
                self.assertEqual(wire["directSet"], board["seedKey"] == "ru.core.v1")
                referenced = set()
                for page in wire["pages"]:
                    self.assertEqual((page["mode"], page["rows"], page["columns"], len(page["cards"])), ("standard", 3, 3, 9))
                    for card in page["cards"]:
                        self.assertNotIn(card["id"], ids)
                        ids.add(card["id"])
                        self.assertEqual((card["width"], card["height"]), (1, 1))
                        if letters:
                            self.assertFalse({"imagePath", "audioPath", "audioText", "audioVoice"} & card.keys())
                            self.assertIn(card["cardType"], (0, 1))
                        else:
                            self.assertEqual(card["cardType"], 0)
                            self.assertEqual(card["audioVoice"], "anton")
                            self.assertEqual(card["audioText"], card["title"])
                            for field in ["imagePath", "audioPath"]:
                                self.assertIn(card[field], names)
                                referenced.add(card[field])
                            locations.setdefault(card["title"], set()).add(card["audioPath"])
                self.assertEqual(set(names), referenced | {"config.json"})
                if letters:
                    cards = [c for p in wire["pages"] for c in p["cards"]]
                    self.assertEqual(sum(c["cardType"] == 1 for c in cards), 1)
                    self.assertEqual([c.get("title") for c in cards[-3:]], [None, ".", "?"])
                    self.assertEqual(len(names), 1)
        self.assertTrue(all(len(paths) == 1 for paths in locations.values()))

    def test_missing_image_unsafe_filename_and_hash(self):
        original = copy.deepcopy(self.images)
        for failure in ["missing_key", "unsafe", "missing_hash", "bad_hash", "missing_file", "unapproved"]:
            self.images = copy.deepcopy(original)
            record = self.images["images"]["ru.yes"]
            if failure == "missing_key":
                del self.images["images"]["ru.yes"]
            elif failure == "unsafe":
                record["file"] = "../escape.png"
            elif failure == "missing_hash":
                del record["sha256"]
            elif failure == "bad_hash":
                record["sha256"] = "0" * 64
            elif failure == "missing_file":
                record["file"] = "missing.png"
            else:
                record["visualApproval"] = "pending"
            self.save_images()
            with self.subTest(failure=failure), self.assertRaises(ValueError):
                packer.build(self.root, self.images_path, self.output, synthetic_test=True)
            self.assertFalse(self.output.exists())

    def test_png_bounds_crc_raster_and_trailing_data(self):
        self.assertEqual(packer.png_dimensions(pixel()), (1, 1))
        for data in [b"not png", pixel()[:-1], pixel() + b"junk", pixel()[:30] + b"bad" + pixel()[33:]]:
            with self.assertRaises((ValueError, zlib.error)):
                packer.png_dimensions(data)
        bad = bytearray(pixel())
        struct.pack_into(">I", bad, 16, 8193)
        struct.pack_into(">I", bad, 29, zlib.crc32(bad[12:29]) & 0xffffffff)
        with self.assertRaisesRegex(ValueError, "bounds"):
            packer.png_dimensions(bad)

    def test_mp3_provenance_and_synthetic_production_gate(self):
        index_path = self.source / "audio/manifest.json"
        index = packer.load_json(index_path)
        index["records"]["ru.help"]["request"]["method"] = "POST"
        index_path.write_bytes(packer.json_bytes(index))
        with self.assertRaisesRegex(ValueError, "pilot receipt"):
            packer.build(self.root, self.images_path, self.output, synthetic_test=True)
        self.assertFalse(self.output.exists())
        shutil.copyfile(packer.ROOT / "assets/starter-pack/audio/manifest.json", index_path)
        index = packer.load_json(index_path)
        index["records"]["ru.yes"]["voice"] = "unknown"
        index_path.write_bytes(packer.json_bytes(index))
        with self.assertRaisesRegex(ValueError, "acquisition receipt"):
            packer.build(self.root, self.images_path, self.output, synthetic_test=True)
        shutil.copyfile(packer.ROOT / "assets/starter-pack/audio/manifest.json", index_path)
        mp3 = self.source / "audio/ru-yes.mp3"
        original = mp3.read_bytes()
        mp3.write_bytes(b"x" + original[1:])
        with self.assertRaisesRegex(ValueError, "hash mismatch"):
            packer.build(self.root, self.images_path, self.output, synthetic_test=True)
        mp3.write_bytes(original)
        self.images["purpose"] = "production"
        self.save_images()
        with self.assertRaisesRegex(ValueError, "One-pixel"):
            packer.build(self.root, self.images_path, self.output)
        self.assertFalse(self.output.exists())
        with self.assertRaisesRegex(ValueError, "temporary test root"):
            packer.build(packer.ROOT, self.images_path, self.output, synthetic_test=True)

    def test_no_images_no_output_and_duplicate_json_rejected(self):
        self.images_path.unlink()
        with self.assertRaises(FileNotFoundError):
            packer.build(self.root, self.images_path, self.output)
        self.assertFalse(self.output.exists())
        self.images_path.write_text('{"images":{},"images":{}}')
        with self.assertRaisesRegex(ValueError, "Duplicate JSON"):
            packer.build(self.root, self.images_path, self.output)


if __name__ == "__main__":
    unittest.main()
