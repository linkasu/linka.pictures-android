"""Device-free checks for the process-death smoke's UI and lifecycle parsers."""
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

from process_death import PACKAGE, bounds, clickable_bounds, grid_stopped, keyboard_titles


class ProcessDeathParsers(unittest.TestCase):
    def test_clickable_parent_for_text_and_content_description(self):
        for attribute in ("text", "content-desc"):
            root = ET.fromstring(f'''<hierarchy><node package="{PACKAGE}" clickable="true"
                enabled="true" bounds="[10,20][110,120]">
                <node package="{PACKAGE}" {attribute}="ё" clickable="false"
                    enabled="true" bounds="[20,30][40,50]"/></node></hierarchy>''')
            self.assertEqual(clickable_bounds(root, "ё"), (10, 20, 110, 120))
            self.assertIsNone(clickable_bounds(root, "missing"))

    def test_invalid_bounds_and_disabled_target_are_not_tapped(self):
        for value in ("", "[0,0][0,0]", "[2,0][1,3]", "[-1,0][2,3]", "0,0,2,3"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                bounds(value)
        root = ET.fromstring(f'''<hierarchy><node package="{PACKAGE}" text="ё"
            clickable="true" enabled="false" bounds="[0,0][100,100]"/></hierarchy>''')
        self.assertIsNone(clickable_bounds(root, "ё"))

    def test_grid_must_be_stopped_not_just_another_activity(self):
        grid = f"  * Hist #1: ActivityRecord{{123 u0 {PACKAGE}/.activity.GridActivity t2}}\n"
        other = f"  * Hist #0: ActivityRecord{{124 u0 {PACKAGE}/.activity.MainActivity t2}}\n"
        self.assertTrue(grid_stopped("\n\n" + grid + "mState=STOPPED stopped=true\n" + other))
        self.assertTrue(grid_stopped(grid + "state=STOPPED stopped=true\n"))
        self.assertFalse(grid_stopped(grid + "state=RESUMED stopped=false\n" + other +
                                      "state=STOPPED stopped=true\n"))

    def test_actual_fixture_contains_safe_unique_ordinary_titles(self):
        path = Path(__file__).resolve().parents[2] / "app/src/main/assets/sets/Клавиатура.linka"
        titles = keyboard_titles(path)
        self.assertGreaterEqual(len(titles), 2)
        self.assertEqual(len(titles), len(set(titles)))
        self.assertNotIn("␣", titles)


if __name__ == "__main__":
    unittest.main()
