#!/usr/bin/env python3
"""
Live checks of the SmartUse backend contract, from the frontend's side.

Plain Python 3 standard library only (no pip installs). Every scenario runs
against the real backend; test participants are tagged with
university_major = "QA-BACKEND-CHECK" and are hard-opted-out at the end of the
run, so their records are deleted (only the `users` row stays, as for real
participants).

Usage (from the repo root or this folder):
    python backend-checks/run_checks.py                  # regenerate fixtures, run everything
    python backend-checks/run_checks.py --reuse-fixtures # replay the stored fixtures
    python backend-checks/run_checks.py --only cutoff sync

Base URL: $SMARTUSE_BASE_URL, else `smartuse.baseUrl` in local.properties;
the script stops if neither is set. Raw requests/responses of each run go to
backend-checks/out/<timestamp>/ (git-ignored).

Two kinds of check:
  EXPECT  the documented API contract; a mismatch is a FAIL.
  OBSERVE behaviour the contract doesn't pin down; printed for the record,
          never fails the run.
"""

import argparse
import datetime as dt
import json
import os
import re
import sys
import urllib.error
import urllib.request
import uuid
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent
FIXTURES = HERE / "fixtures"
QA_MAJOR = "QA-BACKEND-CHECK"
EPOCH = dt.timedelta(minutes=6)
BATCH_CEILING = 500  # backend cap: larger requests get 413 and nothing is stored (JobLimits.SYNC_BATCH_CEILING)


# --- config -----------------------------------------------------------------

def base_url() -> str:
    env = os.environ.get("SMARTUSE_BASE_URL")
    if env:
        return env.rstrip("/")
    props = REPO / "local.properties"
    if props.exists():
        for line in props.read_text(encoding="utf-8").splitlines():
            if line.strip().startswith("smartuse.baseUrl="):
                return line.split("=", 1)[1].strip().rstrip("/")
    sys.exit("No backend URL: set SMARTUSE_BASE_URL or smartuse.baseUrl in local.properties.")


# --- fixtures ---------------------------------------------------------------

def iso(t: dt.datetime) -> str:
    return t.astimezone(dt.timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def record(t: dt.datetime, is_use: bool = True, apps: int = 2, **overrides) -> dict:
    r = {
        "client_record_id": str(uuid.uuid4()),
        "start_ts": iso(t),
        "is_use": is_use,
        "distinct_app_count": apps if is_use else 0,
        "granularity": "small",
    }
    r.update(overrides)
    return r


def generate_fixtures() -> dict:
    """Fresh made-up data: new UUIDs and timestamps a few hours in the past."""
    t0 = dt.datetime.now(dt.timezone.utc).replace(microsecond=0) - dt.timedelta(hours=6)
    t = [t0 + i * EPOCH for i in range(10)]
    batch1 = [record(t[0]), record(t[1], is_use=False), record(t[2])]
    fx = {
        "register_valid": {
            "age_at_signup": 21,
            "gender": "Femenino",
            "university_major": QA_MAJOR,
            "test_score": 0,
            "experiment_group": "control",
        },
        "register_invalid": {"gender": "Femenino", "university_major": QA_MAJOR},
        # Three records, in order.
        "batch1": {"records": batch1},
        # Same start_ts as batch1[1], different client_record_id.
        "collide_single": {"records": [record(t[1], is_use=False)]},
        # One collider (t[2]) plus one new record (t[3]).
        "collide_mixed": {"records": [record(t[2]), record(t[3])]},
        # Two colliders (t[0], t[1]) plus one new record (t[5]).
        "collide_multiple": {"records": [record(t[0]), record(t[1], is_use=False), record(t[5])]},
        # Records given out of order: t[7] before t[6].
        "out_of_order": {"records": [record(t[7]), record(t[6], is_use=False)]},
        # A new record older than the cursor, colliding with nothing.
        "older_than_cursor": {"records": [record(t0 - dt.timedelta(hours=1))]},
        "invalid_uuid": {"records": [record(t[9], client_record_id="not-a-uuid")]},
        "invalid_granularity": {"records": [record(t[9], granularity="medium")]},
        "invalid_use_with_apps": {"records": [record(t[9], is_use=False, distinct_app_count=3)]},
        "empty": {"records": []},
        "soft_user_batch": {"records": [record(t[0]), record(t[1])]},
        "soft_user_after": {"records": [record(t[2])]},
        "hard_user_batch": {"records": [record(t[0]), record(t[1])]},
    }
    FIXTURES.mkdir(exist_ok=True)
    for name, body in fx.items():
        (FIXTURES / f"{name}.json").write_text(json.dumps(body, indent=2) + "\n", encoding="utf-8")
    return fx


def load_fixtures() -> dict:
    return {p.stem: json.loads(p.read_text(encoding="utf-8")) for p in sorted(FIXTURES.glob("*.json"))}


def big_batch(n: int, start: dt.datetime, step: dt.timedelta = EPOCH) -> dict:
    """Generated on the fly (too big to keep as a file): n records from `start`."""
    return {"records": [record(start + i * step) for i in range(n)]}


# --- http -------------------------------------------------------------------

class Client:
    def __init__(self, base: str, out_dir: Path):
        self.base = base
        self.out = out_dir
        self.n = 0

    def call(self, method: str, path: str, body=None, token=None, label: str = "", headers=None):
        self.n += 1
        data = None if body is None else json.dumps(body).encode("utf-8")
        req = urllib.request.Request(self.base + path, data=data, method=method)
        if body is not None:
            req.add_header("Content-Type", "application/json")
        if token:
            req.add_header("Authorization", f"Bearer {token}")
        for k, v in (headers or {}).items():
            req.add_header(k, v)
        try:
            with urllib.request.urlopen(req, timeout=60) as resp:
                status, raw = resp.status, resp.read().decode("utf-8", "replace")
        except urllib.error.HTTPError as e:
            status, raw = e.code, e.read().decode("utf-8", "replace")
        except (urllib.error.URLError, TimeoutError) as e:
            status, raw = 0, f"network error: {e}"
        try:
            parsed = json.loads(raw) if raw else None
        except json.JSONDecodeError:
            parsed = None
        safe = re.sub(r"[^a-z0-9]+", "-", (label or path).lower()).strip("-")
        shown_body = body if body is None or len(json.dumps(body)) < 20000 else {"records": f"<{len(body.get('records', []))} records>"}
        (self.out / f"{self.n:02d}-{safe}.json").write_text(
            json.dumps(
                {"request": {"method": method, "path": path, "auth": bool(token), "body": shown_body},
                 "response": {"status": status, "body": parsed if parsed is not None else raw}},
                indent=2,
            ) + "\n",
            encoding="utf-8",
        )
        return status, parsed if parsed is not None else raw


# --- checks -----------------------------------------------------------------

class Report:
    def __init__(self):
        self.rows = []

    def expect(self, name: str, ok: bool, detail=""):
        self.rows.append(("PASS" if ok else "FAIL", name, detail))
        print(f"  {'PASS' if ok else 'FAIL'}  {name}" + (f"  — {detail}" if detail and not ok else ""))

    def observe(self, name: str, detail):
        self.rows.append(("INFO", name, detail))
        print(f"  INFO  {name}: {detail}")

    @property
    def failed(self):
        return [r for r in self.rows if r[0] == "FAIL"]


def brief(status, body, limit=300):
    text = json.dumps(body) if not isinstance(body, str) else body
    return f"HTTP {status} {text[:limit]}"


def to_ms(ts):
    return int(dt.datetime.fromisoformat(ts.replace("Z", "+00:00")).timestamp() * 1000) if ts else None


def same_instant(a, b):
    return a is not None and b is not None and to_ms(a) == to_ms(b)


def cursor(c: Client, token, label="cursor"):
    status, body = c.call("GET", "/sync/canonical/cursor", token=token, label=label)
    if isinstance(body, dict):
        return status, body.get("client_record_id"), body.get("start_ts"), body
    return status, None, None, body


def register(c: Client, fx, label):
    status, body = c.call("POST", "/users", fx["register_valid"], label=label)
    if isinstance(body, dict) and body.get("auth_token"):
        return status, body
    return status, None


# --- scenarios ----------------------------------------------------------------

def scenario_cutoff(c: Client, r: Report, fx, users):
    print("\n[cutoff] GET /cutoff")
    status, body = c.call("GET", "/cutoff", label="cutoff")
    r.expect("GET /cutoff answers 200", status == 200, brief(status, body))
    ok_shape = isinstance(body, dict) and set(body) == {"cutoff"}
    r.expect("the body is exactly { cutoff }", ok_shape, brief(status, body))
    if ok_shape:
        value = body["cutoff"]
        is_date = value is None or (isinstance(value, str) and re.fullmatch(r"\d{4}-\d{2}-\d{2}", value) is not None)
        r.expect("cutoff is null or a YYYY-MM-DD date (no time part)", is_date, repr(value))
        r.observe("current cutoff", value)
    status2, body2 = c.call("GET", "/cutoff", token="not-a-real-token", label="cutoff-with-bogus-token")
    r.expect("GET /cutoff is open: a bogus bearer token doesn't change the answer", status2 == 200, brief(status2, body2))


def scenario_register(c: Client, r: Report, fx, users):
    print("\n[register] POST /users")
    status, body = register(c, fx, "register-sync-user")
    r.expect("a valid registration answers 201", status == 201, brief(status, body))
    r.expect("it returns user_id and auth_token", body is not None and bool(body.get("user_id")), brief(status, body))
    if body:
        users["sync"] = body
    status2, body2 = c.call("POST", "/users", fx["register_invalid"], label="register-invalid")
    r.expect("an invalid registration answers 400", status2 == 400, brief(status2, body2))
    if isinstance(body2, dict):
        r.observe("invalid registration error code", body2.get("error"))


def scenario_auth(c: Client, r: Report, fx, users):
    print("\n[auth] bearer token")
    status, body = c.call("GET", "/sync/canonical/cursor", label="cursor-no-token")
    r.expect("the cursor without a token answers 401", status == 401, brief(status, body))
    status2, body2 = c.call("GET", "/sync/canonical/cursor", token="bogus-token", label="cursor-bogus-token")
    r.expect("the cursor with a bogus token is refused (401/403)", status2 in (401, 403), brief(status2, body2))
    status3, body3 = c.call("POST", "/sync/canonical", fx["batch1"], label="sync-no-token")
    r.expect("a sync without a token answers 401", status3 == 401, brief(status3, body3))


def scenario_sync(c: Client, r: Report, fx, users):
    print("\n[sync] cursor, idempotency, collisions, validation")
    u = users.get("sync")
    if not u:
        r.expect("sync scenario has a registered user", False, "registration failed earlier")
        return
    tok = u["auth_token"]
    b1 = fx["batch1"]["records"]

    status, cid, cts, body = cursor(c, tok, "cursor-fresh")
    r.expect("a fresh user's cursor is 200 with both fields null", status == 200 and cid is None and cts is None, brief(status, body))

    status, body = c.call("POST", "/sync/canonical", fx["batch1"], tok, "batch1")
    r.expect("a 3-record batch is accepted (2xx)", 200 <= status < 300, brief(status, body))
    st = body.get("synced_through") if isinstance(body, dict) else None
    r.expect("received = 3", isinstance(body, dict) and body.get("received") == 3, brief(status, body))
    r.expect("synced_through is the batch's last record",
             bool(st) and st.get("client_record_id") == b1[-1]["client_record_id"] and same_instant(st.get("start_ts"), b1[-1]["start_ts"]),
             brief(status, body))
    status, cid, cts, body = cursor(c, tok, "cursor-after-batch1")
    r.expect("GET cursor agrees with synced_through",
             cid == b1[-1]["client_record_id"] and same_instant(cts, b1[-1]["start_ts"]), brief(status, body))

    status, body = c.call("POST", "/sync/canonical", fx["batch1"], tok, "batch1-resend")
    r.expect("resending the same records is a harmless duplicate (2xx, NOT 409)", 200 <= status < 300, brief(status, body))
    if isinstance(body, dict):
        r.observe("received on a full resend", body.get("received"))
    status, cid, cts, body = cursor(c, tok, "cursor-after-resend")
    r.expect("the cursor doesn't move on a resend", cid == b1[-1]["client_record_id"], brief(status, body))

    single = fx["collide_single"]["records"][0]
    status, body = c.call("POST", "/sync/canonical", fx["collide_single"], tok, "collide-single")
    conflicts = body.get("conflicts") if isinstance(body, dict) else None
    r.expect("a record at a stored start_ts with a new id answers 409", status == 409, brief(status, body))
    r.expect("error = epoch_conflict", isinstance(body, dict) and body.get("error") == "epoch_conflict", brief(status, body))
    r.expect("conflicts names exactly that record",
             isinstance(conflicts, list) and [x.get("client_record_id") for x in conflicts] == [single["client_record_id"]],
             brief(status, body))
    r.expect("conflicts carries its start_ts",
             isinstance(conflicts, list) and len(conflicts) == 1 and same_instant(conflicts[0].get("start_ts"), single["start_ts"]),
             brief(status, body))

    collider, fresh = fx["collide_mixed"]["records"]
    status, body = c.call("POST", "/sync/canonical", fx["collide_mixed"], tok, "collide-mixed")
    conflicts = body.get("conflicts") if isinstance(body, dict) else None
    r.expect("a mixed batch answers 409 naming only the collider",
             status == 409 and isinstance(conflicts, list) and [x.get("client_record_id") for x in conflicts] == [collider["client_record_id"]],
             brief(status, body))
    status, cid, cts, body = cursor(c, tok, "cursor-after-mixed-409")
    r.expect("a 409 stores nothing: the cursor is unchanged (all-or-nothing)", cid == b1[-1]["client_record_id"], brief(status, body))

    status, body = c.call("POST", "/sync/canonical", {"records": [fresh]}, tok, "resend-without-collider")
    r.expect("resending without the collider is accepted (2xx)", 200 <= status < 300, brief(status, body))
    status, cid, cts, body = cursor(c, tok, "cursor-after-resend-without-collider")
    r.expect("the cursor moves to the new record", cid == fresh["client_record_id"], brief(status, body))

    multi = fx["collide_multiple"]["records"]
    status, body = c.call("POST", "/sync/canonical", fx["collide_multiple"], tok, "collide-multiple")
    named = sorted(x.get("client_record_id") for x in body.get("conflicts", [])) if isinstance(body, dict) else None
    r.expect("two colliders in one batch are both named in one 409",
             status == 409 and named == sorted([multi[0]["client_record_id"], multi[1]["client_record_id"]]),
             brief(status, body))

    ooo = fx["out_of_order"]["records"]
    status, body = c.call("POST", "/sync/canonical", fx["out_of_order"], tok, "out-of-order")
    st = body.get("synced_through") if isinstance(body, dict) else None
    r.expect("an out-of-order batch is accepted (2xx)", 200 <= status < 300, brief(status, body))
    latest = max(ooo, key=lambda x: to_ms(x["start_ts"]))
    r.observe("synced_through for an out-of-order batch is the latest start_ts",
              bool(st) and st.get("client_record_id") == latest["client_record_id"])

    status, cid_before, _, _ = cursor(c, tok, "cursor-before-older")
    status, body = c.call("POST", "/sync/canonical", fx["older_than_cursor"], tok, "older-than-cursor")
    r.observe("a new record older than the cursor", brief(status, body))
    status, cid_after, _, body = cursor(c, tok, "cursor-after-older")
    r.expect("the cursor never moves backwards", cid_after == cid_before, brief(status, body))

    status, body = c.call("POST", "/sync/canonical", fx["invalid_granularity"], tok, "invalid_granularity")
    r.expect("invalid granularity answers 400", status == 400, brief(status, body))
    # Known and accepted: an invalid UUID gets a 502 (the app always sends valid UUIDs).
    status, body = c.call("POST", "/sync/canonical", fx["invalid_uuid"], tok, "invalid_uuid")
    r.observe("an invalid client_record_id UUID", brief(status, body))
    # Only the app's own schema forbids this (canonical-schema.md); the API contract is silent.
    status, body = c.call("POST", "/sync/canonical", fx["invalid_use_with_apps"], tok, "invalid_use_with_apps")
    r.observe("an idle epoch (is_use false) with distinct_app_count > 0", brief(status, body))

    status, body = c.call("POST", "/sync/canonical", fx["empty"], tok, "empty-batch")
    r.observe("an empty batch", brief(status, body))

    # Both start after the cursor (1 s apart), so "nothing stored" is visible in it.
    _, cid_before, _, _ = cursor(c, tok, "cursor-before-batch-cap")
    over = big_batch(BATCH_CEILING + 1, dt.datetime.now(dt.timezone.utc) - dt.timedelta(hours=2), dt.timedelta(seconds=1))
    status, body = c.call("POST", "/sync/canonical", over, tok, "batch-over-cap")
    r.expect(f"a batch of {BATCH_CEILING + 1} records is refused with 413", status == 413, brief(status, body))
    _, cid_after, _, body2 = cursor(c, tok, "cursor-after-batch-over-cap")
    r.expect("a 413 stores nothing (cursor unchanged)", cid_after == cid_before, brief(200, body2))
    at_cap = big_batch(BATCH_CEILING, dt.datetime.now(dt.timezone.utc) - dt.timedelta(hours=2), dt.timedelta(seconds=1))
    status, body = c.call("POST", "/sync/canonical", at_cap, tok, "batch-at-cap")
    r.expect(f"a batch of exactly {BATCH_CEILING} records (the app's default and cap) is accepted (2xx)",
             200 <= status < 300, brief(status, body))
    _, cid_after, _, body2 = cursor(c, tok, "cursor-after-batch-at-cap")
    r.expect("the cursor moves to that batch's last record", cid_after == at_cap["records"][-1]["client_record_id"], brief(200, body2))


def scenario_soft(c: Client, r: Report, fx, users):
    print("\n[soft] POST /opt-out/soft")
    status, u = register(c, fx, "register-soft-user")
    r.expect("the soft-opt-out user registers", u is not None, brief(status, u))
    if not u:
        return
    users["soft"] = u
    tok = u["auth_token"]
    status, body = c.call("POST", "/sync/canonical", fx["soft_user_batch"], tok, "soft-user-batch")
    r.expect("the soft user's first batch is accepted", 200 <= status < 300, brief(status, body))
    status, body = c.call("POST", "/opt-out/soft", None, tok, "opt-out-soft")
    r.expect("soft opt-out answers 200", status == 200, brief(status, body))
    r.expect("it returns capture_status and opted_out_at",
             isinstance(body, dict) and "capture_status" in body and "opted_out_at" in body, brief(status, body))
    if isinstance(body, dict):
        r.observe("soft capture_status", body.get("capture_status"))
    status, cid, _, body = cursor(c, tok, "soft-cursor-after")
    r.expect("soft opt-out keeps the data (cursor unchanged)",
             cid == fx["soft_user_batch"]["records"][-1]["client_record_id"], brief(status, body))
    status, body = c.call("POST", "/sync/canonical", fx["soft_user_after"], tok, "soft-user-sync-after")
    r.observe("a sync after soft opt-out (the backend doesn't enforce opt-out)", brief(status, body))
    status, body = c.call("POST", "/opt-out/soft", None, tok, "opt-out-soft-again")
    r.observe("a repeated soft opt-out", brief(status, body))


def scenario_hard(c: Client, r: Report, fx, users):
    print("\n[hard] POST /opt-out/hard")
    status, u = register(c, fx, "register-hard-user")
    r.expect("the hard-opt-out user registers", u is not None, brief(status, u))
    if not u:
        return
    users["hard"] = u
    tok = u["auth_token"]
    status, body = c.call("POST", "/sync/canonical", fx["hard_user_batch"], tok, "hard-user-batch")
    r.expect("the hard user's batch is accepted", 200 <= status < 300, brief(status, body))
    status, cid, _, body = cursor(c, tok, "hard-cursor-before")
    r.expect("before hard opt-out the cursor shows the data", cid is not None, brief(status, body))
    status, body = c.call("POST", "/opt-out/hard", None, tok, "opt-out-hard")
    r.expect("hard opt-out answers 200", status == 200, brief(status, body))
    r.expect("it returns capture_status and opted_out_at",
             isinstance(body, dict) and "capture_status" in body and "opted_out_at" in body, brief(status, body))
    if isinstance(body, dict):
        r.observe("hard capture_status", body.get("capture_status"))
    status, cid, cts, body = cursor(c, tok, "hard-cursor-after")
    r.expect("after hard opt-out the data is gone (cursor null)", status == 200 and cid is None and cts is None, brief(status, body))
    status, body = c.call("POST", "/opt-out/hard", None, tok, "opt-out-hard-again")
    r.expect("a repeated hard opt-out still answers 200 (the app's retries rely on it)", status == 200, brief(status, body))


SCENARIOS = {
    "cutoff": scenario_cutoff,
    "register": scenario_register,
    "auth": scenario_auth,
    "sync": scenario_sync,
    "soft": scenario_soft,
    "hard": scenario_hard,
}


def cleanup(c: Client, r: Report, users):
    """Hard opt-out every test user still holding data, so each run leaves only `users` rows behind."""
    print("\n[cleanup] hard opt-out of the test users")
    for role, u in users.items():
        if role == "hard":
            continue
        status, body = c.call("POST", "/opt-out/hard", None, u["auth_token"], f"cleanup-{role}")
        r.expect(f"cleanup: {role} user's data deleted", status == 200, brief(status, body))


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--reuse-fixtures", action="store_true", help="replay backend-checks/fixtures instead of regenerating")
    parser.add_argument("--only", nargs="+", choices=list(SCENARIOS), help="run only these scenarios")
    args = parser.parse_args()

    base = base_url()
    stamp = dt.datetime.now().strftime("%Y%m%d-%H%M%S")
    out = HERE / "out" / stamp
    out.mkdir(parents=True, exist_ok=True)
    fx = load_fixtures() if args.reuse_fixtures else generate_fixtures()
    print(f"Backend: {base}\nFixtures: {'reused' if args.reuse_fixtures else 'regenerated'} ({FIXTURES})\nRaw log: {out}")

    c, r, users = Client(base, out), Report(), {}
    selected = args.only or list(SCENARIOS)
    if any(s in selected for s in ("sync", "auth")) and "register" not in selected:
        selected = ["register"] + selected
    try:
        for name in SCENARIOS:
            if name in selected:
                SCENARIOS[name](c, r, fx, users)
    finally:
        cleanup(c, r, users)

    counts = {k: sum(1 for row in r.rows if row[0] == k) for k in ("PASS", "FAIL", "INFO")}
    print(f"\n{counts['PASS']} passed, {counts['FAIL']} failed, {counts['INFO']} observed")
    for _, name, detail in r.failed:
        print(f"  FAIL  {name}\n        {detail}")
    (out / "summary.json").write_text(json.dumps([{"result": a, "check": b, "detail": str(d)} for a, b, d in r.rows], indent=2) + "\n",
                                      encoding="utf-8")
    sys.exit(1 if r.failed else 0)


if __name__ == "__main__":
    main()
