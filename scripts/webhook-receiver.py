#!/usr/bin/env python3
"""Minimal NMS webhook receiver for the demo: verifies X-NMS-Signature and prints each event.

    NMS_WEBHOOK_SIGNING_SECRET=... scripts/webhook-receiver.py [port]   # port defaults to 9099

Answers 200 for a valid signature and 401 otherwise. Standard library only.
"""
import hashlib
import hmac
import json
import os
import sys
import time
from http.server import BaseHTTPRequestHandler, HTTPServer

SECRET = os.environ.get("NMS_WEBHOOK_SIGNING_SECRET", "local-dev-only-not-a-secret").encode()
MAX_AGE_SECONDS = 300


class Receiver(BaseHTTPRequestHandler):
    def do_POST(self):
        raw = self.rfile.read(int(self.headers.get("Content-Length", 0)))
        timestamp = self.headers.get("X-NMS-Timestamp", "")
        expected = "sha256=" + hmac.new(SECRET, f"{timestamp}.".encode() + raw, hashlib.sha256).hexdigest()
        signature_ok = hmac.compare_digest(expected, self.headers.get("X-NMS-Signature", ""))
        fresh = timestamp.isdigit() and abs(time.time() - int(timestamp)) <= MAX_AGE_SECONDS
        verified = signature_ok and fresh
        event = json.loads(raw) if verified else {}
        print(json.dumps({
            "path": self.path,
            "verified": verified,
            "idempotencyKey": self.headers.get("Idempotency-Key"),
            "eventId": event.get("eventId"),
            "recipientId": event.get("recipientId"),
            "attempt": event.get("attempt"),
        }), flush=True)
        self.send_response(200 if verified else 401)
        self.end_headers()

    def log_message(self, *args):
        pass  # one JSON line per request is printed above


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 9099
    HTTPServer(("127.0.0.1", port), Receiver).serve_forever()
