#!/usr/bin/env python3
"""Seed demo journeys, customers and login accounts through the public API
(contracts journey-engine.md 3.1 / 3.2 and auth.md 4).

Env (exported by the Makefile from .env): API_PORT, CDP_ADMIN_API_KEY, CDP_INGEST_API_KEY.
Optional: CDP_DEMO_PASSWORD (>= 12 chars) = fixed password for the three demo accounts; unset = random per run.
Customers are written through PUT /api/v1/profiles/{id} (profiles.md 2) with identifiers of every searchable kind
(PROFILE_ID, EMAIL, PHONE, MEMBER_ID, LINE_USER_ID, DEVICE_ID); identifiers carry the run tag (EMAIL, MEMBER_ID, LINE_USER_ID, DEVICE_ID, and an 8-digit PHONE that maps one-to-one to the tag).
Journeys are never created with the admin key: each one goes draft -> demo editor submits -> demo admin
approves (journeys.md 3.1; nobody approves their own submission). The session cookie is Secure and urllib would
not send it over http, so Session below carries Cookie and X-XSRF-TOKEN by hand (auth.md 1.1).
Each run creates a NEW batch (new journeys, new customers, event names with a run tag),
so reruns never collide with earlier data. Keys are never printed.
Demo accounts (one per role) are created through POST /api/v1/users with the admin key; the emails are fixed,
the passwords are CDP_DEMO_PASSWORD when set (not printed), otherwise random per run and printed once
(a rerun resets them through PATCH /api/v1/users/{id}).
"""
import json
import os
import secrets
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timedelta, timezone

SHORT_WAIT_SECONDS = 15
LONG_WAIT = "PT2H"
POLL_LIMIT_SECONDS = 60
CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"


def fail(msg):
    print("demo-seed: ERROR: " + msg, file=sys.stderr)
    sys.exit(1)


def ulid():
    ms = int(time.time() * 1000)
    out = ""
    for _ in range(10):
        out = CROCKFORD[ms & 31] + out
        ms >>= 5
    return out + "".join(secrets.choice(CROCKFORD) for _ in range(16))


def env(name):
    v = os.environ.get(name, "")
    if not v or v == "CHANGE_ME":
        fail("environment variable %s is not set (set it in .env or on the make command line)" % name)
    return v


PORT = env("API_PORT")
ADMIN_KEY = env("CDP_ADMIN_API_KEY")
INGEST_KEY = env("CDP_INGEST_API_KEY")
BASE = "http://127.0.0.1:%s" % PORT
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def call(method, path, key, body=None, what="", tolerate=()):
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Authorization", "Bearer " + key)
    if data is not None:
        req.add_header("Content-Type", "application/json")
    try:
        with OPENER.open(req, timeout=15) as r:
            raw = r.read()
            return json.loads(raw) if raw else {}
    except urllib.error.HTTPError as e:
        try:
            p = json.loads(e.read())
            extra = " %s: %s" % (p.get("code", ""), p.get("detail", ""))
            for er in p.get("errors", []) or []:
                extra += " [%s %s]" % (er.get("field"), er.get("message"))
        except Exception:
            extra = ""
        if e.code in tolerate:
            return {"_status": e.code}
        if e.code == 401:
            fail("%s returned 401 (API key rejected; check the key matches the running backend)" % what)
        if e.code == 403:
            fail("%s returned 403 (key has no permission for this API)" % what)
        fail("%s returned HTTP %d.%s" % (what, e.code, extra))
    except (urllib.error.URLError, OSError) as e:
        fail("cannot reach the API at 127.0.0.1:%s (%s). Start it with `make api-run` first."
             % (PORT, getattr(e, "reason", e)))


DEMO_USERS = [
    ("viewer", "VIEWER", "Demo viewer"),
    ("editor", "EDITOR", "Demo editor"),
    ("admin", "ADMIN", "Demo admin"),
]


def ensure_user(role_key, role, display_name):
    """Create the demo account, or reset its password and re-enable it when it already exists."""
    email = "demo-%s@example.com" % role_key
    fixed = os.environ.get("CDP_DEMO_PASSWORD")
    password = fixed or secrets.token_urlsafe(12)
    body = {"email": email, "display_name": display_name, "role": role, "password": password}
    r = call("POST", "/api/v1/users", ADMIN_KEY, body, "POST /api/v1/users", tolerate=(409,))
    if r.get("_status") == 409:
        users = call("GET", "/api/v1/users", ADMIN_KEY, None, "GET /api/v1/users").get("items", [])
        found = [u for u in users if u["email"] == email]
        if not found:
            fail("POST /api/v1/users returned 409 but %s is not in the user list" % email)
        patch = {"display_name": display_name, "role": role, "status": "ACTIVE", "password": password}
        call("PATCH", "/api/v1/users/" + found[0]["user_id"], ADMIN_KEY, patch, "PATCH /api/v1/users")
    return email, role, ("(CDP_DEMO_PASSWORD)" if fixed else password)


class Session:
    """Demo account session: Cookie and X-XSRF-TOKEN are handled by hand (the cookie is Secure)."""

    def __init__(self, email, password):
        self.cookies = {}
        self.email = email
        st, _ = self._send("POST", "/api/v1/auth/login", {"email": email, "password": password}, False)
        if st != 204:
            fail("login of %s returned HTTP %s (check CDP_DEMO_PASSWORD, or rerun so the accounts are reset)" % (email, st))
        st, _ = self._send("GET", "/api/v1/auth/me", None, False)
        if st != 200 or "XSRF-TOKEN" not in self.cookies:
            fail("GET /api/v1/auth/me for %s returned HTTP %s" % (email, st))

    def _send(self, method, path, body, csrf):
        data = json.dumps(body).encode("utf-8") if body is not None else None
        req = urllib.request.Request(BASE + path, data=data, method=method)
        if data is not None:
            req.add_header("Content-Type", "application/json")
        if self.cookies:
            req.add_header("Cookie", "; ".join("%s=%s" % kv for kv in self.cookies.items()))
        if csrf and "XSRF-TOKEN" in self.cookies:
            req.add_header("X-XSRF-TOKEN", urllib.parse.unquote(self.cookies["XSRF-TOKEN"]))
        try:
            r = OPENER.open(req, timeout=15)
            status, raw, hdrs = r.status, r.read(), r.headers
        except urllib.error.HTTPError as e:
            status, raw, hdrs = e.code, e.read(), e.headers
        except (urllib.error.URLError, OSError) as e:
            fail("cannot reach the API at 127.0.0.1:%s (%s)" % (PORT, getattr(e, "reason", e)))
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

    def call(self, method, path, body, what):
        st, js = self._send(method, path, body, method != "GET")
        if st == 403 and isinstance(js, dict) and js.get("code") == "CSRF_INVALID":
            self._send("GET", "/api/v1/auth/me", None, False)  # refresh XSRF-TOKEN, retry once
            st, js = self._send(method, path, body, method != "GET")
        if st >= 400:
            extra = ""
            if isinstance(js, dict):
                extra = " %s: %s" % (js.get("code", ""), js.get("detail", ""))
                for er in js.get("errors", []) or []:
                    extra += " [%s %s]" % (er.get("field"), er.get("message"))
            fail("%s as %s returned HTTP %d.%s" % (what, self.email, st, extra))
        return js or {}


def publish(editor, admin, definition, what):
    """Draft -> editor submits -> a different admin approves. Returns (journey_id, version)."""
    jid = editor.call("POST", "/api/v1/journeys/drafts", {"name": definition["name"]}, "create draft")["journey_id"]
    base = "/api/v1/journeys/" + jid
    saved = editor.call("PUT", base + "/draft", {"revision": 1, "definition": definition, "layout": None},
                        "save draft " + what)
    review = editor.call("POST", base + "/draft/submit", {"revision": saved["revision"]}, "submit " + what)
    done = admin.call("POST", "%s/reviews/%s/approve" % (base, review["review_id"]), {}, "approve " + what)
    return jid, done.get("version", 1)


def node(nid, ntype, name, config, outputs):
    return {"id": nid, "type": ntype, "name": name, "config": config, "outputs": outputs}


def scenario_one(tag, cart_event, order_event):
    """Reduced scenario one (D-046, spec 03 s7 without WebhookNode / UpdateProfileNode; amount > 500 filter kept, D-085)."""
    send = {"template_id": "tpl_demo_cart", "channel_id": "ch_demo_mock", "on_skip": "CONTINUE"}
    return {
        "name": "Demo cart recovery (scenario one) " + tag,
        "description": "demo-seed reduced scenario one",
        "settings": {
            "reentry": {"mode": "DEDUP_KEY", "dedup_key_path": "$.trigger.properties.cart_id"},
            "goal": {"goal_id": "checkout", "condition": {"type": "event", "event": order_event}, "window": "P7D"},
        },
        "entry_node": "start",
        "nodes": [
            node("start", "StartNode", None, {}, {"next": "src_1"}),
            node("src_1", "EventSourceNode", "Cart item added",
                 {"event_name": cart_event,
                  "event_filter": {"type": "attribute", "field": "properties.amount", "operator": "GT", "value": 500}},
                 {"next": "wait_1"}),
            node("wait_1", "WaitDelayNode", "Wait 1 hour", {"duration": "PT1H"}, {"next": "cond_vip"}),
            node("cond_vip", "ConditionNode", "VIP member?",
                 {"condition": {"type": "attribute", "field": "member_tier", "operator": "IN",
                                "value": ["GOLD", "PLATINUM"]}},
                 {"yes": "send_vip", "no": "send_normal"}),
            node("send_vip", "SendMessageNode", "VIP reminder", send, {"sent": "merge_1", "skipped": "merge_1"}),
            node("send_normal", "SendMessageNode", "Standard reminder", send,
                 {"sent": "merge_1", "skipped": "merge_1"}),
            node("merge_1", "MergeNode", None, {}, {"next": "wait_order_1"}),
            node("wait_order_1", "WaitForEventNode", "Wait for order (1 day)",
                 {"event_name": order_event, "timeout": "P1D"}, {"occurred": "goal_1", "timeout": "split_1"}),
            node("goal_1", "GoalNode", "Checked out", {"goal_id": "checkout"}, {}),
            node("split_1", "SplitTestNode", "A/B coupon",
                 {"variants": [{"port": "coupon", "weight": 50, "label": "Coupon"},
                               {"port": "control", "weight": 50, "label": "Control", "is_control": True}],
                  "assignment_seed": "STABLE"},
                 {"coupon": "send_coupon", "control": "exit_control"}),
            node("exit_control", "ExitNode", "Control group", {"reason": "control group"}, {}),
            node("send_coupon", "SendMessageNode", "Coupon reminder", send,
                 {"sent": "wait_order_2", "skipped": "wait_order_2"}),
            node("wait_order_2", "WaitForEventNode", "Wait for order (2 days)",
                 {"event_name": order_event, "timeout": "P2D"}, {"occurred": "goal_1", "timeout": "end_1"}),
            node("end_1", "EndNode", "End", {}, {}),
        ],
    }


def loop_journey(tag, entry_event, order_event):
    """Loop journey: wait for the order up to 1 hour, remind on timeout and wait again (laps are unlimited, D-050)."""
    return {
        "name": "Demo reminder loop " + tag,
        "description": "demo-seed loop journey",
        "settings": {},
        "entry_node": "start",
        "nodes": [
            node("start", "StartNode", None, {}, {"next": "src_1"}),
            node("src_1", "EventSourceNode", "Entry event", {"event_name": entry_event}, {"next": "merge_1"}),
            node("merge_1", "MergeNode", None, {}, {"next": "wait_1"}),
            node("wait_1", "WaitForEventNode", "Wait for order (1 hour)",
                 {"event_name": order_event, "timeout": "PT1H"}, {"occurred": "goal_1", "timeout": "send_1"}),
            node("send_1", "SendMessageNode", "Reminder",
                 {"template_id": "tpl_demo_cart", "channel_id": "ch_demo_mock"},
                 {"sent": "merge_1", "skipped": "merge_1"}),
            node("goal_1", "GoalNode", "Ordered", {"goal_id": "ordered"}, {}),
        ],
    }


def journey(name, event_name, wait):
    return {
        "name": name,
        "description": "demo-seed sample journey",
        "settings": {},
        "entry_node": "start",
        "nodes": [
            {"id": "start", "type": "StartNode", "name": "Start", "config": {}, "outputs": {"next": "src"}},
            {"id": "src", "type": "EventSourceNode", "name": "Cart event",
             "config": {"event_name": event_name}, "outputs": {"next": "wait"}},
            {"id": "wait", "type": "WaitDelayNode", "name": "Wait " + wait,
             "config": {"duration": wait}, "outputs": {"next": "send"}},
            {"id": "send", "type": "SendMessageNode", "name": "Reminder",
             "config": {"template_id": "tpl_demo_cart", "channel_id": "ch_demo_mock"},
             "outputs": {"sent": "end"}},
            {"id": "end", "type": "EndNode", "name": "End", "config": {}, "outputs": {}},
        ],
    }


def put_profile(profile_id, who, n, tag):
    """Create the customer with one identifier of each searchable kind. Returns the (unmasked) identifiers."""
    ids = {
        "EMAIL": "%s-%s@example.com" % (who, tag),
        "PHONE": "+886912%08d" % (int(tag, 16) * 10 + n),
        "MEMBER_ID": "M-%s-%d" % (tag.upper(), n),
        "LINE_USER_ID": "U%s%02d" % (tag, n),
        "DEVICE_ID": "dev-%s-%d" % (tag, n),
    }
    body = {"attributes": {"name": who.capitalize(), "tier": "demo"},
            "identifiers": [{"type": k, "value": v} for k, v in ids.items()]}
    call("PUT", "/api/v1/profiles/" + profile_id, INGEST_KEY, body, "PUT /api/v1/profiles")
    return ids


def send_event(profile_id, event_name, who, n):
    ts = (datetime.now(timezone.utc) - timedelta(seconds=2)).strftime("%Y-%m-%dT%H:%M:%SZ")
    ev = {
        "event_id": ulid(),
        "source_type": "EXTERNAL_SYSTEM",
        "source_id": "demo-seed",
        "name": event_name,
        "occurred_at": ts,
        "identity": {"profile_id": profile_id},
        "properties": {
            "email": "%s@example.com" % who,
            "phone": "0912-345-%03d" % n,
            "cart_id": "CART-DEMO-%s-%d" % (who.upper(), n),
            "cart_total": 1580 + n,
        },
        "context": {"trust_level": "VERIFIED"},
    }
    r = call("POST", "/api/v1/events", INGEST_KEY, ev, "POST /api/v1/events")
    if r.get("status") not in ("ACCEPTED", "DUPLICATE"):
        fail("event for %s was not accepted: %s" % (who, r.get("status")))


def enrollments(profile_id):
    r = call("GET", "/api/v1/enrollments?q=" + profile_id, ADMIN_KEY, None, "GET /api/v1/enrollments")
    return r.get("items", [])


def main():
    tag = secrets.token_hex(3)
    accounts = [ensure_user(*u) for u in DEMO_USERS]
    ev_short, ev_long = "demo_short_" + tag, "demo_long_" + tag
    pw = {email: password for email, _, password in accounts}
    fixed = os.environ.get("CDP_DEMO_PASSWORD")
    editor = Session("demo-editor@example.com", fixed or pw["demo-editor@example.com"])
    admin = Session("demo-admin@example.com", fixed or pw["demo-admin@example.com"])
    jsid, jsv = publish(editor, admin, journey("Demo short wait (%ds) %s" % (SHORT_WAIT_SECONDS, tag), ev_short,
                                               "PT%dS" % SHORT_WAIT_SECONDS), "short-wait journey")
    jlid, jlv = publish(editor, admin, journey("Demo long wait (%s) %s" % (LONG_WAIT, tag), ev_long, LONG_WAIT),
                        "long-wait journey")
    j1id, j1v = publish(editor, admin, scenario_one(tag, "demo_cart_" + tag, "demo_order_" + tag), "scenario one")
    jpid, jpv = publish(editor, admin, loop_journey(tag, "demo_loop_" + tag, "demo_loop_order_" + tag),
                        "loop journey")

    alice, bob, carol = ulid(), ulid(), ulid()
    searchable = {w: put_profile(p, w, i, tag)
                  for i, (w, p) in enumerate((("alice", alice), ("bob", bob), ("carol", carol)), 1)}
    send_event(alice, ev_short, "alice", 1)
    send_event(bob, ev_long, "bob", 2)
    send_event(carol, ev_short, "carol", 3)
    send_event(carol, ev_long, "carol", 4)

    customers = [
        ("alice", alice, 1, "COMPLETED (after the short wait; 1 SENT delivery)"),
        ("bob", bob, 1, "WAITING (long wait of %s)" % LONG_WAIT),
        ("carol", carol, 2, "2 enrollments: 1 COMPLETED (short) + 1 WAITING (long)"),
    ]

    # Best effort: wait until enrollments exist and the short ones complete.
    deadline = time.time() + POLL_LIMIT_SECONDS
    done = False
    while time.time() < deadline:
        a, b, c = enrollments(alice), enrollments(bob), enrollments(carol)
        done = (len(a) == 1 and a[0]["status"] == "COMPLETED"
                and len(b) == 1 and b[0]["status"] == "WAITING"
                and len(c) == 2 and sorted(x["status"] for x in c) == ["COMPLETED", "WAITING"])
        if done:
            break
        time.sleep(2)

    print("demo-seed: batch %s created (new journeys + new customers; rerun creates another batch)" % tag)
    print("  journey (short): %s v%s  | journey (long): %s v%s" % (jsid, jsv, jlid, jlv))
    print("  journey (scenario one): %s v%s  | journey (loop): %s v%s" % (j1id, j1v, jpid, jpv))
    print("  all four: submitted by demo-editor, approved by demo-admin (no admin-key shortcut)")
    print("Demo login accounts (%s):" % ("password = CDP_DEMO_PASSWORD" if os.environ.get("CDP_DEMO_PASSWORD") else "passwords are new on every run"))
    for email, role, password in accounts:
        print("  %-7s %s  %s" % (role, email, password))
    print("Search these in the customer page (profile_id, e-mail, phone, member id, LINE user id, device id):")
    for name, pid, _, expect in customers:
        i = searchable[name]
        print("  %-6s %s  expect: %s" % (name, pid, expect))
        print("         email=%s phone=%s member=%s line=%s device=%s" % (
            i["EMAIL"], i["PHONE"], i["MEMBER_ID"], i["LINE_USER_ID"], i["DEVICE_ID"]))
    if done:
        print("Verified via API: all expected statuses reached.")
    else:
        print("Not yet at expected statuses after %ds (engine poll interval / backlog)."
              % POLL_LIMIT_SECONDS)
    print("Note: short-wait enrollments become COMPLETED about %d seconds after enrolling "
          "(plus up to one ENGINE_POLL_INTERVAL); refresh the page if it still shows WAITING."
          % SHORT_WAIT_SECONDS)


main()
