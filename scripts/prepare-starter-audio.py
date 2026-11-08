#!/usr/bin/env python3
"""User-directed LINKa TTS synthesis; no retries, no runtime network dependency."""
import argparse
import csv
import hashlib
import json
import math
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import time
import urllib.parse
import urllib.request
import urllib.error

ROOT = Path(__file__).resolve().parents[1]
SOURCE = "https://tts.linka.su/tts"
VOICE = "anton"
LIMIT = 2 * 1024 * 1024
PILOT_SHA = "c7f5df90c02687060b9fab6e4d3ae0199e67bb26c11e5388a5c9af7930e0749d"
LETTER_NAMES = dict(zip(
    "АБВГДЕЁЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯ",
    ["А", "Бэ", "Вэ", "Гэ", "Дэ", "Е", "Ё", "Жэ", "Зэ", "И", "И краткое",
     "Ка", "Эль", "Эм", "Эн", "О", "Пэ", "Эр", "Эс", "Тэ", "У", "Эф", "Ха",
     "Цэ", "Че", "Ша", "Ща", "Твёрдый знак", "Ы", "Мягкий знак", "Э", "Ю", "Я"], strict=True))


def target(directory, filename):
    if not re.fullmatch(r"ru-[a-z-]+\.mp3", filename):
        raise ValueError("Unsafe audio filename")
    path = directory / filename
    if directory.is_symlink() or path.is_symlink() or path.resolve().parent != directory.resolve():
        raise ValueError("Audio target escapes directory or is a symlink")
    return path


def requests(csv_path, manifest, directory):
    if manifest.get("contentApproval", {}).get("status") != "approved":
        raise ValueError("Vocabulary not approved")
    expected = {}
    seeds = set()
    for board in manifest["proposal"]:
        seed = board["seedKey"]
        if seed in seeds or board["rows"] != 3 or board["columns"] != 3:
            raise ValueError("Duplicate seed or invalid grid")
        seeds.add(seed)
        for pi, page in enumerate(board["pages"], 1):
            if len(page) != 9:
                raise ValueError("Invalid page")
            for ci, text in enumerate(page, 1):
                if not isinstance(text, str) or not text:
                    raise ValueError("Invalid label")
                if text not in {"SPACE", ".", "?"}:
                    expected[f"{seed}:p{pi}:c{ci}"] = text
    with csv_path.open(newline="", encoding="utf-8") as f:
        rows = list(csv.DictReader(f))
    if len(rows) != 74 or len({r["key"] for r in rows}) != 74:
        raise ValueError("Expected 74 unique recording keys")
    actual, filenames, labels = {}, set(), set()
    for row in rows:
        target(directory, row["expected_filename"])
        if not re.fullmatch(r"ru\.[a-z_.]+", row["key"]):
            raise ValueError("Invalid recording key")
        if row["expected_filename"] in filenames or row["exact_phrase"] in labels:
            raise ValueError("Repeated filename or phrase")
        filenames.add(row["expected_filename"])
        labels.add(row["exact_phrase"])
        for loc in row["used_in"].split(";"):
            if loc in actual:
                raise ValueError("Repeated location")
            actual[loc] = row["exact_phrase"]
        row["speechText"] = (LETTER_NAMES[row["exact_phrase"]]
                             if row["key"].startswith("ru.letter.") else row["exact_phrase"])
        row["sourceUrl"] = SOURCE
    if actual != expected or len(actual) != 87:
        raise ValueError("CSV does not cover approved speaking slots")
    return rows


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise ValueError(f"Unexpected redirect: {code}")


def response_bytes(response):
    if response.status != 200 or response.headers.get_content_type() != "audio/mpeg":
        raise ValueError("Expected HTTP 200 audio/mpeg")
    length = response.headers.get("Content-Length")
    if length is not None and not 0 < int(length) <= LIMIT:
        raise ValueError("Invalid response size")
    chunks, count, start = [], 0, time.monotonic()
    while True:
        chunk = response.read(min(65536, LIMIT + 1 - count))
        if not chunk:
            break
        count += len(chunk)
        if count > LIMIT or time.monotonic() - start > 60:
            raise ValueError("Response exceeds size/time budget")
        chunks.append(chunk)
    if count == 0 or (length is not None and count != int(length)):
        raise ValueError("Empty or truncated response")
    return b"".join(chunks)


def inspect(path):
    if not 0 < path.stat().st_size <= LIMIT:
        raise ValueError("Invalid local audio size")
    probe = subprocess.run(["ffprobe", "-v", "error", "-show_format", "-show_streams",
                            "-of", "json", str(path)], check=True, capture_output=True,
                           text=True, timeout=20)
    data = json.loads(probe.stdout)
    streams = data.get("streams", [])
    duration = float(data.get("format", {}).get("duration", 0))
    if (probe.stderr or data.get("format", {}).get("format_name") != "mp3"
            or len(streams) != 1 or streams[0].get("codec_name") != "mp3"
            or not math.isfinite(duration) or not 0 < duration <= 30):
        raise ValueError("Not a single bounded MP3 stream")
    decode = subprocess.run(["ffmpeg", "-hide_banner", "-nostdin", "-v", "info",
                             "-xerror", "-err_detect", "explode", "-i", str(path),
                             "-af", "volumedetect", "-f", "null", "-"],
                            check=True, capture_output=True, text=True, timeout=30)
    peaks = re.findall(r"max_volume: ([-\w.]+) dB", decode.stderr)
    if not peaks or not math.isfinite(float(peaks[-1])) or float(peaks[-1]) < -50:
        raise ValueError("Silent or near-silent recording")
    return {"format": "mp3", "codec": "mp3", "durationSeconds": duration,
            "sampleRate": int(streams[0]["sample_rate"]), "channels": streams[0]["channels"],
            "maxVolumeDb": float(peaks[-1]), "strictDecode": "passed",
            "nonSilent": True, "listeningReview": "not_performed"}


def save_index(path, index):
    with tempfile.NamedTemporaryFile(mode="w", dir=path.parent, encoding="utf-8", delete=False) as f:
        json.dump(index, f, ensure_ascii=False, indent=2)
        f.write("\n")
        temporary = Path(f.name)
    temporary.replace(path)


def validate_index(index, rows):
    if (not isinstance(index, dict) or index.get("schema") != "linka-starter-audio-v1"
            or index.get("source") != SOURCE or index.get("voice") != VOICE
            or not isinstance(index.get("records"), dict)
            or set(index["records"]) - {r["key"] for r in rows}
            or any(not isinstance(r, dict) for r in index["records"].values())):
        raise ValueError("Malformed audio manifest")
    by_key = {row["key"]: row for row in rows}
    for key, record in index["records"].items():
        if (any(record.get(field) != by_key[key][field]
                for field in ["key", "exact_phrase", "used_in", "speechText", "sourceUrl", "expected_filename"])
                or not isinstance(record.get("size"), int) or not 0 < record["size"] <= LIMIT
                or not re.fullmatch(r"[0-9a-f]{64}", str(record.get("sha256")))
                or record.get("acquisition") not in {"https_post_json_once", "reused_primary_pilot"}
                or not isinstance(record.get("request"), dict)):
            raise ValueError("Malformed acquisition receipt")
        receipt = record["request"]
        if receipt.get("httpStatus") != 200 or receipt.get("contentType") != "audio/mpeg":
            raise ValueError("Invalid HTTP receipt")
        if record["acquisition"] == "https_post_json_once":
            if (receipt.get("method") != "POST" or receipt.get("url") != SOURCE
                    or receipt.get("json") != {"text": record["speechText"], "voice": VOICE}):
                raise ValueError("POST receipt does not match synthesis request")
        elif (key != "ru.help" or record["sha256"] != PILOT_SHA or record["size"] != 7341
              or receipt.get("method") != "GET"
              or receipt.get("url") != SOURCE + "?" + urllib.parse.urlencode({"text": "Помоги", "voice": VOICE})):
            raise ValueError("Invalid pilot receipt")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--download", action="store_true", help="Request missing files once; default validates offline")
    parser.add_argument("--pilot", type=Path, help="Reuse already-authorized Помоги pilot")
    parser.add_argument("--preflight", action="store_true", help="Process only first CSV entry to verify POST contract")
    args = parser.parse_args()
    for tool in ["ffprobe", "ffmpeg"]:
        if not shutil.which(tool):
            raise ValueError(f"Required existing codec tool unavailable: {tool}")
    directory = ROOT / "assets/starter-pack/audio"
    if not directory.resolve().is_relative_to(ROOT.resolve()):
        raise ValueError("Audio directory escapes project root")
    manifest_path = ROOT / "docs/development/starter-pack-manifest.json"
    manifest = json.loads(manifest_path.read_text())
    rows = requests(manifest_path.with_name("starter-pack-recordings.csv"), manifest, directory)
    directory.mkdir(parents=True, exist_ok=True)
    index_path = directory / "manifest.json"
    if index_path.is_symlink():
        raise ValueError("Symlink audio manifest")
    index = json.loads(index_path.read_text()) if index_path.exists() else {
        "schema": "linka-starter-audio-v1", "source": SOURCE, "voice": VOICE,
        "provenance": "User-directed synthesis LINKa TTS anton; no legacy media reused",
        "license": None, "licenseNote": "No external media license inferred",
        "tools": {tool: subprocess.run([tool, "-version"], check=True, capture_output=True,
                                      text=True, timeout=10).stdout.splitlines()[0]
                  for tool in ["ffprobe", "ffmpeg"]}, "records": {}}
    validate_index(index, rows)
    opener = urllib.request.build_opener(NoRedirect)
    for row in rows[:1] if args.preflight else rows:
        path = target(directory, row["expected_filename"])
        previous = index["records"].get(row["key"])
        origin = previous["acquisition"] if previous else None
        if previous and not path.exists():
            raise ValueError("Previously acquired file is missing; refusing duplicate synthesis")
        if path.exists():
            if not previous:
                raise ValueError("Existing file has no acquisition receipt; refusing to invent provenance")
            audio = path.read_bytes()
        elif args.pilot and row["key"] == "ru.help":
            audio = args.pilot.read_bytes()
            if len(audio) != 7341 or hashlib.sha256(audio).hexdigest() != PILOT_SHA:
                raise ValueError("Pilot hash/size mismatch")
            origin = "reused_primary_pilot"
        elif args.download:
            # No retries: HTTP 401/402/429 and all other errors stop the run.
            request = urllib.request.Request(SOURCE,
                data=json.dumps({"text": row["speechText"], "voice": VOICE}).encode("utf-8"),
                headers={"Content-Type": "application/json", "Accept": "audio/mpeg"}, method="POST")
            try:
                with opener.open(request, timeout=20) as response:
                    audio = response_bytes(response)
            except urllib.error.HTTPError as error:
                detail = error.read(1024).decode("utf-8", errors="replace")
                raise ValueError(f"POST stopped, HTTP {error.code}: {detail}") from error
            origin = "https_post_json_once"
        else:
            raise ValueError(f"Missing offline audio: {row['key']}")
        sha = hashlib.sha256(audio).hexdigest()
        if previous and (previous.get("sha256") != sha or previous.get("size") != len(audio)):
            raise ValueError("Local audio hash differs from recorded provenance")
        if previous:
            if previous.get("validation") != inspect(path):
                raise ValueError("Validation differs from acquisition manifest; review before changing evidence")
            print(f"{row['key']}: {len(audio)} bytes, offline reuse PASS", flush=True)
            continue
        if not path.exists():
            # Keep the response on codec failure: never silently synthesize it again.
            with path.open("xb") as f:
                f.write(audio)
        index["records"][row["key"]] = {**row, "sha256": sha, "size": len(audio),
            "voice": VOICE, "acquisition": origin, "validation": {"strictDecode": "pending"}}
        if origin == "reused_primary_pilot":
            index["records"][row["key"]]["request"] = {"method": "GET", "url": SOURCE + "?" + urllib.parse.urlencode({"text": "Помоги", "voice": VOICE}), "httpStatus": 200, "contentType": "audio/mpeg", "evidence": "primary supplied pilot and verified hash/size"}
        elif origin == "https_post_json_once":
            index["records"][row["key"]]["request"] = {"method": "POST", "url": SOURCE, "json": {"text": row["speechText"], "voice": VOICE}, "httpStatus": 200, "contentType": "audio/mpeg"}
        save_index(index_path, index)
        index["records"][row["key"]]["validation"] = inspect(path)
        save_index(index_path, index)
        print(f"{row['key']}: {len(audio)} bytes, MP3 decode/non-silent PASS", flush=True)
    print(f"Ready: {len(index['records'])} records; listening/native validation still pending")


if __name__ == "__main__":
    main()
