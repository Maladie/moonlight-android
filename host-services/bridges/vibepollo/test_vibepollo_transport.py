import json
import subprocess
import sys
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


class ControlSnapshotTransportTest(unittest.TestCase):
    def test_parallel_sources_are_bounded_and_independent(self):
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_args):
                pass

            def do_GET(self):
                time.sleep(0.38)
                status = 503 if self.path == "/api/apps" else 200
                body = b'{}' if status == 200 else b'private backend error'
                self.send_response(status)
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

        server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            request = {"base_url": f"http://127.0.0.1:{server.server_port}",
                       "token": "private-test-token", "control_snapshot": True}
            started = time.monotonic()
            completed = subprocess.run(
                [sys.executable, str(Path(__file__).with_name("VibepolloTransport.py"))],
                input=json.dumps(request), capture_output=True, text=True, timeout=1.4)
            elapsed = time.monotonic() - started
            self.assertEqual(completed.returncode, 0, completed.stderr)
            self.assertLess(elapsed, 1.4)
            self.assertNotIn("private", completed.stdout)
            rows = json.loads(completed.stdout)["control_sources"]
            self.assertEqual(set(rows), {"session", "rtsp", "webrtc", "apps", "clients"})
            self.assertEqual(rows["apps"]["status"], 503)
            self.assertFalse(rows["apps"]["ok"])
            self.assertEqual(sum(row["ok"] for row in rows.values()), 4)
        finally:
            server.shutdown()
            server.server_close()


if __name__ == "__main__":
    unittest.main()
