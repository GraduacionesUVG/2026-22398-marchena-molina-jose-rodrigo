#!/usr/bin/env python3
"""
Live checks of the SmartUse backend, deployment group, from the frontend's side.

The deployment app calls three endpoints: POST /users (experiment_group
"inference"), GET /cutoff-deployment-group and POST /final. This checks each
against the documented API contract.

Plain Python 3 standard library only (no pip installs). There's one backend
(it *is* production), so every run registers real test participants:
university_major = "QA-BACKEND-CHECK", experiment_group = "inference" (never
"control"). There is no endpoint to delete them; analysis filters them out.

Usage (from the repo root or this folder):
    python backend-checks/run_checks.py                  # regenerate fixtures, run everything
    python backend-checks/run_checks.py --reuse-fixtures # replay the stored fixtures
    python backend-checks/run_checks.py --only cutoff final

Base URL: $SMARTUSE_BASE_URL, else `smartuse.baseUrl` in local.properties;
the script stops if neither is set. Raw requests/responses of each run go to
backend-checks/out/<timestamp>/ (git-ignored).

Two kinds of check:
  EXPECT  documented behaviour; a mismatch is a FAIL.
  OBSERVE behaviour the docs don't pin down; printed for the record, never
          fails the run.
"""

import argparse
import datetime as dt
import json
import os
import random
import re
import sys
import threading
import urllib.error
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent
FIXTURES = HERE / "fixtures"
QA_MAJOR = "QA-BACKEND-CHECK"
GROUP = "inference"
BIG_METRICS = 10_000  # a few thousand per participant expected; the contract asks for >= 10 000 per body
INT32_MAX = 2_147_483_647


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

def registration(**overrides) -> dict:
    body = {
        "age_at_signup": 21,
        "gender": "Femenino",
        "university_major": QA_MAJOR,
        "test_score": 37,
        "experiment_group": GROUP,
    }
    body.update(overrides)
    return {k: v for k, v in body.items() if v is not None}


def metric(decision_point: str = "screen_on", **overrides) -> dict:
    """As the app sends one: no id, no timestamp; battery_delta_pct may be absent."""
    m = {
        "decision_point": decision_point,
        "duration_ms": random.randint(40, 900),
        "battery_delta_pct": random.choice([0, 0, 0, 1]),
        "current_mean": random.randint(-450_000, -80_000),
        "peak_rss_kb": random.randint(90_000, 160_000),
    }
    m.update(overrides)
    return {k: v for k, v in m.items() if v is not None}


def usability_answers() -> dict:
    """The shape the app sends (forms/FormDefinitions.kt): dN_M ids, choices 1-5, Sí/No 1/0, optional text."""
    ids = ["d1_1", "d1_2", "d2_1", "d2_2", "d3_1", "d4_1", "d5_1", "d6_1", "d7_1", "d8_1", "d8_2", "d9_1", "d10_1"]
    answers = {q: random.randint(1, 5) for q in ids}
    answers["d11_1"] = 0
    answers["d12_1"] = "Todo bien, gracias \U0001F600"
    return answers


def final_body(end_reason: str = "completed", metrics=None, **form_overrides) -> dict:
    form = {"version": 2, "answers": usability_answers()}
    form.update(form_overrides)
    return {
        "end_reason": end_reason,
        "forms": {"usability": form},
        "metrics": [metric(random.choice(["screen_on", "active_use"])) for _ in range(5)] if metrics is None else metrics,
    }


def generate_fixtures() -> dict:
    fx = {
        "register_valid": registration(),
        "final_valid": final_body(),
        "final_opt_out": final_body("opt_out"),
        # 3 good records and 4 bad ones: filtered, never a 400.
        "final_bad_metrics": final_body(metrics=[
            metric(), metric("active_use"), metric(battery_delta_pct=None),
            metric("unknown_point"), metric(duration_ms=-1), {"duration_ms": 10}, "not-an-object",
        ]),
        # Unstorable text: a NUL and a lone high surrogate (cut emoji). Cleaned to U+FFFD, not rejected.
        "final_unstorable_text": final_body(answers={"d1_1": 3, "d12_1": "a\u0000b \ud83d"}),
    }
    FIXTURES.mkdir(exist_ok=True)
    for old in FIXTURES.glob("*.json"):
        old.unlink()
    for name, body in fx.items():
        (FIXTURES / f"{name}.json").write_text(json.dumps(body, indent=2) + "\n", encoding="utf-8")
    return fx


def load_fixtures() -> dict:
    return {p.stem: json.loads(p.read_text(encoding="utf-8")) for p in sorted(FIXTURES.glob("*.json"))}


# --- http -------------------------------------------------------------------

class Client:
    def __init__(self, base: str, out_dir: Path):
        self.base = base
        self.out = out_dir
        self.n = 0
        self.lock = threading.Lock()

    def call(self, method: str, path: str, body=None, token=None, label: str = "", raw: bytes = None):
        """`raw` sends those bytes as the body instead of `body` as JSON."""
        with self.lock:
            self.n += 1
            n = self.n
        data = raw if raw is not None else (None if body is None else json.dumps(body).encode("utf-8"))
        req = urllib.request.Request(self.base + path, data=data, method=method)
        if data is not None:
            req.add_header("Content-Type", "application/json")
        if token:
            req.add_header("Authorization", f"Bearer {token}")
        try:
            with urllib.request.urlopen(req, timeout=60) as resp:
                status, text = resp.status, resp.read().decode("utf-8", "replace")
        except urllib.error.HTTPError as e:
            status, text = e.code, e.read().decode("utf-8", "replace")
        except (urllib.error.URLError, TimeoutError) as e:
            status, text = 0, f"network error: {e}"
        try:
            parsed = json.loads(text) if text else None
        except json.JSONDecodeError:
            parsed = None
        safe = re.sub(r"[^a-z0-9]+", "-", (label or path).lower()).strip("-")
        if raw is not None:
            shown = raw.decode("utf-8", "replace")
        elif isinstance(body, dict) and len(body.get("metrics") or []) > 50:
            shown = {**body, "metrics": f"<{len(body['metrics'])} records>"}
        else:
            shown = body
        (self.out / f"{n:02d}-{safe}.json").write_text(
            json.dumps(
                {"request": {"method": method, "path": path, "auth": bool(token), "body": shown},
                 "response": {"status": status, "body": parsed if parsed is not None else text}},
                indent=2,
            ) + "\n",
            encoding="utf-8",
        )
        return status, parsed if parsed is not None else text


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


def error_is(body, key) -> bool:
    return isinstance(body, dict) and body.get("error") == key


def status_is(body, value) -> bool:
    return isinstance(body, dict) and body.get("status") == value


def register(c: Client, r: Report, label: str, body=None):
    """A fresh inference participant; its token, or None (counted as a FAIL)."""
    status, resp = c.call("POST", "/users", body or registration(), label=label)
    ok = status == 201 and isinstance(resp, dict) and bool(resp.get("user_id")) and bool(resp.get("auth_token"))
    r.expect(f"{label}: 201 with user_id and auth_token", ok, brief(status, resp))
    return resp["auth_token"] if ok else None


# --- scenarios ----------------------------------------------------------------

def scenario_cutoff(c: Client, r: Report, fx):
    print("\n[cutoff] GET /cutoff-deployment-group")
    status, body = c.call("GET", "/cutoff-deployment-group", label="cutoff")
    r.expect("GET /cutoff-deployment-group answers 200", status == 200, brief(status, body))
    ok_shape = isinstance(body, dict) and set(body) == {"cutoff"}
    r.expect("the body is exactly { cutoff }", ok_shape, brief(status, body))
    if ok_shape:
        value = body["cutoff"]
        is_date = value is None or (isinstance(value, str) and re.fullmatch(r"\d{4}-\d{2}-\d{2}", value) is not None)
        r.expect("cutoff is null or a YYYY-MM-DD date (no time part)", is_date, repr(value))
        r.observe("current deployment-group cutoff", value)
    status2, body2 = c.call("GET", "/cutoff-deployment-group", token="not-a-real-token", label="cutoff-with-bogus-token")
    r.expect("it is open: a bogus bearer token doesn't change the answer", status2 == 200 and body2 == body, brief(status2, body2))
    status3, body3 = c.call("GET", "/cutoff", label="data-collection-cutoff")
    r.observe("the data-collection GET /cutoff (never called by this app)", brief(status3, body3))


def scenario_register(c: Client, r: Report, fx):
    print("\n[register] POST /users, experiment_group = inference")
    register(c, r, "register valid (test_score 37)", fx["register_valid"])
    register(c, r, "register test_score 10 (lowest)", registration(test_score=10))
    register(c, r, "register test_score 60 (highest)", registration(test_score=60))
    register(c, r, "register without experiment_config", registration())
    for label, body in [
        ("test_score missing", registration(test_score=None)),
        ("test_score 9", registration(test_score=9)),
        ("test_score 61", registration(test_score=61)),
        ("test_score 37.5", registration(test_score=37.5)),
    ]:
        status, resp = c.call("POST", "/users", body, label=f"register {label}")
        r.expect(f"{label} -> 400 naming test_score",
                 status == 400 and "test_score" in json.dumps(resp), brief(status, resp))
    r.observe("not checkable live", "that the stored row has test_score (no read endpoint)")


def scenario_auth(c: Client, r: Report, fx):
    print("\n[auth] POST /final bearer token")
    status, body = c.call("POST", "/final", fx["final_valid"], label="final-no-token")
    r.expect("without a token -> 401 {error: unauthorized}", status == 401 and error_is(body, "unauthorized"), brief(status, body))
    status, body = c.call("POST", "/final", fx["final_valid"], token="bogus-token", label="final-bogus-token")
    r.expect("with an unknown token -> 401 {error: unauthorized}", status == 401 and error_is(body, "unauthorized"), brief(status, body))


def scenario_invalid(c: Client, r: Report, fx):
    print("\n[invalid] POST /final 400 cases (one user, nothing stored)")
    tok = register(c, r, "register invalid-body user")
    if not tok:
        return
    valid = fx["final_valid"]
    answers = valid["forms"]["usability"]["answers"]

    def with_answer(value):
        return final_body(answers={**answers, "d1_1": value})

    cases = [
        ("a body that isn't JSON", None, b"{not json"),
        ("a JSON array body", [valid], None),
        ("end_reason not completed/opt_out", {**valid, "end_reason": "finished"}, None),
        ("forms missing", {k: v for k, v in valid.items() if k != "forms"}, None),
        ("forms not an object", {**valid, "forms": []}, None),
        ("forms.usability missing", {**valid, "forms": {"other": valid["forms"]["usability"]}}, None),
        ("version 0", final_body(version=0), None),
        ("version above 2^31-1", final_body(version=INT32_MAX + 1), None),
        ("answers not an object", final_body(answers=[1, 2]), None),
        ("an answer true", with_answer(True), None),
        ("an answer null", with_answer(None), None),
        ("an answer 5.5", with_answer(5.5), None),
        ("an answer that is an array", with_answer([5]), None),
        ("an integer answer above 2^31-1", with_answer(INT32_MAX + 1), None),
        ("metrics not an array", {**valid, "metrics": {"decision_point": "screen_on"}}, None),
    ]
    for label, body, raw in cases:
        status, resp = c.call("POST", "/final", body, tok, f"invalid {label}", raw=raw)
        r.expect(f"{label} -> 400 invalid_body", status == 400 and error_is(resp, "invalid_body"), brief(status, resp))
    # Every 400 above stored nothing: the first valid send is still a 201.
    status, resp = c.call("POST", "/final", valid, tok, "valid after the 400s")
    r.expect("after only 400s, a valid send -> 201 (the 400s stored nothing)",
             status == 201 and status_is(resp, "stored"), brief(status, resp))


def scenario_final(c: Client, r: Report, fx):
    print("\n[final] POST /final: stored once, first send wins")
    tok = register(c, r, "register final user")
    if not tok:
        return
    status, body = c.call("POST", "/final", fx["final_valid"], tok, "final valid")
    r.expect("a valid send -> 201 {status: stored}", status == 201 and status_is(body, "stored"), brief(status, body))
    status, body = c.call("POST", "/final", fx["final_valid"], tok, "final same again")
    r.expect("the same send again -> 200 {status: already_received}",
             status == 200 and status_is(body, "already_received"), brief(status, body))
    status, body = c.call("POST", "/final", fx["final_opt_out"], tok, "final different body")
    r.expect("a different send for the same user -> 200 already_received",
             status == 200 and status_is(body, "already_received"), brief(status, body))
    status, body = c.call("POST", "/final", None, tok, "final invalid after stored", raw=b"{not json")
    r.expect("even an invalid body after a stored send -> 200 already_received (checked before the body)",
             status == 200 and status_is(body, "already_received"), brief(status, body))

    tok = register(c, r, "register opt-out user")
    if tok:
        status, body = c.call("POST", "/final", fx["final_opt_out"], tok, "final opt_out")
        r.expect("end_reason opt_out -> 201", status == 201 and status_is(body, "stored"), brief(status, body))

    tok = register(c, r, "register bad-metrics user")
    if tok:
        status, body = c.call("POST", "/final", fx["final_bad_metrics"], tok, "final bad metrics")
        r.expect("bad metric records are skipped, never a 400 -> 201", status == 201, brief(status, body))

    tok = register(c, r, "register no-metrics user")
    if tok:
        body_ = {k: v for k, v in fx["final_valid"].items() if k != "metrics"}
        status, body = c.call("POST", "/final", body_, tok, "final without metrics")
        r.expect("metrics missing counts as empty -> 201", status == 201, brief(status, body))

    tok = register(c, r, "register unstorable-text user")
    if tok:
        status, body = c.call("POST", "/final", fx["final_unstorable_text"], tok, "final unstorable text")
        r.expect("a NUL and a lone surrogate in text are cleaned, not rejected -> 201", status == 201, brief(status, body))


def scenario_size(c: Client, r: Report, fx):
    print(f"\n[size] POST /final with {BIG_METRICS} metric records")
    tok = register(c, r, "register size user")
    if not tok:
        return
    body = final_body(metrics=[metric(random.choice(["screen_on", "active_use"])) for _ in range(BIG_METRICS)])
    r.observe("body size", f"{len(json.dumps(body)) / 1e6:.2f} MB")
    status, resp = c.call("POST", "/final", body, tok, "final big")
    r.expect(f"{BIG_METRICS} metric records -> 201", status == 201 and status_is(resp, "stored"), brief(status, resp))


def scenario_concurrent(c: Client, r: Report, fx):
    print("\n[concurrent] two identical sends at once")
    tok = register(c, r, "register concurrent user")
    if not tok:
        return
    results = [None, None]

    def send(i):
        results[i] = c.call("POST", "/final", fx["final_valid"], tok, f"final concurrent {i}")

    threads = [threading.Thread(target=send, args=(i,)) for i in range(2)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    statuses = sorted(s for s, _ in results)
    r.expect("exactly one 201 and one 200", statuses == [200, 201], f"statuses {statuses}: {results}")


SCENARIOS = {
    "cutoff": scenario_cutoff,
    "register": scenario_register,
    "auth": scenario_auth,
    "invalid": scenario_invalid,
    "final": scenario_final,
    "size": scenario_size,
    "concurrent": scenario_concurrent,
}


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--reuse-fixtures", action="store_true", help="replay backend-checks/fixtures instead of regenerating")
    parser.add_argument("--only", nargs="+", choices=list(SCENARIOS), help="run only these scenarios")
    args = parser.parse_args()
    sys.stdout.reconfigure(errors="replace")

    base = base_url()
    stamp = dt.datetime.now().strftime("%Y%m%d-%H%M%S")
    out = HERE / "out" / stamp
    out.mkdir(parents=True, exist_ok=True)
    fx = load_fixtures() if args.reuse_fixtures else generate_fixtures()
    print(f"Backend: {base}\nFixtures: {'reused' if args.reuse_fixtures else 'regenerated'} ({FIXTURES})\nRaw log: {out}")

    c, r = Client(base, out), Report()
    for name in args.only or list(SCENARIOS):
        SCENARIOS[name](c, r, fx)

    counts = {k: sum(1 for row in r.rows if row[0] == k) for k in ("PASS", "FAIL", "INFO")}
    print(f"\n{counts['PASS']} passed, {counts['FAIL']} failed, {counts['INFO']} observed")
    for _, name, detail in r.failed:
        print(f"  FAIL  {name}\n        {detail}")
    (out / "summary.json").write_text(json.dumps([{"result": a, "check": b, "detail": str(d)} for a, b, d in r.rows], indent=2) + "\n",
                                      encoding="utf-8")
    sys.exit(1 if r.failed else 0)


if __name__ == "__main__":
    main()
