"""Acceptance checks for the deployment-group endpoints (README, "Acceptance checks").

Runs against a deployed stack. Every user it registers has
university_major = "QA-BACKEND-CHECK", so analysis filters them out.

Usage: python scripts/check_deployment.py <api_base_url>
"""

import json
import re
import sys
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor

BASE = ""
FAILURES = 0


def request(method, path, body=None, token=None):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            return resp.status, json.loads(resp.read() or b"null")
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return e.code, json.loads(raw)
        except ValueError:
            return e.code, raw.decode(errors="replace")


def check(name, ok, detail=""):
    global FAILURES
    print(("PASS " if ok else "FAIL ") + name + ("" if ok else f"  {detail}"))
    if not ok:
        FAILURES += 1


def register(test_score=37, omit_score=False):
    body = {
        "age_at_signup": 21,
        "gender": "femenino",
        "university_major": "QA-BACKEND-CHECK",
        "test_score": test_score,
        "experiment_group": "inference",
    }
    if omit_score:
        del body["test_score"]
    return request("POST", "/users", body)


def fresh_token():
    status, body = register()
    assert status == 201, (status, body)
    return body["auth_token"]


def valid_send(metrics=None):
    return {
        "end_reason": "completed",
        "forms": {"usability": {"version": 2, "answers": {"d1_1": 5, "d8_3": "texto libre"}}},
        "metrics": metrics if metrics is not None else [
            {"decision_point": "screen_on", "duration_ms": 412, "battery_delta_pct": 0, "current_mean": -310000, "peak_rss_kb": 98304},
            {"decision_point": "active_use", "duration_ms": 35},
        ],
    }


def main():
    global BASE
    BASE = sys.argv[1].rstrip("/")

    # Registration
    status, body = register(37)
    check("POST /users inference test_score 37 -> 201", status == 201, (status, body))
    for label, kwargs in [("missing", {"omit_score": True}), ("9", {"test_score": 9}), ("61", {"test_score": 61}), ("37.5", {"test_score": 37.5})]:
        status, body = register(**kwargs)
        check(f"POST /users inference test_score {label} -> 400", status == 400, (status, body))

    # Cutoff
    status, body = request("GET", "/cutoff-deployment-group")
    cutoff = body.get("cutoff", "missing") if isinstance(body, dict) else "missing"
    check(
        "GET /cutoff-deployment-group -> 200 date or null",
        status == 200 and (cutoff is None or (isinstance(cutoff, str) and re.fullmatch(r"\d{4}-\d{2}-\d{2}", cutoff))),
        (status, body),
    )

    # Auth
    status, body = request("POST", "/final", valid_send())
    check("POST /final without token -> 401 unauthorized", status == 401 and body == {"error": "unauthorized"}, (status, body))
    status, body = request("POST", "/final", valid_send(), token="bogus")
    check("POST /final unknown token -> 401", status == 401, (status, body))

    # 400 cases (fresh user each: a stored send would short-circuit to 200)
    base = valid_send()
    bad_bodies = {
        "end_reason unknown": {**base, "end_reason": "done"},
        "forms missing": {k: v for k, v in base.items() if k != "forms"},
        "forms not object": {**base, "forms": []},
        "usability missing": {**base, "forms": {}},
        "version 0": {**base, "forms": {"usability": {"version": 0, "answers": {}}}},
        "answers not object": {**base, "forms": {"usability": {"version": 2, "answers": []}}},
        "answer boolean": {**base, "forms": {"usability": {"version": 2, "answers": {"d1_1": True}}}},
        "answer above int32": {**base, "forms": {"usability": {"version": 2, "answers": {"d1_1": 2147483648}}}},
        "metrics not array": {**base, "metrics": {}},
    }
    for label, bad in bad_bodies.items():
        status, body = request("POST", "/final", bad, token=fresh_token())
        check(f"POST /final {label} -> 400 invalid_body", status == 400 and isinstance(body, dict) and body.get("error") == "invalid_body", (status, body))

    # Store, idempotency, skipped metrics
    token = fresh_token()
    send = valid_send()
    send["metrics"] += [{"decision_point": "other", "duration_ms": 1}, {"decision_point": "screen_on", "duration_ms": -1}]
    status, body = request("POST", "/final", send, token=token)
    check("POST /final with 2 bad metrics -> 201 stored", status == 201 and body == {"status": "stored"}, (status, body))
    status, body = request("POST", "/final", send, token=token)
    check("POST /final same body again -> 200 already_received", status == 200 and body == {"status": "already_received"}, (status, body))
    status, body = request("POST", "/final", {**send, "end_reason": "opt_out"}, token=token)
    check("POST /final different body -> 200 already_received", status == 200 and body == {"status": "already_received"}, (status, body))

    # 10 000 metrics
    big = valid_send([{"decision_point": "screen_on", "duration_ms": i % 5000} for i in range(10000)])
    status, body = request("POST", "/final", big, token=fresh_token())
    check("POST /final 10 000 metrics -> 201", status == 201, (status, body))

    # Concurrent duplicates
    token = fresh_token()
    with ThreadPoolExecutor(2) as pool:
        results = list(pool.map(lambda _: request("POST", "/final", valid_send(), token=token), range(2)))
    codes = sorted(s for s, _ in results)
    check("two concurrent sends -> one 201, one 200", codes == [200, 201], results)

    print(f"\n{FAILURES} failure(s)")
    sys.exit(1 if FAILURES else 0)


if __name__ == "__main__":
    main()
