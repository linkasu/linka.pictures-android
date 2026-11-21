"""Offline regression checks; no SDK, build, network or image dependencies."""
import importlib.util
from pathlib import Path
import shutil
import struct
import tempfile
import unittest
from unittest.mock import patch
import zipfile
import zlib

SPEC = importlib.util.spec_from_file_location("launcher", Path(__file__).with_name("check-android-launcher.py"))
launcher = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(launcher)


def png(width, height, color, depth, raster, palette=b"", transparency=b""):
    def chunk(kind, payload):
        return (struct.pack(">I", len(payload)) + kind + payload +
                struct.pack(">I", zlib.crc32(kind + payload) & 0xffffffff))
    return (b"\x89PNG\r\n\x1a\n" +
            chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, depth, color, 0, 0, 0)) +
            (chunk(b"PLTE", palette) if palette else b"") +
            (chunk(b"tRNS", transparency) if transparency else b"") +
            chunk(b"IDAT", zlib.compress(raster)) + chunk(b"IEND", b""))


class LauncherTests(unittest.TestCase):
    def test_png_crunch_encodings_and_transparent_rgb(self):
        expected = (2, 1, bytes((10, 20, 30, 255, 0, 0, 0, 0)))
        self.assertEqual(launcher.rgba(png(2, 1, 6, 8, b"\0\x0a\x14\x1e\xff\xff\xff\xff\0")), expected)
        for depth, indices in ((1, b"\x40"), (2, b"\x10"), (4, b"\x01"), (8, b"\x00\x01")):
            with self.subTest(depth=depth):
                self.assertEqual(launcher.rgba(png(2, 1, 3, depth, b"\0" + indices,
                                                  b"\x0a\x14\x1e\xff\xff\xff", b"\xff\0")), expected)
        self.assertEqual(launcher.rgba(png(1, 1, 2, 8, b"\0\x0a\x14\x1e")),
                         (1, 1, b"\x0a\x14\x1e\xff"))
        self.assertEqual(launcher.rgba(png(2, 1, 0, 1, b"\0\x40", transparency=b"\0\1")),
                         (2, 1, b"\0\0\0\xff\0\0\0\0"))
        self.assertEqual(launcher.rgba(png(1, 1, 4, 8, b"\0\x80\x7f")),
                         (1, 1, b"\x80\x80\x80\x7f"))
        self.assertEqual(launcher.rgba(png(1, 1, 2, 8, b"\0\x0a\x14\x1e",
                                         transparency=struct.pack(">HHH", 10, 20, 30))),
                         (1, 1, b"\0\0\0\0"))
        damaged = bytearray(png(1, 1, 0, 8, b"\0\0"))
        damaged[-1] ^= 1
        with self.assertRaisesRegex(ValueError, "CRC"):
            launcher.rgba(damaged)

    def test_all_png_filters(self):
        # Same two pixels on two rows, independently encode filters 0..4.
        row = bytes((10, 20, 30, 40, 50, 60, 70, 80))
        for mode in range(5):
            previous, raw = bytes(8), bytearray()
            for _ in range(2):
                raw.append(mode)
                for x, value in enumerate(row):
                    left = row[x - 4] if x >= 4 else 0
                    above = previous[x]
                    corner = previous[x - 4] if x >= 4 else 0
                    estimate = left + above - corner
                    distances = (abs(estimate - left), abs(estimate - above), abs(estimate - corner))
                    paeth = (left, above, corner)[distances.index(min(distances))]
                    predictor = (0, left, above, (left + above) // 2, paeth)[mode]
                    raw.append((value - predictor) & 255)
                previous = row
            with self.subTest(filter=mode):
                self.assertEqual(launcher.rgba(png(2, 2, 6, 8, raw)), (2, 2, row * 2))

    def test_approved_source_and_regressions(self):
        launcher.check_source(launcher.ROOT)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            main = root / "app/src/main"
            shutil.copytree(launcher.ROOT / "app/src/main/res", main / "res")
            for name in ("AndroidManifest.xml", "ic_launcher-playstore.png"):
                shutil.copyfile(launcher.ROOT / "app/src/main" / name, main / name)
            targets = {
                "missing-density": main / "res/mipmap-mdpi/ic_launcher.png",
                "wrong-pixels": main / "res/mipmap-hdpi/ic_launcher_round.png",
                "wrong-adaptive-reference": main / "res/mipmap-anydpi-v26/ic_launcher.xml",
                "wrong-background": main / "res/values/ic_launcher_background.xml",
                "wrong-store-image": main / "ic_launcher-playstore.png",
            }
            for case, target in targets.items():
                original = target.read_bytes()
                if case == "missing-density":
                    target.unlink()
                elif case == "wrong-adaptive-reference":
                    target.write_bytes(original.replace(b"@mipmap/", b"@drawable/"))
                else:
                    target.write_bytes(original.replace(b"7A662E", b"000000") if case == "wrong-background"
                                       else png(1, 1, 6, 8, b"\0\0\0\0\xff"))
                with self.subTest(case=case), self.assertRaises((ValueError, FileNotFoundError)):
                    launcher.check_source(root)
                target.write_bytes(original)
            override = root / "app/src/debug/res/mipmap-mdpi/ic_launcher.png"
            override.parent.mkdir(parents=True)
            override.write_bytes(b"debug override")
            with self.assertRaisesRegex(ValueError, "override"):
                launcher.check_source(root)
            override.unlink()
            template = main / "res/mipmap-mdpi/ic_launcher.webp"
            template.write_bytes(b"old template")
            with self.assertRaisesRegex(ValueError, "Unexpected"):
                launcher.check_source(root)

    def test_packaged_pixels_references_and_manifest(self):
        images = launcher.check_source(launcher.ROOT)
        entries, ids, files = {}, {}, {}
        for number, name in enumerate(launcher.NAMES, 1):
            key = f"mipmap/{name}"
            ids[key] = f"0x7f08000{number}"
            for density in launcher.APPROVED:
                path = f"res/mipmap-{density}-v4/{name}.png"
                entries[key, density] = path
                files[path] = (launcher.ROOT / f"app/src/main/res/mipmap-{density}/{name}.png").read_bytes()
            if name != launcher.NAMES[2]:
                entries[key, "anydpi-v26"] = f"res/mipmap-anydpi-v26/{name}.xml"
        ids["color/ic_launcher_background"] = "0x7f010001"
        entries["color/ic_launcher_background", ""] = ("0x1d", "0xff7a662e")

        def table():
            lines = []
            for (name, config), value in entries.items():
                kind, data = ("0x03", "0x00000000") if isinstance(value, str) else value
                lines += [f"config {config}:",
                          f"resource {ids[name]} su.linka.pictures:{name}: t={kind} d={data} (s=0 r=0)"]
                if isinstance(value, str):
                    lines.append(f'(string8) "{value}"')
            return "\n".join(lines)

        manifest = ("E: application\n"
                    f'A: android:icon(0x01010002)=@{ids["mipmap/ic_launcher"]}\n'
                    f'A: android:roundIcon(0x0101052c)=@{ids["mipmap/ic_launcher_round"]}\n')
        adaptive = ("E: adaptive-icon\nE: background\n"
                    f'A: android:drawable(0x01010199)=@{ids["color/ic_launcher_background"]}\n'
                    "E: foreground\n"
                    f'A: android:drawable(0x01010199)=@{ids["mipmap/ic_launcher_foreground"]}\n')

        def dump(args, **kwargs):
            if "resources" in args:
                return table()
            return manifest if args[-1] == "AndroidManifest.xml" else adaptive

        with tempfile.TemporaryDirectory() as temporary:
            apk = Path(temporary) / "universal.apk"

            def write_apk():
                with zipfile.ZipFile(apk, "w") as archive:
                    for name, data in files.items():
                        archive.writestr(name, data)

            write_apk()
            with patch.object(launcher.subprocess, "check_output", side_effect=dump):
                launcher.check_apk(apk, "aapt", images)
                manifest = manifest.replace(ids["mipmap/ic_launcher"], "0x7f080099")
                manifest += f'E: activity\nA: android:icon(0x01010002)=@{ids["mipmap/ic_launcher"]}\n'
                with self.assertRaisesRegex(ValueError, "manifest"):
                    launcher.check_apk(apk, "aapt", images)
                manifest = manifest.replace("0x7f080099", ids["mipmap/ic_launcher"])
                adaptive = adaptive.replace(ids["mipmap/ic_launcher_foreground"], "0x7f080099")
                with self.assertRaisesRegex(ValueError, "adaptive references"):
                    launcher.check_apk(apk, "aapt", images)
                adaptive = adaptive.replace("0x7f080099", ids["mipmap/ic_launcher_foreground"])
                entries["color/ic_launcher_background", ""] = ("0x1d", "0xff000000")
                with self.assertRaisesRegex(ValueError, "background"):
                    launcher.check_apk(apk, "aapt", images)
                entries["color/ic_launcher_background", ""] = ("0x1d", "0xff7a662e")
                files[entries["mipmap/ic_launcher", "mdpi"]] = png(1, 1, 6, 8, b"\0\0\0\0\xff")
                write_apk()
                with self.assertRaisesRegex(ValueError, "pixels differ"):
                    launcher.check_apk(apk, "aapt", images)
                entries["mipmap/ic_launcher", "night-mdpi"] = "other.png"
                with self.assertRaisesRegex(ValueError, "configurations"):
                    launcher.check_apk(apk, "aapt", images)


if __name__ == "__main__":
    unittest.main()
