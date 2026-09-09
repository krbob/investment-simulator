"""Local HTTP contract tests; no access to a real Portfolio instance."""

from contextlib import contextmanager
import copy
import importlib.util
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import tempfile
import threading
import unittest


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("capture_portfolio", ROOT / "scripts" / "capture-portfolio.py")
capture = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(capture)


@contextmanager
def fixture_server(bundle, changed=False, redirect=False):
    requests = []
    exports = 0

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            nonlocal exports
            requests.append((self.path, self.headers.get("Cookie")))
            if redirect:
                self.send_response(302)
                self.send_header("Location", "/redirected-must-not-receive-cookie")
                self.end_headers()
                return
            if self.path == "/v1/portfolio/state/export":
                payload = copy.deepcopy(bundle["snapshot"])
                exports += 1
                if exports == 2:
                    payload["exportedAt"] = "2026-09-09T12:00:01Z"
                    if changed:
                        payload["transactions"][0]["grossAmount"] = "2000"
            elif self.path == "/v1/portfolio/holdings":
                payload = bundle["holdings"]
            elif self.path == "/v1/portfolio/accounts":
                payload = bundle["accountSummaries"]
            else:
                self.send_error(404)
                return
            body = json.dumps(payload).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, *_args):
            pass

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield f"http://127.0.0.1:{server.server_address[1]}", requests
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=5)


class CapturePortfolioTest(unittest.TestCase):
    def setUp(self):
        self.bundle = json.loads((ROOT / "portfolio-adapter/src/test/resources/portfolio-bundle.json").read_text())
        self.settings = {"selection": self.bundle["selection"]}

    def test_captures_consistent_ledger_around_valuations_and_preserves_raw_upstream(self):
        with fixture_server(self.bundle) as (url, requests):
            result = capture.capture_bundle(url, self.settings, session_cookie="portfolio_session=synthetic")
        self.assertEqual([
            "/v1/portfolio/state/export", "/v1/portfolio/holdings", "/v1/portfolio/accounts",
            "/v1/portfolio/state/export",
        ], [path for path, _cookie in requests])
        self.assertTrue(all(cookie == "portfolio_session=synthetic" for _path, cookie in requests))
        self.assertEqual("2026-09-09T12:00:01Z", result["snapshot"]["exportedAt"])
        self.assertEqual(self.bundle["snapshot"]["transactions"], result["snapshot"]["transactions"])
        self.assertEqual(self.bundle["holdings"], result["holdings"])
        self.assertNotIn("portfolio_session", json.dumps(result))
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "bundle.json"
            capture.write_bundle(result, output)
            self.assertEqual(result, json.loads(output.read_text()))

    def test_canonical_mutation_rejects_capture(self):
        with fixture_server(self.bundle, changed=True) as (url, _requests):
            with self.assertRaisesRegex(capture.CaptureError, "canonical state changed"):
                capture.capture_bundle(url, self.settings)

    def test_redirect_is_rejected_before_cookie_can_be_forwarded(self):
        with fixture_server(self.bundle, redirect=True) as (url, requests):
            with self.assertRaisesRegex(capture.CaptureError, "HTTP redirect"):
                capture.capture_bundle(url, self.settings, "portfolio_session=synthetic")
        self.assertEqual(1, len(requests))

    def test_invalid_settings_fail_before_network_access(self):
        with self.assertRaisesRegex(capture.CaptureError, "Unsupported settings keys"):
            capture.capture_bundle("http://127.0.0.1:1", {**self.settings, "snapshot": {}})
        with self.assertRaisesRegex(capture.CaptureError, "credentials"):
            capture.capture_bundle("http://user:secret@127.0.0.1:1", self.settings)


if __name__ == "__main__":
    unittest.main()
