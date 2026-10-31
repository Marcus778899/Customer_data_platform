#!/usr/bin/env python3
"""API-level end-to-end check of everything the frontend calls, against a running backend (make api-run)
that was seeded by seed.py (run `make demo-seed` first, with the same CDP_DEMO_PASSWORD). Run via `make demo-verify`.

Env: API_PORT, CDP_ADMIN_API_KEY, CDP_INGEST_API_KEY, CDP_DEMO_PASSWORD. Secrets are never printed.
Cookies are handled by hand (the session cookie is Secure and urllib would not send it over http).
Usage: python3 tools/demo-seed/verify.py
"""
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request

BASE = "http://127.0.0.1:%s" % os.environ["API_PORT"]
ADMIN_KEY = os.environ["CDP_ADMIN_API_KEY"]
INGEST_KEY = os.environ["CDP_INGEST_API_KEY"]
PASSWORD = os.environ.get("CDP_DEMO_PASSWORD") or sys.exit("verify: set CDP_DEMO_PASSWORD (same value used for make demo-seed)")
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))
FAILS = []


class Session:
    def __init__(self, key=None):
        self.key, self.cookies = key, {}

    def call(self, method, path, body=None, csrf=True):
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(BASE + path, data=data, method=method)
        if body is not None:
            req.add_header("Content-Type", "application/json")
        if self.key:
            req.add_header("Authorization", "Bearer " + self.key)
        elif self.cookies:
            req.add_header("Cookie", "; ".join("%s=%s" % kv for kv in self.cookies.items()))
            if method != "GET" and csrf and "XSRF-TOKEN" in self.cookies:
                req.add_header("X-XSRF-TOKEN", urllib.parse.unquote(self.cookies["XSRF-TOKEN"]))
        try:
            r = OPENER.open(req, timeout=15)
            status, raw, hdrs = r.status, r.read(), r.headers
        except urllib.error.HTTPError as e:
            status, raw, hdrs = e.code, e.read(), e.headers
        for h in hdrs.get_all("Set-Cookie") or []:
            name, _, rest = h.partition("=")
            val = rest.split(";")[0]
            if "Max-Age=0" in h or val == "":
                self.cookies.pop(name, None)
            else:
                self.cookies[name] = val
        try:
            js = json.loads(raw) if raw else None
        except ValueError:
            js = None
        return status, js

    def login(self, email):
        s, _ = self.call("POST", "/api/v1/auth/login", {"email": email, "password": PASSWORD}, csrf=False)
        assert s == 204, "login %s -> %s" % (email, s)
        s, me = self.call("GET", "/api/v1/auth/me")
        assert s == 200 and "XSRF-TOKEN" in self.cookies, "me after login -> %s" % s
        return me


def check(label, cond, extra=""):
    print("  [%s] %s %s" % ("ok" if cond else "FAIL", label, extra))
    if not cond:
        FAILS.append(label)


def mask_note(profile):
    return [i["value"] for i in profile["identifiers"]]


def main():
    sessions = {}
    print("1. login per role (auth.md 3) and /auth/me")
    for role in ("viewer", "editor", "admin"):
        s = Session()
        me = s.login("demo-%s@example.com" % role)
        check("login %s" % role, me["role"] == role.upper(), "-> /auth/me role=%s" % me["role"])
        sessions[role] = s
    bad = Session()
    st, js = bad.call("POST", "/api/v1/auth/login", {"email": "demo-viewer@example.com", "password": "wrong-password-xx"}, csrf=False)
    check("wrong password -> 401 INVALID_CREDENTIALS", st == 401 and js["code"] == "INVALID_CREDENTIALS")
    st, _ = Session().call("GET", "/api/v1/journeys")
    check("no credential -> 401", st == 401)

    print("2. role matrix (auth.md 2): expected status per role for sample calls")
    ev = Session(INGEST_KEY)
    st, _ = ev.call("GET", "/api/v1/journeys")
    check("INGEST key GET /journeys -> 403", st == 403)
    matrix = [
        ("GET", "/api/v1/enrollments", None, {"viewer": 200, "editor": 200, "admin": 200}),
        ("GET", "/api/v1/profiles/search?q=x", None, {"viewer": 200, "editor": 200, "admin": 200}),
        ("GET", "/api/v1/journeys", None, {"viewer": 200, "editor": 200, "admin": 200}),
        ("GET", "/api/v1/users", None, {"viewer": 403, "editor": 403, "admin": 200}),
        ("POST", "/api/v1/events", {}, {"viewer": 403, "editor": 403, "admin": 403}),
        ("PUT", "/api/v1/profiles/01JD0000000000000000000000", {}, {"viewer": 403, "editor": 403, "admin": 403}),
        ("POST", "/api/v1/journeys", {}, {"viewer": 403, "editor": 403, "admin": 403}),
        ("PATCH", "/api/v1/users/01JD0000000000000000000000", {"display_name": "x"},
         {"viewer": 403, "editor": 403, "admin": 404}),
    ]
    for m, p, b, exp in matrix:
        for role, want in exp.items():
            st, _ = sessions[role].call(m, p, b)
            check("%-6s %-40s %-6s -> %s" % (m, p.split("?")[0], role, want), st == want, "got %s" % st)
    st, js = sessions["viewer"].call("POST", "/api/v1/users", {"email": "x@example.com"}, csrf=False)
    check("session write without X-XSRF-TOKEN -> 403 CSRF_INVALID (viewer may not reach users; csrf checked first)",
          st == 403 and js and js.get("code") == "CSRF_INVALID", "got %s %s" % (st, js and js.get("code")))
    st, _ = Session(ADMIN_KEY).call("GET", "/api/v1/users")
    check("admin key GET /users -> 200", st == 200)

    print("3. search four ways -> customer page -> graph trace (profiles.md 3, journey-engine 4)")
    v = sessions["viewer"]
    _, enr = v.call("GET", "/api/v1/enrollments?limit=100")
    pid = None
    # newest batch's customers: take the profile with 2 enrollments among the newest 6 items
    by_profile = {}
    for e in enr["items"][:6]:
        by_profile.setdefault(e["profile_id"], []).append(e)
    pid = next(p for p, es in by_profile.items() if len(es) == 2)
    st, prof = v.call("GET", "/api/v1/profiles/" + pid)
    check("GET /profiles/{id} 200 with masked identifiers", st == 200 and all(i["masked"] for i in prof["identifiers"] if i["type"] in ("EMAIL", "PHONE")),
          "identifiers=%s masked_paths=%d" % (mask_note(prof), len(prof["masked_paths"])))
    # identifiers are masked in responses, so create a fresh customer here to know the plain values
    import secrets
    tag, newp = secrets.token_hex(3), "01" + "".join(secrets.choice("0123456789ABCDEFGHJKMNPQRSTVWXYZ") for _ in range(24))
    plain = {"PROFILE_ID": newp, "EMAIL": "verify-%s@example.com" % tag,
             "PHONE": "+886913%06d" % (int(tag, 16) % 1000000), "MEMBER_ID": "V-%s" % tag.upper()}
    st, _ = ev.call("PUT", "/api/v1/profiles/" + newp, {"identifiers": [
        {"type": k, "value": q} for k, q in plain.items() if k != "PROFILE_ID"]})
    check("PUT /profiles creates the verification customer", st == 200)
    for kind, q in plain.items():
        st, js = v.call("GET", "/api/v1/profiles/search?q=" + urllib.parse.quote(q))
        hit = [i for i in js["items"] if i["profile_id"] == newp] if st == 200 else []
        check("search by %-10s -> that customer" % kind, len(hit) == 1 and hit[0]["matched_by"] == kind,
              "matched_by=%s items=%d" % (hit and hit[0]["matched_by"], len(js["items"]) if js else -1))
    st, js = v.call("GET", "/api/v1/profiles/%s/enrollments" % pid)
    check("GET /profiles/{id}/enrollments", st == 200 and len(js["items"]) == 2, "items=%s" % [e["status"] for e in js["items"]])
    done = next(e for e in js["items"] if e["status"] == "COMPLETED")
    st, d = v.call("GET", "/api/v1/enrollments/" + done["enrollment_id"])
    check("GET /enrollments/{id} detail with trace", st == 200 and len(d["trace"]) >= 4,
          "trace=%s" % [(t["node_id"], t["out_port"]) for t in d["trace"]])
    st, dl = v.call("GET", "/api/v1/enrollments/%s/deliveries" % done["enrollment_id"])
    check("GET deliveries", st == 200 and dl["items"] and dl["items"][0]["status"] == "SENT", "status=%s" % [x["status"] for x in dl["items"]])
    st, ver = v.call("GET", "/api/v1/journeys/%s/versions/%s" % (d["journey_id"], d["journey_version"]))
    ids = {n["id"] for n in ver["nodes"]} if st == 200 else set()
    check("GET journey version -> graph nodes cover trace nodes", st == 200 and {t["node_id"] for t in d["trace"]} <= ids,
          "nodes=%d layout=%s" % (len(ids), ver.get("layout")))

    print("4. journey list -> read-only journey page (journeys.md 2)")
    st, jl = v.call("GET", "/api/v1/journeys?limit=5")
    check("GET /journeys Page<JourneySummary>", st == 200 and jl["items"] and all(
        k in jl["items"][0] for k in ("journey_id", "name", "status", "latest_version", "has_draft", "review_status", "updated_at", "published_at")),
          "first=%s" % {k: jl["items"][0][k] for k in ("name", "status", "latest_version")})
    st, jd = v.call("GET", "/api/v1/journeys/" + jl["items"][0]["journey_id"])
    check("GET /journeys/{id} JourneyDetail", st == 200 and jd["versions"] and "stop_reason" in jd, "versions=%s" % [x["version"] for x in jd["versions"]])
    st, js = v.call("GET", "/api/v1/journeys?status=ACTIVE&q=demo")
    check("GET /journeys?status&q filter", st == 200 and all(i["status"] == "ACTIVE" for i in js["items"]))
    st, js = v.call("GET", "/api/v1/journeys/01JD0000000000000000000000")
    check("unknown journey -> 404 JOURNEY_NOT_FOUND", st == 404 and js["code"] == "JOURNEY_NOT_FOUND")

    print("5. enrollment browse (journey-engine 4)")
    st, js = v.call("GET", "/api/v1/enrollments?status=WAITING&limit=2")
    check("browse without q + status filter", st == 200 and all(e["status"] == "WAITING" for e in js["items"]), "items=%d next_cursor=%s" % (len(js["items"]), bool(js["next_cursor"])))
    if js["next_cursor"]:
        st, js2 = v.call("GET", "/api/v1/enrollments?status=WAITING&limit=2&cursor=" + urllib.parse.quote(js["next_cursor"]))
        check("cursor page 2", st == 200)
    st, js = v.call("GET", "/api/v1/enrollments/01JD0000000000000000000000")
    check("unknown enrollment -> 404 ENROLLMENT_NOT_FOUND", st == 404 and js["code"] == "ENROLLMENT_NOT_FOUND")

    print("6. password change + logout")
    e = Session()
    e.login("demo-editor@example.com")
    st, js = e.call("POST", "/api/v1/auth/password", {"current_password": "wrong-password-xx", "new_password": PASSWORD})
    check("POST /auth/password wrong current -> 401 INVALID_CREDENTIALS", st == 401 and js["code"] == "INVALID_CREDENTIALS")
    st, _ = e.call("POST", "/api/v1/auth/logout", csrf=False)
    st2, _ = e.call("GET", "/api/v1/auth/me")
    check("logout -> 204, then /auth/me 401", st == 204 and st2 == 401)

    print("RESULT: %s" % ("all checks passed" if not FAILS else "%d FAILED: %s" % (len(FAILS), FAILS)))
    sys.exit(1 if FAILS else 0)


main()
