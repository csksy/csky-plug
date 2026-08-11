"""
Tests for the Eera bridge.

Run:  python -m unittest test_bridge -v
Requires: pip install -r requirements.txt
"""
import json
import os
import socket
import subprocess
import sys
import time
import unittest
from types import SimpleNamespace
from urllib.request import urlopen, Request

import main as bridge

# ---------------------------------------------------------------------------
# Real search reply from @eera_Search_Zone (from the user's screenshot 2.png)
# ---------------------------------------------------------------------------
REAL_SEARCH_REPLY = """👍🤷

📢 THE RESULTS FOR ☞
latent ❞
🙇 REQUESTED BY ☞ raja babu
⌛ RESULT SHOW IN ☞ 1.20 SECONDS
✨ POWERED BY ☞ Search Zone 🤫

⚠️ AFTER 5 MINUTES THIS MESSAGE WILL BE AUTOMATICALLY DELETED 🗑️

🍿 Your Movie Files 👇 ❞

📁 [2.41 GB] ❗ INDIA'S Got Latent S2 Bonus Ep2 Ft Badshah Sourav Jos
📁 [1008.60 MB] ❗ INDIA'S Got Latent S2 Bonus Ep2 Ft Badshah Sourav Joshi Harssh Limbachiyaa mp4
📁 [579.66 MB] ❗ INDIA'S Got Latent S2 Bonus Ep2 Ft Badshah Sourav Joshi Harssh Limbachiyaa mp4
📁 [342.18 MB] ❗ INDIA'S Got Latent S2 Bonus Ep2 Ft Badshah Sourav Joshi Harssh Limbachiyaa mp4
📁 [116.70 MB] ❗ INDIA'S Got Latent S2 Bonus Ep2 Ft Badshah Sourav Joshi Harssh Limbachiyaa mp4
📁 [199.39 MB] ❗ INDIA'S Got Latent S2 Bonus Ep2 Ft Badshah Sourav Joshi Harssh Limbachiyaa mp4
📁 [57.58 MB] ❗ INDIA'S Got Latent S2 Bonus Ep2 Ft Badshah Sourav Joshi Harssh Limbachiyaa mp4
📁 [124.62 MB] ❗ India's Got Latent S2 Bonus EP 2 480p ft Badshah@Movie mkv
📁 [263.09 MB] ❗ India's Got Latent S2 Bonus EP 2 1080p ft Badshah@Movi mkv"""


class FakeMessage:
    def __init__(self, text, entities=None, buttons=None):
        self.text = text
        self.message = text
        self.entities = entities or []
        self.buttons = buttons


class TestBotLink(unittest.TestCase):
    def test_bot_deep_links(self):
        self.assertTrue(bridge._is_bot_link("https://t.me/Movie_world2_bot?start=abc123"))
        self.assertTrue(bridge._is_bot_link("https://t.me/Movie_world2_bot/start"))

    def test_noise_links(self):
        self.assertFalse(bridge._is_bot_link("https://t.me/eera_Search_Zone"))
        self.assertFalse(bridge._is_bot_link("https://t.me/Harshit_contact_bot"))
        self.assertFalse(bridge._is_bot_link("https://example.com/movie"))


class TestExtractUrls(unittest.TestCase):
    def test_visible_and_hidden_urls(self):
        text = "Pick a title https://t.me/Movie_world2_bot?start=z9"
        from telethon.tl.types import MessageEntityUrl, MessageEntityTextUrl

        entities = [
            MessageEntityUrl(offset=text.index("https"), length=len("https://t.me/Movie_world2_bot?start=z9")),
        ]
        urls = bridge._extract_urls(FakeMessage(text, entities))
        self.assertEqual(urls, ["https://t.me/Movie_world2_bot?start=z9"])


class TestParseFileList(unittest.TestCase):
    def test_real_reply_no_links(self):
        items = bridge._parse_file_list(FakeMessage(REAL_SEARCH_REPLY))
        self.assertEqual(len(items), 9)
        self.assertEqual(items[0]["title"], "INDIA'S Got Latent S2 Bonus Ep2 Ft Badshah Sourav Jos")
        self.assertEqual(items[0]["size"], "2.41 GB")
        self.assertEqual(items[-1]["size"], "263.09 MB")
        # no links in this screenshot -> payloads fall back to None
        self.assertTrue(all(i["payload"] is None for i in items))

    def test_real_reply_with_bot_deep_links_paired(self):
        text = REAL_SEARCH_REPLY
        lines = [l for l in text.splitlines() if bridge.FILE_RE.search(l)]
        urls = [f"https://t.me/Movie_world2_bot?start=item{i}" for i in range(len(lines))]
        msg = FakeMessage(text, entities=[])
        # inject button urls (the way these bots usually attach the deep links)
        buttons = [[SimpleNamespace(url=u) for u in urls]]
        msg.buttons = buttons
        items = bridge._parse_file_list(msg)
        self.assertEqual(len(items), len(urls))
        self.assertEqual(items[0]["payload"], urls[0])
        self.assertEqual(items[-1]["payload"], urls[-1])

    def test_header_channel_link_ignored(self):
        text = "✨ POWERED BY ☞ Search Zone https://t.me/eera_Search_Zone 🤫\n" + REAL_SEARCH_REPLY
        urls = ["https://t.me/Movie_world2_bot?start=only"]
        buttons = [[SimpleNamespace(url=u) for u in urls]]
        items = bridge._parse_file_list(FakeMessage(text, [], buttons))
        # channel link in header must NOT become a payload
        self.assertEqual(items[0]["payload"], urls[0])


class TestRangeParsing(unittest.TestCase):
    def test_open_ended(self):
        self.assertEqual(bridge._parse_range_header("bytes=0-", 1000), (0, min(999, bridge.MAX_RANGE - 1)))

    def test_explicit(self):
        s, e = bridge._parse_range_header("bytes=100-199", 10000)
        self.assertEqual((s, e), (100, 199))

    def test_huge_range_capped(self):
        s, e = bridge._parse_range_header("bytes=0-", 3_800_000_000)
        self.assertLessEqual(e - s + 1, bridge.MAX_RANGE)

    def test_unsatisfiable(self):
        self.assertIsNone(bridge._parse_range_header("bytes=5000-6000", 100))
        self.assertIsNone(bridge._parse_range_header("bogus", 100))


class TestLiveServer(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        # pick a free port and boot the real server without Telegram credentials
        with socket.socket() as s:
            s.bind(("127.0.0.1", 0))
            cls.port = s.getsockname()[1]
        env = dict(os.environ)
        env["PORT"] = str(cls.port)
        env.pop("API_ID", None)
        env.pop("API_HASH", None)
        cls.proc = subprocess.Popen(
            [sys.executable, "main.py"],
            cwd=os.path.dirname(os.path.abspath(__file__)),
            env=env,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        deadline = time.time() + 30
        while time.time() < deadline:
            try:
                urlopen(f"http://127.0.0.1:{cls.port}/health", timeout=2)
                break
            except Exception:
                time.sleep(0.5)
        else:
            cls.proc.kill()
            raise RuntimeError("server did not start")

    @classmethod
    def tearDownClass(cls):
        cls.proc.terminate()
        try:
            cls.proc.wait(timeout=5)
        except Exception:
            cls.proc.kill()

    def get(self, path):
        from urllib.error import HTTPError

        try:
            with urlopen(f"http://127.0.0.1:{self.port}{path}", timeout=10) as r:
                return r.status, r.read()
        except HTTPError as e:
            return e.code, e.read()

    def test_health(self):
        status, body = self.get("/health")
        self.assertEqual(status, 200)
        data = json.loads(body)
        self.assertTrue(data["ok"])
        self.assertFalse(data["loggedIn"])

    def test_config(self):
        status, body = self.get("/api/config")
        self.assertEqual(status, 200)
        data = json.loads(body)
        self.assertFalse(data["apiConfigured"])
        self.assertEqual(data["group"], "eera_Search_Zone")
        self.assertEqual(data["bot"], "Movie_world2_bot")

    def test_root_html(self):
        status, body = self.get("/")
        self.assertEqual(status, 200)
        self.assertIn(b"Eera Telegram Bridge", body)

    def test_unknown_stream_404(self):
        status, _ = self.get("/api/stream/nope")
        self.assertEqual(status, 404)

    def test_search_requires_q(self):
        status, _ = self.get("/api/search")
        # FastAPI: 422 for missing required query param, 400 if we handle it
        self.assertIn(status, (400, 422))


if __name__ == "__main__":
    unittest.main(verbosity=2)
