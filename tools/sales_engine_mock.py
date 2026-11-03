#!/usr/bin/env python3
"""A stand-in for LANARS's Sales Engine API, for trying the app without the stand.

Answers each endpoint of the API document (specs/002-sales-engine-call-logging/
reference) with the document's own bodies - the fixtures in
app/src/test/resources/crm - and prints and keeps every request it gets, so a
check can compare what the app sent with what it must send.

    python3 tools/sales_engine_mock.py [--port 8787] [--numbers numbers.json]

An emulator reaches it at http://10.0.2.2:8787/api/v1 - build the debug app
with salesEngineStandUrl set to that - and a phone over `adb reverse tcp:8787
tcp:8787` at http://127.0.0.1:8787/api/v1. The app then needs a token: set one
with the debug receiver's crm-token op (any 40 hex characters; this mock takes
any token).

What a lookup answers depends on the number's last digit, as the document's
invented numbers do: ...1 one open deal plus a lost one, ...2 two open deals,
...3 a deal with no organisation, ...0 a colleague, anything else not in the
CRM. Step 1 answers in kind: logged, pending, not in the CRM, hidden, internal.
GET /requests returns what was received; DELETE /requests forgets it.

Standard library only.
"""

import argparse
import json
import pathlib
import re
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = pathlib.Path(__file__).resolve().parent.parent
FIXTURES = ROOT / "app/src/test/resources/crm"
received = []
calls = {}  # client_call_id -> stored call object


def fixture(name):
    return json.loads((FIXTURES / f"{name}.json").read_text())


def last_digit(phone):
    digits = re.sub(r"\D", "", phone or "")
    return digits[-1:] if digits else ""


def lookup_for(phone):
    d = last_digit(phone)
    if d == "1":
        return fixture("lookup-deal")
    if d == "2":
        two = fixture("call-pending")["data"]["call"]
        return {"data": {"match": "deal", "candidates": two["candidates"], "candidates_total": 2,
                         "auto_deal_id": None, "suggested_deal_id": two["candidates"][0]["deal_id"]}}
    if d == "3":
        return fixture("lookup-no-org")
    if d == "0":
        return fixture("lookup-internal")
    return fixture("lookup-none")


def call_for(body):
    """Step 1's answer: the document's call object of the matching kind, under this call's id."""
    if body.get("presentation") != "allowed":
        answer = fixture("call-hidden")
    else:
        d = last_digit(body.get("phone"))
        if d == "0":
            answer = fixture("call-internal")
        elif body.get("deal_choice") == "none":
            answer = fixture("call-declined")
        elif body.get("deal_choice") == "deal" or d in ("1", "3"):
            answer = fixture("call-logged")
        elif d == "2":
            answer = fixture("call-pending")
        else:
            answer = fixture("call-not-in-crm")
    c = answer["data"]["call"]
    c["client_call_id"] = body.get("client_call_id")
    if c["id"] is not None:
        c["id"] = 5000 + len(calls) + 1 if body["client_call_id"] not in calls else calls[body["client_call_id"]]["id"]
    if not body.get("transcript_expected", True) and c["status"] != "internal":
        c["content_status"] = "none"
    return answer


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):  # the requests are printed below instead
        pass

    def reply(self, status, body):
        data = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("content-type", "application/json")
        self.send_header("cache-control", "no-store")
        self.send_header("content-length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def body(self):
        n = int(self.headers.get("content-length") or 0)
        raw = self.rfile.read(n).decode() if n else ""
        return raw, (json.loads(raw) if raw else None)

    def handle_any(self, method):
        raw, body = self.body()
        path = self.path.split("?")[0]
        if path == "/requests":
            if method == "DELETE":
                received.clear()
                return self.reply(200, {"data": {"cleared": True}})
            return self.reply(200, {"data": received})
        received.append({"method": method, "path": path, "body": body,
                         "token": bool(self.headers.get("x-api-token")),
                         "version": self.headers.get("x-app-version")})
        print(f"{method} {path} {raw[:200]}", flush=True)
        if not path.startswith("/api/v1/"):
            return self.reply(404, {"error": "not_found", "detail": "unknown endpoint"})
        p = path[len("/api/v1"):]
        if not self.headers.get("x-app-version"):
            return self.reply(426, fixture("error-upgrade"))
        if p != "/mobile/session" and not self.headers.get("x-api-token"):
            return self.reply(401, fixture("error-unauthorized"))
        if method == "POST" and p == "/mobile/session":
            return self.reply(201, fixture("session-201"))
        if method == "DELETE" and p == "/mobile/session":
            return self.reply(200, {"data": {"signed_out": True}})
        if method == "GET" and p == "/mobile/notice":
            return self.reply(200, fixture("notice"))
        if method == "POST" and p == "/mobile/notice":
            return self.reply(200, {"data": {"version": body["version"], "acknowledged_at": "2026-11-03T08:01:12.000Z"}})
        if method == "GET" and p == "/me":
            return self.reply(200, fixture("me"))
        if method == "POST" and p == "/calls/lookup":
            return self.reply(200, lookup_for(body.get("phone")))
        if method == "POST" and p == "/calls":
            known = body["client_call_id"] in calls
            answer = call_for(body)
            calls[body["client_call_id"]] = answer["data"]["call"]
            return self.reply(200 if known else 201, answer)
        m = re.fullmatch(r"/calls/(\d+)(/deal|/content|/private)?", p)
        if m:
            cid = int(m.group(1))
            stored = next((c for c in calls.values() if c["id"] == cid), None)
            if stored is None:
                return self.reply(404, {"error": "not_found", "detail": f"call {cid}"})
            what = m.group(2)
            if what == "/deal":
                if body["deal_choice"] == "none":
                    stored.update(status="declined", candidates=[], candidates_total=0, pending_reason=None)
                else:
                    stored.update(status="logged", deal={"id": body["deal_id"], "title": "Picked deal"},
                                  activity_id=1000793, candidates=[], candidates_total=0, pending_reason=None)
            elif what == "/content":
                if stored["status"] not in ("private", "deleted"):
                    stored["content_status"] = body["content_status"]
            elif what == "/private":
                if stored["status"] == "logged":
                    return self.reply(409, fixture("error-duplicate-logged"))
                stored.update(status="private", content_status="none")
            return self.reply(200, {"data": {"call": stored}})
        return self.reply(405, fixture("error-method"))

    def do_GET(self):
        self.handle_any("GET")

    def do_POST(self):
        self.handle_any("POST")

    def do_DELETE(self):
        self.handle_any("DELETE")


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--port", type=int, default=8787)
    args = ap.parse_args()
    if not FIXTURES.is_dir():
        sys.exit(f"no fixtures at {FIXTURES}")
    server = ThreadingHTTPServer(("0.0.0.0", args.port), Handler)
    print(f"Sales Engine mock on :{args.port}/api/v1 (fixtures from {FIXTURES.relative_to(ROOT)})", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
