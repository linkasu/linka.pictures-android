import copy
import importlib.util
import io
import json
from email.message import Message
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import contextlib

spec = importlib.util.spec_from_file_location("producer", Path(__file__).with_name("prepare-starter-audio.py"))
producer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(producer)


class Response(io.BytesIO):
    def __init__(self, status=200, content_type="audio/mpeg", body=b"mp3", length="3"):
        super().__init__(body)
        self.status = status
        self.headers = Message()
        self.headers["Content-Type"] = content_type
        if length is not None:
            self.headers["Content-Length"] = length


class AudioProducerTest(unittest.TestCase):
    def test_approved_coverage_and_letter_speech(self):
        path = producer.ROOT / "docs/development/starter-pack-manifest.json"
        manifest = json.loads(path.read_text())
        rows = producer.requests(path.with_name("starter-pack-recordings.csv"), manifest, Path(tempfile.gettempdir()))
        self.assertEqual(len(rows), 74)
        self.assertEqual(sum(len(r["used_in"].split(";")) for r in rows), 87)
        by_key = {r["key"]: r for r in rows}
        self.assertEqual(by_key["ru.letter.soft_sign"]["speechText"], "Мягкий знак")
        self.assertEqual(by_key["ru.letter.hard_sign"]["speechText"], "Твёрдый знак")
        self.assertEqual(by_key["ru.letter.short_i"]["speechText"], "И краткое")
        self.assertEqual(by_key["ru.eat"]["speechText"], "Есть")
        for change in ["unapproved", "bad_page", "duplicate_seed"]:
            invalid = copy.deepcopy(manifest)
            if change == "unapproved":
                invalid["contentApproval"]["status"] = "pending"
            elif change == "bad_page":
                invalid["proposal"][0]["pages"][0].pop()
            else:
                invalid["proposal"].append(invalid["proposal"][0])
            with self.subTest(change=change), self.assertRaises(ValueError):
                producer.requests(path.with_name("starter-pack-recordings.csv"), invalid, Path(tempfile.gettempdir()))

    def test_unsafe_targets(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            for name in ["../escape.mp3", "/tmp/escape.mp3", "ru-help.wav", "ru-help.mp3/x"]:
                with self.subTest(name=name), self.assertRaises(ValueError):
                    producer.target(directory, name)
            (directory / "ru-help.mp3").symlink_to(directory / "outside")
            with self.assertRaises(ValueError):
                producer.target(directory, "ru-help.mp3")

    def test_malformed_audio_manifest(self):
        valid = {"schema":"linka-starter-audio-v1", "source":producer.SOURCE,
                 "voice":producer.VOICE, "records":{}}
        producer.validate_index(valid, [])
        for invalid in [[], {}, {**valid, "source":"http://evil.test"},
                        {**valid, "records":{"unknown":{}}}, {**valid, "records":[]}]:
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                producer.validate_index(invalid, [])

    def test_response_errors_and_limits(self):
        self.assertEqual(producer.response_bytes(Response()), b"mp3")
        for response in [Response(status=401), Response(status=402), Response(status=429),
                         Response(content_type="text/html"), Response(body=b"", length="0"),
                         Response(length="99"), Response(length=str(producer.LIMIT + 1)),
                         Response(body=b"x" * (producer.LIMIT + 1), length=None)]:
            with self.subTest(status=response.status, headers=str(response.headers)), self.assertRaises(ValueError):
                producer.response_bytes(response)

    def test_invalid_audio_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "bad.mp3"
            path.write_bytes(b"not audio")
            with self.assertRaises((ValueError, producer.subprocess.CalledProcessError)):
                producer.inspect(path)

    def test_silence_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "silence.mp3"
            producer.subprocess.run(["ffmpeg", "-v", "error", "-f", "lavfi", "-i",
                                     "anullsrc=r=48000:cl=mono", "-t", "0.5", str(path)],
                                    check=True, capture_output=True, timeout=10)
            with self.assertRaisesRegex(ValueError, "Silent"):
                producer.inspect(path)

    def test_offline_idempotence_and_receipt_tampering(self):
        index_path = producer.ROOT / "assets/starter-pack/audio/manifest.json"
        before = index_path.read_bytes()
        with patch("sys.argv", ["prepare-starter-audio.py"]), \
                patch("urllib.request.OpenerDirector.open", side_effect=AssertionError("Network forbidden")), \
                contextlib.redirect_stdout(io.StringIO()):
            producer.main()
        self.assertEqual(before, index_path.read_bytes())
        path = producer.ROOT / "docs/development/starter-pack-manifest.json"
        rows = producer.requests(path.with_name("starter-pack-recordings.csv"), json.loads(path.read_text()), index_path.parent)
        invalid = json.loads(before)
        invalid["records"]["ru.help"]["request"]["method"] = "POST"
        with self.assertRaisesRegex(ValueError, "pilot receipt"):
            producer.validate_index(invalid, rows)


if __name__ == "__main__":
    unittest.main()
