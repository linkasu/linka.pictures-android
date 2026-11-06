#!/usr/bin/env python3
"""Validate paired filled-board evidence integrity, never visual parity or approval."""
import hashlib
import json
from pathlib import Path
import re
import struct
import sys
import tempfile


FIXTURE_SHA = "a0589a029f77bfff7307d6ee3c4dbd41e1b2badd52ca90896ca5c835ccfb293b"
CARD_ORDER = ["КУШАТЬ", "ПИТЬ", "СПАТЬ", "КУПАТЬСЯ", "ХОЧУ",
              "ПИСАТЬ КАКАТЬ", "ГУЛЯТЬ", "ИГРАТЬ", "ОБНИМАТЬСЯ"]
MESSAGE = ["ХОЧУ", "КУШАТЬ"]


def require(condition, message):
    if not condition:
        raise ValueError(message)


def digest(value):
    return isinstance(value, str) and re.fullmatch(r"[0-9a-f]{64}", value) is not None


def validate(manifest_path):
    root = manifest_path.resolve().parent
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    require(manifest["schema_version"] == 1, "unsupported schema_version")
    pairs = manifest["pairs"]
    require(isinstance(pairs, list) and bool(pairs), "no pairs")
    states, filenames = set(), set()
    for pair in pairs:
        state = pair["state"]
        require(state not in states, "duplicate state")
        states.add(state)
        baseline, new = pair["baseline"], pair["new"]
        for key in ("viewport", "os", "fixture_sha256", "page", "card_order", "message_parts"):
            require(baseline[key] == new[key], f"{state}: incomparable {key}")
        require(baseline["package"] == "su.linka.pictures", "wrong baseline package")
        require(new["package"] == "su.linka.pictures.development", "wrong development package")
        for entry in (baseline, new):
            require(isinstance(entry["build"], str) and bool(entry["build"].strip()), "missing build identity")
            require(re.fullmatch(r"[0-9a-f]{40}", entry["commit"]) is not None, "commit must be full SHA")
            require(type(entry["dirty"]) is bool, "dirty must be boolean")
            require(digest(entry["wip_diff_sha256"]) if entry["dirty"] else entry["wip_diff_sha256"] is None,
                    "dirty builds need a diff identity; clean builds must use null")
            require(isinstance(entry["os"], str) and bool(entry["os"].strip()), "missing OS")
            require(entry["fixture_sha256"] == FIXTURE_SHA, "not the frozen fixture")
            require(entry["page"] == 0 and type(entry["page"]) is int, "fixture page must be zero-based 0")
            require(entry["card_order"] == CARD_ORDER, "wrong card order")
            require(type(entry["visually_reviewed"]) is bool and type(entry["user_approved"]) is bool,
                    "review/approval flags must be explicit booleans")
            require(not entry["user_approved"] or entry["visually_reviewed"], "approval without review")
            viewport = entry["viewport"]
            for key in ("width_dp", "height_dp", "width_px", "height_px", "font_scale"):
                require(type(viewport[key]) in (int, float) and 0 < viewport[key] < 100000,
                        f"invalid viewport {key}")
            orientation = "landscape" if viewport["width_px"] > viewport["height_px"] else "portrait"
            require(viewport["orientation"] == orientation, "orientation contradicts pixel dimensions")
            require((viewport["width_dp"] > viewport["height_dp"]) == (orientation == "landscape"),
                    "dp and pixels disagree on orientation")
            name = Path(entry["png"])
            require(not name.is_absolute() and ".." not in name.parts and name.suffix == ".png", "unsafe PNG filename")
            png = (root / name).resolve()
            require(png.is_relative_to(root) and png not in filenames, "PNG path reused or outside evidence root")
            filenames.add(png)
            data = png.read_bytes()
            require(data[:8] == b"\x89PNG\r\n\x1a\n" and data[12:16] == b"IHDR" and len(data) > 33,
                    "not a PNG with IHDR")
            require(struct.unpack(">II", data[16:24]) == (viewport["width_px"], viewport["height_px"]),
                    "PNG dimensions disagree with viewport")
            require(digest(entry["sha256"]) and hashlib.sha256(data).hexdigest() == entry["sha256"], "PNG hash mismatch")
        if state == "board-filled":
            require(baseline["message_parts"] == MESSAGE, "wrong filled message")
    require("board-filled" in states, "missing required state board-filled")
    return len(pairs)


def self_test():
    # Synthetic header bytes exist only in this temporary parser test, never as screenshot evidence.
    with tempfile.TemporaryDirectory(prefix="u14-evidence-selftest-") as directory:
        root = Path(directory)
        data = b"\x89PNG\r\n\x1a\n" + struct.pack(">I", 13) + b"IHDR" + struct.pack(">II", 900, 600) + bytes(20)
        for name in ("baseline.png", "new.png"):
            (root / name).write_bytes(data)
        entry = dict(build="selftest only", commit="a" * 40, dirty=False, wip_diff_sha256=None,
                     package="su.linka.pictures", os="Android 35 selftest", fixture_sha256=FIXTURE_SHA,
                     page=0, card_order=CARD_ORDER, message_parts=MESSAGE, visually_reviewed=False,
                     user_approved=False, png="baseline.png", sha256=hashlib.sha256(data).hexdigest(),
                     viewport=dict(width_dp=900, height_dp=600, width_px=900, height_px=600,
                                   font_scale=1.0, orientation="landscape"))
        manifest = dict(schema_version=1, pairs=[dict(state="board-filled", baseline=entry,
                        new=dict(entry, package="su.linka.pictures.development", png="new.png"))])
        path = root / "manifest.json"
        def check():
            path.write_text(json.dumps(manifest), encoding="utf-8")
            return validate(path)
        assert check() == 1
        for key, bad in (("sha256", "0" * 64), ("png", "../escape.png"),
                         ("message_parts", []), ("fixture_sha256", "0" * 64), ("dirty", True),
                         ("viewport", dict(entry["viewport"], orientation="portrait"))):
            old = manifest["pairs"][0]["new"][key]
            manifest["pairs"][0]["new"][key] = bad
            try:
                check()
            except ValueError:
                pass
            else:
                raise AssertionError(f"accepted invalid {key}")
            manifest["pairs"][0]["new"][key] = old
        manifest["pairs"][0]["state"] = "library"
        try:
            check()
        except ValueError:
            pass
        else:
            raise AssertionError("accepted missing filled board")
    print("Evidence validator self-test OK (no visual verdict)")


if __name__ == "__main__":
    try:
        if sys.argv[1:] == ["--self-test"]:
            self_test()
        else:
            require(len(sys.argv) == 2, "usage: check-ui-parity-evidence.py MANIFEST.json | --self-test")
            count = validate(Path(sys.argv[1]))
            print(f"Integrity OK: {count} pair(s). Visual parity and user approval NOT evaluated.")
    except (ValueError, KeyError, TypeError, OSError, struct.error) as error:
        sys.exit(f"Evidence invalid: {error}")
