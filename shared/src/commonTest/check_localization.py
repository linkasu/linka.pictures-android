"""Run with python3 -B shared/src/commonTest/check_localization.py; no native UI runtime needed."""

from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET


class LocalizationResourcesTest(unittest.TestCase):
    def test_english_and_russian_have_matching_keys_and_placeholders(self):
        main = Path(__file__).resolve().parent.parent / "commonMain"
        resources = main / "composeResources"
        translations = []
        for locale in ("values", "values-ru"):
            strings = [node for file in sorted((resources / locale).glob("*.xml"))
                       for node in ET.parse(file).getroot() if node.tag == "string"]
            entries = {node.attrib["name"]: node.text or "" for node in strings}
            self.assertEqual(len(strings), len(entries), f"Duplicate key in {locale}")
            translations.append(entries)
        english, russian = translations
        self.assertEqual(english.keys(), russian.keys())
        for name in english:
            with self.subTest(resource=name):
                self.assertTrue(english[name].strip())
                self.assertTrue(russian[name].strip())
                self.assertNotEqual(english[name], russian[name])
                self.assertIsNone(re.search("[А-Яа-яЁё]", english[name]))
                self.assertEqual(
                    sorted(re.findall(r"%\d+\$[sd]", english[name])),
                    sorted(re.findall(r"%\d+\$[sd]", russian[name])),
                )
        ui = main / "kotlin/su/linka/pictures/ui"
        used = set()
        for source in ui.rglob("*.kt"):
            text = source.read_text()
            self.assertIsNone(re.search("[А-Яа-яЁё]", text), str(source))
            self.assertNotIn("AuthoringAction.NewBoard()", text, str(source))
            used.update(re.findall(r"Res\.string\.(\w+)", text))
        self.assertTrue(used <= set(english))
        self.assertEqual({key for key in english if not key.startswith(("startup_", "bootstrap_", "ios_"))}, used)
        self.assertEqual("New board", english["new_board"])
        self.assertEqual("Новая доска", russian["new_board"])
        native_used = set()
        for platform, filenames in (
            ("androidMain", ("AndroidProductRuntime.kt", "AndroidStartupScreen.kt")),
            ("iosMain", ("IosProductRoot.kt", "MainViewController.kt")),
        ):
            for filename in filenames:
                source = main.parent / platform / "kotlin/su/linka/pictures/bootstrap" / filename
                text = source.read_text()
                self.assertIsNone(re.search("[А-Яа-яЁё]", text), str(source))
                native_used.update(re.findall(r"Res\.string\.(\w+)", text))
        self.assertTrue(native_used <= set(english))
        self.assertEqual({key for key in english if key.startswith(("startup_", "bootstrap_", "ios_"))}, native_used)


if __name__ == "__main__":
    unittest.main()
