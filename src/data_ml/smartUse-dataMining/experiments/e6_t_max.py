"""E6: threshold for sessions with no close (README §4.8).

Pre-registered criteria (2026-10-18):
  truth (MPUD): longest gap without counted App events > 30 min and > half
                the session
  recall:       T_max flags >= 80 % of truth sessions
  masking:      on the real decision points, masked share per person has
                median <= 5 % and p90 <= 15 %
  choice:       among CANDIDATES_H, the one meeting both closest to 3 h; a
                tie goes to the larger

Inputs: data/processed/mpud_preprocess_1.csv (events),
data/processed/mpud_preprocess_3.csv and real_preprocess_3.csv (sessions).
Output: data/reports/e6_t_max.json

    python -m experiments.e6_t_max
"""

import json
from pathlib import Path

import pandas as pd

from pipelines.labels.build_windows import build
from pipelines.mpud.mpud_preprocess_2 import counted_apps

EVENTS_CSV = Path("data/processed/mpud_preprocess_1.csv")
MPUD_SESSIONS = Path("data/processed/mpud_preprocess_3.csv")
REAL_SESSIONS = Path("data/processed/real_preprocess_3.csv")
OUT_JSON = Path("data/reports/e6_t_max.json")

CANDIDATES_H = [2.0, 2.5, 3.0, 3.5, 4.0]
TRUTH_GAP_MIN = 30.0
TRUTH_GAP_SHARE = 0.5
MIN_RECALL = 0.80
MAX_MASK_MEDIAN = 0.05
MAX_MASK_P90 = 0.15
W_S, K_S = 3600.0, 600.0


def longest_idle_gap_s(sessions: pd.DataFrame, apps: pd.DataFrame) -> pd.Series:
    """Longest stretch inside each session (start, counted App events, end) with no App event."""
    s = sessions.reset_index(names="sid")
    owner = s[["user_id", "start_utc", "end_utc", "sid"]].sort_values("start_utc")
    apps = apps.rename(columns={"uuid": "user_id"}).sort_values("ts_utc")
    m = pd.merge_asof(apps[["user_id", "ts_utc"]], owner, left_on="ts_utc", right_on="start_utc",
                      by="user_id", direction="backward")
    m = m[m["ts_utc"] < m["end_utc"]][["sid", "ts_utc"]]
    bounds = pd.concat([s[["sid", "start_utc"]].rename(columns={"start_utc": "ts_utc"}), m,
                        s[["sid", "end_utc"]].rename(columns={"end_utc": "ts_utc"})])
    bounds = bounds.sort_values(["sid", "ts_utc"])
    gap = bounds.groupby("sid")["ts_utc"].diff().dt.total_seconds()
    return gap.groupby(bounds["sid"]).max().reindex(s["sid"]).set_axis(sessions.index)


def real_masking(real: pd.DataFrame, t_max_h: float) -> dict:
    sessions = real.assign(no_close=real["duration_s"] > t_max_h * 3600)
    points = build(sessions, "points", W_S, K_S, t_max_h * 3600)
    share = points.groupby("user_id")["masked"].mean()
    return {"median": float(share.median()), "p90": float(share.quantile(0.9)), "max": float(share.max())}


def main() -> None:
    dates = ["start_utc", "start_local", "end_utc"]
    mpud = pd.read_csv(MPUD_SESSIONS, parse_dates=dates)
    real = pd.read_csv(REAL_SESSIONS, parse_dates=dates)
    events = pd.read_csv(EVENTS_CSV, usecols=["uuid", "ts_utc", "sensor_id", "value"],
                         dtype={"value": str}, keep_default_na=False, parse_dates=["ts_utc"])

    gap_s = longest_idle_gap_s(mpud, counted_apps(events))
    truth = (gap_s > TRUTH_GAP_MIN * 60) & (gap_s > TRUTH_GAP_SHARE * mpud["duration_s"])

    rows = []
    for t in CANDIDATES_H:
        flagged = mpud["duration_s"] > t * 3600
        recall = float((flagged & truth).sum() / truth.sum())
        precision = float((flagged & truth).sum() / flagged.sum())
        mask = real_masking(real, t)
        ok = recall >= MIN_RECALL and mask["median"] <= MAX_MASK_MEDIAN and mask["p90"] <= MAX_MASK_P90
        rows.append({"t_max_h": t, "recall": recall, "precision": precision,
                     "mpud_flagged": int(flagged.sum()), "real_masking": mask, "meets_criteria": ok})

    passing = [r["t_max_h"] for r in rows if r["meets_criteria"]]
    chosen = min(passing, key=lambda t: (abs(t - 3.0), -t)) if passing else None
    truth_by_len = (truth.groupby(pd.cut(mpud["duration_s"] / 3600, [0, 1, 2, 3, 4, 1e9])).sum()
                    .rename(lambda iv: str(iv)).astype(int).to_dict())
    result = {"truth_sessions": int(truth.sum()), "truth_by_duration_h": truth_by_len,
              "candidates": rows, "chosen_t_max_h": chosen}
    OUT_JSON.parent.mkdir(parents=True, exist_ok=True)
    OUT_JSON.write_text(json.dumps(result, indent=2))
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
