#!/usr/bin/env python3
"""Generate reproducible public .linka visual fixtures; never install or import them."""
import hashlib
import io
import json
from pathlib import Path
import sys
import tempfile
import zipfile

SOURCE = Path(__file__).resolve().parents[1] / "app/src/androidTest/assets/u14/ХОЧУ.linka"
SOURCE_SHA = "a0589a029f77bfff7307d6ee3c4dbd41e1b2badd52ca90896ca5c835ccfb293b"


def generate(destination):
    raw = SOURCE.read_bytes()
    if hashlib.sha256(raw).hexdigest() != SOURCE_SHA:
        raise ValueError("public source fixture identity changed")
    destination.mkdir(parents=True, exist_ok=True)
    manifest = {"source_sha256": SOURCE_SHA, "fixtures": {},
                "approval": False, "note": "Public synthetic data; generated, not imported."}
    with zipfile.ZipFile(io.BytesIO(raw)) as source:
        original = json.loads(source.read("config.json"))
        cards = [dict(card, id=f"electron-{i}") for i, card in enumerate(original["cards"])]
        cases = {
            "six-cards": (2, 3, cards[:6], False),
            "nine-cards": (3, 3, cards, False),
            "two-pages": (3, 3, cards, False),
            "long-label": (3, 3, [dict(card, title="Полная длинная подпись карточки — конец текста") if i == 0 else card
                                    for i, card in enumerate(cards)], False),
            "no-image": (3, 3, [dict(card, imagePath=None) for card in cards], False),
            "literal": (3, 3, cards, True),
        }
        for name, (rows, columns, content, literal) in cases.items():
            page = dict(id="page-1", mode="standard", rows=rows, columns=columns, cards=content)
            pages = [page]
            if name == "two-pages":
                pages.append(dict(page, id="page-2", cards=[dict(c, id=c["id"] + "-second") for c in content]))
            config = dict(version="3.0", withoutSpace=literal, directSet=False, pages=pages)
            entries = {"config.json": json.dumps(config, ensure_ascii=False, sort_keys=True).encode()}
            for card in content:
                for key in ("imagePath", "audioPath"):
                    if card.get(key):
                        entries[card[key]] = source.read(card[key])
            filename = destination / f"{name}.linka"
            if filename.exists():
                raise FileExistsError(f"refusing to overwrite {filename}")
            with zipfile.ZipFile(filename, "w", compression=zipfile.ZIP_DEFLATED) as archive:
                for path, data in sorted(entries.items()):
                    info = zipfile.ZipInfo(path, date_time=(2026, 11, 7, 0, 0, 0))
                    info.compress_type = zipfile.ZIP_DEFLATED
                    archive.writestr(info, data)
            manifest["fixtures"][filename.name] = dict(
                sha256=hashlib.sha256(filename.read_bytes()).hexdigest(), pages=len(pages),
                rows=rows, columns=columns, labels=[card["title"] for card in content])
    manifest_path = destination / "fixtures.json"
    if manifest_path.exists():
        raise FileExistsError(f"refusing to overwrite {manifest_path}")
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return manifest


def self_test():
    with tempfile.TemporaryDirectory(prefix="electron-fixtures-") as directory:
        root = Path(directory)
        first, second = generate(root / "a"), generate(root / "b")
        assert first == second
        assert len(first["fixtures"]) == 6
        for name in first["fixtures"]:
            assert (root / "a" / name).read_bytes() == (root / "b" / name).read_bytes()
            with zipfile.ZipFile(root / "a" / name) as archive:
                assert archive.testzip() is None
                config = json.loads(archive.read("config.json"))
                assert config["version"] == "3.0" and config["directSet"] is False
                for page in config["pages"]:
                    assert page["mode"] == "standard"
                    assert len(page["cards"]) == page["rows"] * page["columns"]
        try:
            generate(root / "a")
        except FileExistsError:
            pass
        else:
            raise AssertionError("existing fixtures were overwritten")
    print("Six deterministic public fixtures verified; no app data touched.")


if __name__ == "__main__":
    if sys.argv[1:] == ["--self-test"]:
        self_test()
    elif len(sys.argv) == 2:
        print(json.dumps(generate(Path(sys.argv[1])), ensure_ascii=False, indent=2))
    else:
        sys.exit("usage: generate-electron-ui-fixtures.py NEW_OUTPUT_DIRECTORY | --self-test")
