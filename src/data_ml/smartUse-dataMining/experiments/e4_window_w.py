"""E4: sensitivity of the window W, 60 vs 30 min (README §5.6).

Report only (pre-registered 2026-10-19): W = 60 min is fixed by Siebers'
validity. Each 60 min grid window is compared with its two 30 min halves:
the 30 min version is positive if either half is positive, with
percentiles computed on the W = 30 windows. Only hours with both halves
usable are compared.

Input: data/processed/real_preprocess_3.csv. Output: data/reports/e4_window_w.json

    python -m experiments.e4_window_w
"""

import json
from pathlib import Path

import pandas as pd
from sklearn.metrics import cohen_kappa_score

from experiments.common import label_p, paired_grid

SESSIONS_CSV = Path("data/processed/real_preprocess_3.csv")
OUT_JSON = Path("data/reports/e4_window_w.json")
P = 0.80


def main() -> None:
    sessions = pd.read_csv(SESSIONS_CSV, parse_dates=["start_utc", "start_local", "end_utc"])
    offsets = (sessions["start_local"] - sessions["start_utc"]).dt.total_seconds()
    assert (offsets % 3600 == 0).all(), "half-hour pairing assumes whole-hour UTC offsets"

    w60 = paired_grid({"x": sessions}, 3600.0)
    w60["y60"] = label_p(w60, "x", P)
    w30 = paired_grid({"x": sessions}, 1800.0)
    w30["y30"] = label_p(w30, "x", P)
    w30["t"] = (w30["t"] // 3600) * 3600
    halves = w30.groupby(["user_id", "t"]).agg(y30=("y30", "any"), n=("y30", "size")).reset_index()
    pairs = w60.merge(halves[halves["n"] == 2], on=["user_id", "t"])

    result = {
        "windows_60": len(w60), "windows_30": len(w30), "paired_hours": len(pairs),
        "prevalence_60": float(w60["y60"].mean()), "prevalence_30_per_window": float(w30["y30"].mean()),
        "paired": {"prevalence_60": float(pairs["y60"].mean()),
                   "prevalence_30_either_half": float(pairs["y30"].mean()),
                   "agreement": float((pairs["y60"] == pairs["y30"]).mean()),
                   "kappa": float(cohen_kappa_score(pairs["y60"], pairs["y30"]))},
    }
    OUT_JSON.parent.mkdir(parents=True, exist_ok=True)
    OUT_JSON.write_text(json.dumps(result, indent=2))
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
