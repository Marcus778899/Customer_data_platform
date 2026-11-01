#!/usr/bin/env python3
"""Relay / loop load test (DESIGN-03 "perf test (QA-06, D-038)"; entry: `make perf`).

For each size in PERF_SIZES it builds a backlog of rows that all become due at the same instant and measures how the
running backend drains it, in three isolated scenarios:
  advance   N WAITING enrollments due at T   (AdvanceLoop -> then relay -> consumer for the effects it creates)
  relay     N PENDING outbox rows due at T   (OutboxRelay -> consumer)
  dispatch  N undispatched events due at T   (EventDispatcher; events carry next_dispatch_at = T)
Needs: `make infra-up` and a running backend (`make api-run`) on the same PostgreSQL/RabbitMQ. It never starts or stops
anything. All rows it creates have ids starting with `perf-`, and journeys are named `perf-...`; it deletes only those.

Env (exported by the Makefile from .env): POSTGRES_*, API_PORT, CDP_ADMIN_API_KEY, CDP_INGEST_API_KEY, OUTBOX_RELAY_*,
ENGINE_*, RABBITMQ_* ... Optional, passed as make variables: PERF_SIZES (default 10000,100000),
PERF_TIMEOUT (seconds per scenario, default 3600), PERF_OUT (report file, default docs/perf/last-run.md).
Secrets are never printed.
"""
import json
import os
import re
import secrets
import statistics
import sys
import threading
import time
import urllib.error
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import pg  # noqa: E402
from pg import lit  # noqa: E402

CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
TABLES = ["enrollment_record", "event", "effect_outbox"]
# non-secret variables worth recording in the report
RECORDED_VARS = [
    "POSTGRES_HOST", "RABBITMQ_HOST", "ENGINE_POLL_INTERVAL", "ENGINE_BATCH_SIZE",
    "ENGINE_DISPATCH_MAX_ATTEMPTS", "OUTBOX_RELAY_POLL_INTERVAL", "OUTBOX_RELAY_BATCH_SIZE",
    "OUTBOX_RELAY_RETRY_DELAY", "OUTBOX_RELAY_CONFIRM_TIMEOUT", "OUTBOX_RELAY_LEASE",
    "DELIVERY_REQUEUE_DELAY", "CDP_SCHEDULER_POOL_SIZE", "CDP_LOOP_MAX_DRAIN",
]


def log(msg):
    print("perf: " + msg, flush=True)


def fail(msg):
    print("perf: ERROR: " + msg, file=sys.stderr, flush=True)
    sys.exit(1)


def env(name):
    v = os.environ.get(name, "")
    if not v or v == "CHANGE_ME":
        fail("environment variable %s is not set (set it in .env)" % name)
    return v


def seconds(iso):
    """ISO-8601 duration limited to PTnH / PTnM / PTnS combinations (what .env.example uses)."""
    m = re.fullmatch(r"PT(?:(\d+(?:\.\d+)?)H)?(?:(\d+(?:\.\d+)?)M)?(?:(\d+(?:\.\d+)?)S)?", iso or "")
    if not m or not any(m.groups()):
        fail("cannot parse duration %r" % iso)
    h, mi, s = (float(x) if x else 0.0 for x in m.groups())
    return h * 3600 + mi * 60 + s


def ulid():
    ms = int(time.time() * 1000)
    out = ""
    for _ in range(10):
        out = CROCKFORD[ms & 31] + out
        ms >>= 5
    return out + "".join(secrets.choice(CROCKFORD) for _ in range(16))


# ------------------------------------------------------------------ API (templates only)

OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def api(method, path, key, body=None):
    base = "http://127.0.0.1:%s" % env("API_PORT")
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(base + path, data=data, method=method)
    req.add_header("Authorization", "Bearer " + key)
    if data is not None:
        req.add_header("Content-Type", "application/json")
    try:
        with OPENER.open(req, timeout=15) as r:
            raw = r.read()
            return json.loads(raw) if raw else {}
    except urllib.error.HTTPError as e:
        fail("%s %s returned HTTP %d" % (method, path, e.code))
    except (urllib.error.URLError, OSError) as e:
        fail("cannot reach the backend at 127.0.0.1:%s (%s). Start it with `make api-run`." % (env("API_PORT"), e))


def journey(name, event_name, wait):
    return {
        "name": name, "description": "perf template", "settings": {}, "entry_node": "start",
        "nodes": [
            {"id": "start", "type": "StartNode", "name": "Start", "config": {}, "outputs": {"next": "src"}},
            {"id": "src", "type": "EventSourceNode", "name": "Event",
             "config": {"event_name": event_name}, "outputs": {"next": "wait"}},
            {"id": "wait", "type": "WaitDelayNode", "name": "Wait", "config": {"duration": wait},
             "outputs": {"next": "send"}},
            {"id": "send", "type": "SendMessageNode", "name": "Send",
             "config": {"template_id": "tpl_perf", "channel_id": "ch_perf_mock"}, "outputs": {"sent": "end"}},
            {"id": "end", "type": "EndNode", "name": "End", "config": {}, "outputs": {}},
        ],
    }


def send_event(event_id, profile_id, name):
    ts = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(time.time() - 2))
    ev = {"event_id": event_id, "source_type": "EXTERNAL_SYSTEM", "source_id": "perf", "name": name,
          "occurred_at": ts, "identity": {"profile_id": profile_id}, "properties": {},
          "context": {"trust_level": "VERIFIED"}}
    r = api("POST", "/api/v1/events", env("CDP_INGEST_API_KEY"), ev)
    if r.get("status") not in ("ACCEPTED", "DUPLICATE"):
        fail("template event not accepted: %s" % r.get("status"))


# ------------------------------------------------------------------ DB helpers

def connect():
    return pg.Connection("127.0.0.1", env("POSTGRES_PORT"), env("POSTGRES_USER"),
                         env("POSTGRES_PASSWORD"), env("POSTGRES_DB"))


def wait_until(fn, what, limit=120, step=1.0):
    end = time.time() + limit
    while time.time() < end:
        v = fn()
        if v:
            return v
        time.sleep(step)
    fail("timed out waiting for " + what)


PERF_ENROLLMENTS = ("select enrollment_id from enrollment_record where enrollment_id like 'perf-%' or journey_id in "
                    "(select journey_id from journey_definition where name like 'perf-%')")


def cleanup(c):
    """Delete only perf-* rows (ids starting with perf-, journeys named perf-, and what hangs off them)."""
    c.query(
        "begin;"
        "delete from mock_channel_message where enrollment_id in (%(e)s);"
        "delete from delivery_task where enrollment_id in (%(e)s);"
        "delete from effect_outbox where enrollment_id in (%(e)s) or idempotency_key like 'perf-%%';"
        "delete from node_trace where enrollment_id in (%(e)s);"
        "delete from enrollment_record where enrollment_id in (%(e)s);"
        "delete from event where event_id like 'perf-%%' or name like 'perf\\_%%';"
        "delete from profile_identifier where profile_id like 'perf-%%';"
        "delete from profile where profile_id like 'perf-%%';"
        "delete from journey_definition where name like 'perf-%%';"
        "commit;" % {"e": PERF_ENROLLMENTS})


def row_counts(c):
    """Row counts of non-perf rows: used to show that existing dev data is untouched by the run."""
    q = ("select 'journey_definition', count(*) from journey_definition where name not like 'perf-%' union all "
         "select 'enrollment_record', count(*) from enrollment_record where enrollment_id not like 'perf-%' "
         "and journey_id not in (select journey_id from journey_definition where name like 'perf-%') union all "
         "select 'event', count(*) from event where event_id not like 'perf-%' union all "
         "select 'profile', count(*) from profile where profile_id not like 'perf-%'")
    return {r[0]: int(r[1]) for r in c.rows(q)}


def table_stats(c):
    """n_dead_tup / sizes of the three tables. Waits for the cumulative statistics to be flushed first."""
    time.sleep(3.0)
    out = {}
    for t in TABLES:
        r = c.rows("select n_live_tup, n_dead_tup, pg_table_size(%s::regclass), pg_indexes_size(%s::regclass) "
                   "from pg_stat_user_tables where relname = %s" % (lit(t), lit(t), lit(t)))[0]
        out[t] = {"live": int(r[0]), "dead": int(r[1]), "table_bytes": int(r[2]), "index_bytes": int(r[3])}
    return out


def mb(n):
    return "%.1f" % (n / 1048576.0)


# ------------------------------------------------------------------ sampler

class Sampler(threading.Thread):
    """~4 samples/s of pg_stat_activity on its own connection."""

    def __init__(self):
        super().__init__(daemon=True)
        self.stop = threading.Event()
        self.samples = 0
        self.waits = {}
        self.subtrans_slru = 0
        self.subtrans_buffer = 0
        self.overflow_samples = 0
        self.overflow_max = 0
        self.subxact_max = 0
        self.active_max = 0

    def run(self):
        c = connect()
        try:
            while not self.stop.is_set():
                res = c.query(
                    "select coalesce(wait_event, '(running)'), count(*) from pg_stat_activity "
                    "where datname = current_database() and pid <> pg_backend_pid() and state = 'active' "
                    "group by 1;"
                    "select count(*) filter (where s.subxact_overflowed), coalesce(max(s.subxact_count), 0) "
                    "from pg_stat_activity a cross join lateral pg_stat_get_backend_subxact(a.pid) s "
                    "where a.backend_type = 'client backend' and a.pid <> pg_backend_pid()")
                self.samples += 1
                active = 0
                for name, n in (res[0] if res else []):
                    n = int(n)
                    active += n
                    self.waits[name] = self.waits.get(name, 0) + n
                    if name == "SubtransSLRU":
                        self.subtrans_slru += n
                    if name == "SubtransBuffer":
                        self.subtrans_buffer += n
                self.active_max = max(self.active_max, active)
                ov, sub = res[1][0] if len(res) > 1 and res[1] else ("0", "0")
                ov, sub = int(ov or 0), int(sub or 0)
                if ov > 0:
                    self.overflow_samples += 1
                self.overflow_max = max(self.overflow_max, ov)
                self.subxact_max = max(self.subxact_max, sub)
                self.stop.wait(0.25)
        finally:
            c.close()


# ------------------------------------------------------------------ seeding

def due_time(c, lead):
    return c.scalar("select (clock_timestamp() + make_interval(secs => %s))::text" % lead)


def copy_enrollments(c, tpl_id, prefix, n, due, keep_wake):
    """Copy a template enrollment (and its node_trace) n times as perf rows."""
    wake = "%s::timestamptz" % lit(due) if keep_wake else "e.next_wake_at"
    c.query(
        "insert into enrollment_record (enrollment_id, journey_id, journey_version, profile_id, status, "
        "current_node_id, wake_type, next_wake_at, wake_event_name, wake_event_filter, trigger_snapshot, dedup_key, "
        "variables, delivery_history, enrolled_at, updated_at, terminated_at, termination_reason) "
        "select %(p)s || g, e.journey_id, e.journey_version, %(p)s || g, e.status, e.current_node_id, e.wake_type, "
        "%(w)s, e.wake_event_name, e.wake_event_filter, e.trigger_snapshot, null, e.variables, e.delivery_history, "
        "e.enrolled_at, e.updated_at, e.terminated_at, e.termination_reason "
        "from enrollment_record e, generate_series(1, %(n)d) g where e.enrollment_id = %(t)s;"
        "insert into node_trace (enrollment_id, seq, node_id, node_type, entered_at, exited_at, out_port, "
        "decision_reason, evaluated) "
        "select %(p)s || g, t.seq, t.node_id, t.node_type, t.entered_at, t.exited_at, t.out_port, "
        "t.decision_reason, t.evaluated from node_trace t, generate_series(1, %(n)d) g where t.enrollment_id = %(t)s"
        % {"p": lit(prefix), "n": n, "t": lit(tpl_id), "w": wake})


def seed_advance(c, ctx, prefix, n, due):
    copy_enrollments(c, ctx["tpl_wait_enrollment"], prefix, n, due, True)


def seed_relay(c, ctx, prefix, n, due):
    copy_enrollments(c, ctx["tpl_done_enrollment"], prefix, n, due, False)
    c.query(
        "insert into effect_outbox (idempotency_key, enrollment_id, journey_id, journey_version, node_id, visit_seq, "
        "effect_index, effect_type, payload, status, available_at, publish_count, created_at, published_at) "
        "select %(p)s || g, %(p)s || g, o.journey_id, o.journey_version, o.node_id, o.visit_seq, o.effect_index, "
        "o.effect_type, o.payload, 'PENDING', %(d)s::timestamptz, 0, o.created_at, null "
        "from effect_outbox o, generate_series(1, %(n)d) g where o.enrollment_id = %(t)s"
        % {"p": lit(prefix), "n": n, "t": lit(ctx["tpl_done_enrollment"]), "d": lit(due)})


def seed_dispatch(c, ctx, prefix, n, due):
    c.query(
        "insert into event (event_id, source_type, source_id, name, occurred_at, received_at, identity, properties, "
        "context, next_dispatch_at) "
        "select %(p)s || g, 'EXTERNAL_SYSTEM', 'perf', %(nm)s, clock_timestamp(), clock_timestamp(), "
        "jsonb_build_object('profile_id', %(p)s || g), '{}'::jsonb, '{}'::jsonb, %(d)s::timestamptz "
        "from generate_series(1, %(n)d) g"
        % {"p": lit(prefix), "n": n, "nm": lit(ctx["nomatch_event"]), "d": lit(due)})


# ------------------------------------------------------------------ measuring

def series(c, sql_ts_from, due):
    """Per-second counts relative to due, plus quantiles of completion time, from a 'from ... where ...' fragment
    exposing the timestamp column `ts`."""
    rel = "extract(epoch from (ts - %s::timestamptz))" % lit(due)
    rows = c.rows("select floor(%s)::int, count(*) from %s group by 1 order by 1" % (rel, sql_ts_from))
    summary = c.rows("select count(*), min(%s), max(%s), percentile_cont(array[0.5, 0.9, 0.99]) within group "
                     "(order by %s) from %s" % (rel, rel, rel, sql_ts_from))[0]
    if int(summary[0]) == 0:
        return None
    q = [float(x) for x in summary[3].strip("{}").split(",")]
    buckets = [(int(a), int(b)) for a, b in rows]
    return {"count": int(summary[0]), "first": float(summary[1]), "last": float(summary[2]),
            "p50": q[0], "p90": q[1], "p99": q[2], "buckets": buckets}


def rate_summary(s, n):
    if s is None:
        return None
    counts = [b for _, b in s["buckets"]]
    inner = counts[1:-1] if len(counts) > 2 else counts  # first/last second are partial
    clear = max(s["last"], 0.001)
    return {"clear_s": s["last"], "avg_per_s": s["count"] / clear, "median_per_s": statistics.median(inner),
            "peak_per_s": max(counts), "done": s["count"], "of": n, "t50": s["p50"], "t90": s["p90"],
            "t99": s["p99"]}


def explain(c, label, sql, now_lit):
    sql = sql.replace(":now", "%s::timestamptz" % lit(now_lit))
    res = c.query("begin; explain (analyze, buffers) %s; rollback" % sql)
    plan = [r[0] for rs in res for r in rs]
    return {"label": label, "sql": sql, "plan": plan}


EXPLAIN_COLUMNS = ("idempotency_key, enrollment_id, journey_id, journey_version, node_id, visit_seq, effect_index, "
                   "effect_type, payload, status, available_at, publish_count, created_at, published_at")


def run_scenario(c, ctx, kind, n, timeout):
    prefix = "perf-%s-%s%d-" % (ctx["tag"], kind[0], n)
    log("== %s n=%d: vacuum + baseline" % (kind, n))
    c.query("vacuum (analyze) enrollment_record")
    c.query("vacuum (analyze) event")
    c.query("vacuum (analyze) effect_outbox")
    before = table_stats(c)
    c.query("select pg_stat_reset_slru('subtransaction')")

    lead = 45 + n // 1000
    due = due_time(c, lead)
    log("seeding %d rows, due at %s (lead %ds)" % (n, due, lead))
    t0 = time.time()
    {"advance": seed_advance, "relay": seed_relay, "dispatch": seed_dispatch}[kind](c, ctx, prefix, n, due)
    seed_s = time.time() - t0
    c.query("analyze enrollment_record")
    c.query("analyze event")
    c.query("analyze effect_outbox")
    seeded = table_stats(c)

    now_lit = c.scalar("select ((%s::timestamptz) + interval '1 second')::text" % lit(due))
    plans = []
    if kind == "advance":
        plans.append(explain(c, "AdvanceLoop lockDue (limit 1)",
                             "select * from enrollment_record where status = 'WAITING' and next_wake_at is not null "
                             "and next_wake_at <= :now order by next_wake_at, enrollment_id limit 1 for update skip "
                             "locked", now_lit))
    if kind == "relay":
        plans.append(explain(c, "OutboxRelay claimDue (limit %d)" % ctx["relay_batch"],
                             "update effect_outbox set available_at = :now + interval '30 seconds' where "
                             "idempotency_key in (select idempotency_key from effect_outbox where status = 'PENDING' "
                             "and available_at <= :now order by available_at, idempotency_key limit %d for update "
                             "skip locked) returning %s" % (ctx["relay_batch"], EXPLAIN_COLUMNS), now_lit))
    if kind == "dispatch":
        plans.append(explain(c, "EventDispatcher lockUndispatched (limit 1)",
                             "select event_id, source_type, source_id, name, occurred_at, received_at, identity, "
                             "properties, context from event where dispatched_at is null and (next_dispatch_at is "
                             "null or next_dispatch_at <= :now) order by received_at, event_id limit 1 for update "
                             "skip locked", now_lit))

    sampler = Sampler()
    sampler.start()
    wait_s = float(c.scalar("select extract(epoch from (%s::timestamptz - clock_timestamp()))" % lit(due)))
    log("waiting %.0fs for the backlog to become due" % max(wait_s, 0))
    if wait_s > 0:
        time.sleep(wait_s)

    def progress():
        if kind == "advance":
            return {"advance": int(c.scalar(
                        "select count(*) from enrollment_record where enrollment_id like %s and status <> 'WAITING'"
                        % lit(prefix + "%"))),
                    "relay": int(c.scalar("select count(*) from effect_outbox where enrollment_id like %s and "
                                          "status = 'PUBLISHED'" % lit(prefix + "%"))),
                    "consumer": int(c.scalar("select count(*) from delivery_task where enrollment_id like %s and "
                                             "status <> 'PENDING'" % lit(prefix + "%")))}
        if kind == "relay":
            return {"relay": int(c.scalar("select count(*) from effect_outbox where idempotency_key like %s and "
                                          "status = 'PUBLISHED'" % lit(prefix + "%"))),
                    "consumer": int(c.scalar("select count(*) from delivery_task where enrollment_id like %s and "
                                             "status <> 'PENDING'" % lit(prefix + "%")))}
        return {"dispatch": int(c.scalar("select count(*) from event where event_id like %s and dispatched_at is not "
                                         "null" % lit(prefix + "%")))}

    start = time.time()
    last_print = 0
    timed_out = False
    while True:
        p = progress()
        if all(v >= n for v in p.values()):
            break
        if time.time() - start > timeout:
            timed_out = True
            log("TIMEOUT after %ds; progress %s" % (timeout, p))
            break
        if time.time() - last_print > 30:
            log("progress %s / %d" % (p, n))
            last_print = time.time()
        time.sleep(2.0)
    sampler.stop.set()
    sampler.join(timeout=10)

    pfx = lit(prefix + "%")
    results = {}
    if kind == "advance":
        results["advance"] = rate_summary(series_ts(
            c, "updated_at", "enrollment_record", "enrollment_id like %s and status <> 'WAITING'" % pfx, due), n)
    if kind in ("advance", "relay"):
        key = "enrollment_id" if kind == "advance" else "idempotency_key"
        results["relay"] = rate_summary(series_ts(c, "published_at", "effect_outbox",
                                                  "%s like %s and status = 'PUBLISHED'" % (key, pfx), due), n)
        results["consumer"] = rate_summary(series_ts(c, "updated_at", "delivery_task",
                                                     "enrollment_id like %s and status <> 'PENDING'" % pfx, due), n)
    if kind == "dispatch":
        results["dispatch"] = rate_summary(series_ts(c, "dispatched_at", "event", "event_id like %s" % pfx, due), n)

    confirm = None
    if kind in ("advance", "relay"):
        key = "enrollment_id" if kind == "advance" else "idempotency_key"
        lease = ctx["lease_s"]
        r = c.rows("select count(*), percentile_cont(array[0.5, 0.9, 0.99]) within group (order by d), max(d) from "
                   "(select extract(epoch from (published_at - (available_at - make_interval(secs => %s)))) d "
                   "from effect_outbox where %s like %s and status = 'PUBLISHED' and publish_count = 1) x"
                   % (lease, key, pfx))[0]
        if int(r[0]) > 0:
            q = [float(x) for x in r[1].strip("{}").split(",")]
            confirm = {"count": int(r[0]), "p50": q[0], "p90": q[1], "p99": q[2], "max": float(r[2])}

    slru = c.rows("select name, blks_zeroed, blks_hit, blks_read from pg_stat_slru where lower(name) in "
                  "('subtransaction', 'subtrans')")
    final = {}
    if kind == "advance":
        final = {"statuses": c.rows("select status, count(*) from enrollment_record where enrollment_id like %s "
                                    "group by 1 order by 1" % pfx)}
    after = table_stats(c)
    retries = c.rows("select coalesce(sum(publish_count), 0), coalesce(max(publish_count), 0) from effect_outbox "
                     "where enrollment_id like %s" % pfx)[0] if kind != "dispatch" else None
    log("cleanup %s n=%d" % (kind, n))
    cleanup_scenario(c, prefix)
    return {"kind": kind, "n": n, "lead": lead, "seed_s": seed_s, "due": due, "timed_out": timed_out,
            "before": before, "seeded": seeded, "after": after, "plans": plans, "results": results,
            "confirm": confirm, "slru": slru, "final": final, "retries": retries,
            "sampler": {"samples": sampler.samples, "waits": sampler.waits, "SubtransSLRU": sampler.subtrans_slru,
                        "SubtransBuffer": sampler.subtrans_buffer, "overflow_samples": sampler.overflow_samples,
                        "overflow_max": sampler.overflow_max, "subxact_max": sampler.subxact_max,
                        "active_max": sampler.active_max}}


def series_ts(c, col, table, where, due):
    return series(c, "(select %s as ts from %s where %s and %s is not null) x" % (col, table, where, col), due)


def cleanup_scenario(c, prefix):
    like = lit(prefix + "%")
    c.query("begin;"
            "delete from mock_channel_message where enrollment_id like %(l)s;"
            "delete from delivery_task where enrollment_id like %(l)s;"
            "delete from effect_outbox where enrollment_id like %(l)s or idempotency_key like %(l)s;"
            "delete from node_trace where enrollment_id like %(l)s;"
            "delete from enrollment_record where enrollment_id like %(l)s;"
            "delete from event where event_id like %(l)s;"
            "commit" % {"l": like})


# ------------------------------------------------------------------ environment

def read_file(c, path):
    try:
        return c.scalar("select pg_read_file(%s)" % lit(path)) or ""
    except pg.PgError:
        return ""


def environment(c):
    info = {}
    info["postgres"] = c.scalar("select version()")
    info["pg_settings"] = {r[0]: r[1] for r in c.rows(
        "select name, setting || coalesce(unit, '') from pg_settings where name in ('shared_buffers', "
        "'max_connections', 'work_mem', 'synchronous_commit', 'fsync', 'max_wal_size', 'autovacuum', "
        "'track_activities')")}
    cpuinfo = read_file(c, "/proc/cpuinfo")
    info["cpus"] = len(re.findall(r"^processor\s*:", cpuinfo, re.M))
    m = re.search(r"model name\s*:\s*(.+)", cpuinfo)
    info["cpu_model"] = m.group(1).strip() if m else "unknown"
    m = re.search(r"MemTotal:\s*(\d+) kB", read_file(c, "/proc/meminfo"))
    info["mem_mb"] = int(m.group(1)) // 1024 if m else None
    info["cgroup_cpu_max"] = read_file(c, "/sys/fs/cgroup/cpu.max").strip() or "unreadable"
    info["cgroup_memory_max"] = read_file(c, "/sys/fs/cgroup/memory.max").strip() or "unreadable"
    info["kernel"] = read_file(c, "/proc/version").strip()[:120]
    info["vars"] = {k: os.environ.get(k, "(unset)") for k in RECORDED_VARS}
    try:
        port = env("RABBITMQ_MANAGEMENT_PORT")
        req = urllib.request.Request("http://127.0.0.1:%s/api/overview" % port)
        import base64
        tok = base64.b64encode(("%s:%s" % (env("RABBITMQ_USER"), env("RABBITMQ_PASSWORD"))).encode()).decode()
        req.add_header("Authorization", "Basic " + tok)
        with OPENER.open(req, timeout=10) as r:
            ov = json.loads(r.read())
        info["rabbitmq"] = "RabbitMQ %s, Erlang %s" % (ov.get("rabbitmq_version"), ov.get("erlang_version"))
    except Exception as e:  # noqa: BLE001  (informational only)
        info["rabbitmq"] = "unavailable (%s)" % type(e).__name__
    pom = ""
    try:
        with open(os.path.join("api", "pom.xml"), encoding="utf-8") as f:
            pom = f.read()
    except OSError:
        pass
    m = re.search(r"<parent>.*?<version>([^<]+)</version>", pom, re.S)
    info["spring_boot"] = m.group(1) if m else "unknown"
    m = re.search(r"<java.version>([^<]+)</java.version>", pom)
    info["java_target"] = m.group(1) if m else "unknown"
    return info


# ------------------------------------------------------------------ report

def fmt_rate(r):
    if r is None:
        return "no rows"
    return "%.1f s | %.1f | %.0f | %d | %.1f / %.1f / %.1f" % (
        r["clear_s"], r["avg_per_s"], r["median_per_s"], r["peak_per_s"], r["t50"], r["t90"], r["t99"])


def write_report(path, envinfo, scenarios, counts_before, counts_after, skew, tag):
    L = []
    L.append("# perf run %s (generated by tools/perf/run.py; overwritten on every run)" % tag)
    L.append("")
    L.append("## Environment")
    L.append("- %s" % envinfo["postgres"])
    L.append("- %s; Spring Boot parent %s; Java target %s" % (envinfo["rabbitmq"], envinfo["spring_boot"],
                                                             envinfo["java_target"]))
    L.append("- Docker VM as seen from the PostgreSQL container: %s CPUs (%s), %s MB memory; cgroup cpu.max=%s, "
             "memory.max=%s" % (envinfo["cpus"], envinfo["cpu_model"], envinfo["mem_mb"], envinfo["cgroup_cpu_max"],
                                envinfo["cgroup_memory_max"]))
    L.append("- PostgreSQL settings: " + ", ".join("%s=%s" % kv for kv in sorted(envinfo["pg_settings"].items())))
    L.append("- Variables of this run (the backend under test was started earlier; compare with its own start): "
             + ", ".join("%s=%s" % kv for kv in envinfo["vars"].items()))
    L.append("- Clock skew DB minus this host: %+.3f s" % skew)
    L.append("- Non-perf row counts before: %s" % counts_before)
    L.append("- Non-perf row counts after:  %s" % counts_after)
    L.append("")
    L.append("## Drain results")
    L.append("Columns: clear time (last row done minus due instant T) | average rows/s | median rows/s (per full "
             "second) | peak rows/s | t50 / t90 / t99 seconds after T")
    L.append("")
    L.append("| scenario | n | loop | clear | avg/s | median/s | peak/s | t50/t90/t99 | timed out |")
    L.append("|---|---|---|---|---|---|---|---|---|")
    for s in scenarios:
        for loop, r in s["results"].items():
            cells = fmt_rate(r).split(" | ")
            L.append("| %s | %d | %s | %s | %s | %s | %s | %s | %s |" % (
                s["kind"], s["n"], loop, cells[0] if r else "-", *(cells[1:] if r else ["-"] * 4),
                "yes" if s["timed_out"] else "no"))
    L.append("")
    L.append("## Subtransaction overflow indicators")
    L.append("| scenario | n | SLRU blks_zeroed / hit / read | SubtransSLRU samples | SubtransBuffer samples | "
             "samples | max backends overflowed | max subxact_count | max active backends |")
    L.append("|---|---|---|---|---|---|---|---|---|")
    for s in scenarios:
        slru = s["slru"][0] if s["slru"] else ("-", "?", "?", "?")
        sm = s["sampler"]
        L.append("| %s | %d | %s / %s / %s | %d | %d | %d | %d | %d | %d |" % (
            s["kind"], s["n"], slru[1], slru[2], slru[3], sm["SubtransSLRU"], sm["SubtransBuffer"], sm["samples"],
            sm["overflow_max"], sm["subxact_max"], sm["active_max"]))
    L.append("")
    L.append("Wait events observed on active backends (sum of samples):")
    for s in scenarios:
        w = sorted(s["sampler"]["waits"].items(), key=lambda kv: -kv[1])[:8]
        L.append("- %s n=%d: %s" % (s["kind"], s["n"], ", ".join("%s=%d" % kv for kv in w) or "none"))
    L.append("")
    L.append("## Confirm latency (proxy: published_at - (available_at - OUTBOX_RELAY_LEASE), seconds)")
    L.append("| scenario | n | rows | p50 | p90 | p99 | max |")
    L.append("|---|---|---|---|---|---|---|")
    for s in scenarios:
        c = s["confirm"]
        if c:
            L.append("| %s | %d | %d | %.3f | %.3f | %.3f | %.3f |" % (s["kind"], s["n"], c["count"], c["p50"],
                                                                         c["p90"], c["p99"], c["max"]))
    L.append("")
    L.append("## Table health (n_dead_tup, table MB, index MB): before / after seeding / after drain")
    L.append("| scenario | n | table | before | seeded | after drain |")
    L.append("|---|---|---|---|---|---|")
    for s in scenarios:
        for t in TABLES:
            def f(x):
                return "%d / %s / %s" % (x[t]["dead"], mb(x[t]["table_bytes"]), mb(x[t]["index_bytes"]))
            L.append("| %s | %d | %s | %s | %s | %s |" % (s["kind"], s["n"], t, f(s["before"]), f(s["seeded"]),
                                                          f(s["after"])))
    L.append("")
    L.append("## Other")
    for s in scenarios:
        extra = []
        if s["final"].get("statuses"):
            extra.append("enrollment statuses after drain: " + ", ".join("%s=%s" % tuple(r)
                                                                          for r in s["final"]["statuses"]))
        if s["retries"]:
            extra.append("outbox publish_count sum=%s max=%s" % tuple(s["retries"]))
        extra.append("seed took %.1f s, lead %d s" % (s["seed_s"], s["lead"]))
        L.append("- %s n=%d: %s" % (s["kind"], s["n"], "; ".join(extra)))
    L.append("")
    L.append("## EXPLAIN (ANALYZE, BUFFERS) under backlog (evaluated at T + 1 s, inside a rolled-back transaction)")
    for s in scenarios:
        for p in s["plans"]:
            L.append("")
            L.append("### %s, n=%d" % (p["label"], s["n"]))
            L.append("```")
            L.extend(p["plan"])
            L.append("```")
    L.append("")
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(L) + "\n")


def main():
    sizes = [int(x) for x in os.environ.get("PERF_SIZES", "10000,100000").split(",") if x.strip()]
    timeout = int(os.environ.get("PERF_TIMEOUT", "3600"))
    out = os.environ.get("PERF_OUT", os.path.join("docs", "perf", "last-run.md"))
    if os.environ.get("CI"):
        fail("perf is not meant to run in CI")
    c = connect()
    tag = secrets.token_hex(3)
    log("run %s sizes=%s timeout=%ds" % (tag, sizes, timeout))

    t0 = time.time()
    db_now = float(c.scalar("select extract(epoch from clock_timestamp())"))
    skew = db_now - (t0 + time.time()) / 2
    envinfo = environment(c)
    relay_batch = int(os.environ.get("OUTBOX_RELAY_BATCH_SIZE", "100"))
    lease_s = seconds(os.environ.get("OUTBOX_RELAY_LEASE", "PT30S"))

    api("GET", "/api/v1/journeys?limit=1", env("CDP_ADMIN_API_KEY"))  # fail early if the backend is down
    # leftovers of an interrupted earlier run
    cleanup(c)
    counts_before = row_counts(c)

    # a clean start: no non-perf backlog that is already due
    def quiet():
        n = int(c.scalar(
            "select (select count(*) from enrollment_record where status = 'WAITING' and next_wake_at <= now()) + "
            "(select count(*) from effect_outbox where status = 'PENDING' and available_at <= now()) + "
            "(select count(*) from event where dispatched_at is null and (next_dispatch_at is null or "
            "next_dispatch_at <= now()))"))
        return n == 0
    wait_until(quiet, "existing non-perf backlog to drain (the backend must be running)", limit=120, step=3)

    ctx = {"tag": tag, "relay_batch": relay_batch, "lease_s": lease_s, "nomatch_event": "perf_%s_nomatch" % tag}
    try:
        log("creating template journeys through the API")
        ev_wait, ev_done = "perf_%s_wait" % tag, "perf_%s_done" % tag
        api("POST", "/api/v1/journeys", env("CDP_ADMIN_API_KEY"), journey("perf-%s-wait" % tag, ev_wait, "PT2H"))
        api("POST", "/api/v1/journeys", env("CDP_ADMIN_API_KEY"), journey("perf-%s-done" % tag, ev_done, "PT2S"))
        send_event(ulid(), "perf-%s-tpl-wait" % tag, ev_wait)
        send_event(ulid(), "perf-%s-tpl-done" % tag, ev_done)
        ctx["tpl_wait_enrollment"] = wait_until(lambda: (c.scalar(
            "select enrollment_id from enrollment_record where profile_id = %s and status = 'WAITING'"
            % lit("perf-%s-tpl-wait" % tag))), "template WAITING enrollment", 90)
        ctx["tpl_done_enrollment"] = wait_until(lambda: (c.scalar(
            "select enrollment_id from enrollment_record where profile_id = %s and status = 'COMPLETED'"
            % lit("perf-%s-tpl-done" % tag))), "template COMPLETED enrollment", 90)
        wait_until(lambda: c.scalar(
            "select 1 from effect_outbox where enrollment_id = %s and status = 'PUBLISHED'"
            % lit(ctx["tpl_done_enrollment"])), "template outbox PUBLISHED", 60)

        scenarios = []
        for n in sizes:
            for kind in ("advance", "relay", "dispatch"):
                scenarios.append(run_scenario(c, ctx, kind, n, timeout))
    finally:
        log("final cleanup (perf-* rows only)")
        cleanup(c)
        c.query("vacuum (analyze) enrollment_record")
        c.query("vacuum (analyze) event")
        c.query("vacuum (analyze) effect_outbox")
    counts_after = row_counts(c)
    write_report(out, envinfo, scenarios, counts_before, counts_after, skew, tag)
    log("report written to %s" % out)
    if counts_before != counts_after:
        log("note: non-perf row counts differ (a running backend or other work changed them): %s -> %s"
            % (counts_before, counts_after))


main()
