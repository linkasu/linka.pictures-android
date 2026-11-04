"""Launcher/task restoration smoke for a fresh CI emulator; never use on user devices."""
from collections import Counter
import json
import os
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET
import zipfile


PACKAGE = "su.linka.pictures"
MAIN = PACKAGE + "/.activity.MainActivity"
GRID = PACKAGE + "/.activity.GridActivity"


def bounds(value):
    match = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", value)
    if not match:
        raise ValueError("Invalid UI bounds: " + value)
    left, top, right, bottom = map(int, match.groups())
    if right <= left or bottom <= top:
        raise ValueError("Empty or inverted UI bounds: " + value)
    return left, top, right, bottom


def labels(node):
    return {node.get(key, "").strip() for key in ("text", "content-desc")} - {""}


def clickable_bounds(root, title):
    parents = {child: parent for parent in root.iter() for child in parent}
    targets = set()
    for node in root.iter("node"):
        if node.get("package") != PACKAGE or title not in labels(node):
            continue
        while node is not None:
            if node.get("enabled") == "false":
                break
            if node.get("clickable") == "true":
                targets.add(bounds(node.get("bounds", "")))
                break
            node = parents.get(node)
    if len(targets) > 1:
        raise RuntimeError("Ambiguous clickable UI label: " + title)
    return next(iter(targets), None)


def has_label(root, title):
    return any(node.get("package") == PACKAGE and title in labels(node)
               for node in root.iter("node"))


def keyboard_titles(path):
    with zipfile.ZipFile(path) as archive:
        config = json.loads(archive.read("config.json"))
    if config.get("withoutSpace") is not True:
        raise RuntimeError("Keyboard fixture must concatenate without spaces")
    counts = Counter(card.get("title") for card in config["cards"])
    return [card["title"] for card in config["cards"]
            if card.get("cardType") == 0 and card.get("title", "").strip()
            and counts[card["title"]] == 1 and not card.get("audioPath")]


def grid_stopped(output):
    records = re.split(r"(?m)(?=^[ \t]*\*\s+Hist\s+#\d+:)", output)[1:]
    return any(GRID in record.splitlines()[0]
               and re.search(r"\b(?:mState|state)=STOPPED\b", record)
               and re.search(r"\b(?:stopped|mAppStopped)=true\b", record) for record in records)


class Smoke:
    def __init__(self, serial):
        self.serial = serial
        self.deadline = time.monotonic() + 240
        self.xml = f"/data/local/tmp/linka-process-death-{os.getpid()}.xml"

    def adb(self, *args, deadline=None, check=True):
        remaining = min(deadline or self.deadline, self.deadline) - time.monotonic()
        if remaining <= 0:
            raise TimeoutError("Process-death smoke deadline exceeded")
        return subprocess.run(["adb", "-s", self.serial, *args], text=True,
                              capture_output=True, check=check, timeout=min(20, remaining))

    def wait(self, description, probe, seconds=40):
        end = min(self.deadline, time.monotonic() + seconds)
        while time.monotonic() < end:
            result = probe(end)
            if result:
                return result
            remaining = end - time.monotonic()
            if remaining > 0:
                time.sleep(min(0.25, remaining))
        raise TimeoutError("Timed out waiting for " + description)

    def hierarchy(self, deadline):
        result = self.adb("shell", "uiautomator", "dump", self.xml, deadline=deadline)
        if "dumped to:" not in result.stdout:
            return None
        return ET.fromstring(self.adb("shell", "cat", self.xml, deadline=deadline).stdout)

    def tap(self, rectangle, deadline):
        left, top, right, bottom = rectangle
        self.adb("shell", "input", "tap", str((left + right) // 2),
                 str((top + bottom) // 2), deadline=deadline)

    def scroll(self, root, forward, deadline):
        for node in root.iter("node"):
            if node.get("package") == PACKAGE and node.get("scrollable") == "true":
                left, top, right, bottom = bounds(node.get("bounds", ""))
                x = str((left + right) // 2)
                upper, lower = top + (bottom - top) // 4, bottom - (bottom - top) // 4
                start, finish = (lower, upper) if forward else (upper, lower)
                self.adb("shell", "input", "swipe", x, str(start), x, str(finish),
                         "300", deadline=deadline)
                return

    def find(self, title, forward=True):
        def probe(end):
            root = self.hierarchy(end)
            if root is None:
                return None
            target = clickable_bounds(root, title)
            if target is None:
                self.scroll(root, forward, end)
            return target
        return self.wait("clickable " + title, probe)

    def launch(self):
        self.adb("shell", "am", "start", "-W", "-a", "android.intent.action.MAIN",
                 "-c", "android.intent.category.LAUNCHER", "-n", MAIN,
                 "-f", "0x10200000")  # NEW_TASK | RESET_TASK_IF_NEEDED, not CLEAR_TASK.

    def pid(self, deadline):
        result = self.adb("shell", "pidof", PACKAGE, deadline=deadline, check=False)
        if result.returncode not in (0, 1):
            raise RuntimeError("Cannot inspect app PID: " + result.stderr)
        pid = result.stdout.strip()
        if pid and not re.fullmatch(r"[1-9][0-9]*", pid):
            raise RuntimeError("Expected exactly one positive app PID")
        return pid

    def phrase(self, text):
        def probe(end):
            root = self.hierarchy(end)
            if root is None:
                return False
            if has_label(root, text):
                return True
            self.scroll(root, False, end)
            return False
        self.wait("exact keyboard phrase " + repr(text), probe)

    def run(self, titles):
        self.launch()

        def privacy_or_library(end):
            root = self.hierarchy(end)
            if root is None:
                return False
            for node in root.iter("node"):
                for title in labels(node):
                    if title.casefold() == "not now":
                        target = clickable_bounds(root, title)
                        if target:
                            self.tap(target, end)
                            return False
            return any(clickable_bounds(root, title) for title in ("Settings", "Настройки"))

        self.wait("library with privacy declined (never opted in)", privacy_or_library)
        self.tap(self.find("Клавиатура"), self.deadline)

        def output_mode(end):
            root = self.hierarchy(end)
            # Direct mode would activate audio on selection; never select until output is confirmed.
            return root is not None and any(has_label(root, title) for title in ("Phrase", "Фраза"))

        self.wait("keyboard output mode (default settings 3)", output_mode)

        def visible_cards(end):
            root = self.hierarchy(end)
            if root is None:
                return None
            visible = [title for title in titles if clickable_bounds(root, title)]
            if len(visible) >= 2:
                return visible[:2]
            self.scroll(root, True, end)
            return None

        first, second = self.wait("two visible ordinary keyboard cards in output mode", visible_cards)
        for title in (first, second):
            self.tap(self.find(title), self.deadline)
        phrase = first + second
        self.phrase(phrase)
        self.adb("shell", "input", "keyevent", "KEYCODE_HOME")
        self.wait("GridActivity STOPPED after HOME", lambda end: grid_stopped(
            self.adb("shell", "dumpsys", "activity", "activities", deadline=end).stdout))
        old_pid = self.wait("background app PID", self.pid)
        # am kill is best-effort for cached processes; SIGKILL models abrupt death without force-stop.
        self.adb("shell", "run-as", PACKAGE, "kill", "-9", old_pid)
        self.wait("debug app PID disappearance after SIGKILL", lambda end: not self.pid(end))
        self.launch()
        new_pid = self.wait("restored app PID", self.pid)
        if new_pid == old_pid:
            raise RuntimeError("App process did not change")
        self.phrase(phrase)
        print(f"Process-death smoke passed: PID {old_pid} -> {new_pid}, phrase {phrase!r}")


def main():
    if os.environ.get("GITHUB_ACTIONS") != "true":
        raise RuntimeError("This smoke is restricted to a fresh GitHub Actions emulator")
    result = subprocess.run(["adb", "devices"], text=True, capture_output=True,
                            check=True, timeout=10)
    devices = [line.split() for line in result.stdout.splitlines()[1:] if line.strip()]
    if len(devices) != 1 or devices[0][1:] != ["device"] or not devices[0][0].startswith("emulator-"):
        raise RuntimeError("Exactly one connected CI emulator is required")
    fixture = Path(__file__).resolve().parents[2] / "app/src/main/assets/sets/Клавиатура.linka"
    smoke = Smoke(devices[0][0])
    try:
        smoke.run(keyboard_titles(fixture))
    finally:
        subprocess.run(["adb", "-s", smoke.serial, "shell", "rm", "-f", smoke.xml],
                       capture_output=True, timeout=5, check=False)


if __name__ == "__main__":
    main()
