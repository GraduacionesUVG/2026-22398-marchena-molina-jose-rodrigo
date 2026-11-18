"""E7: overlap of F and S (README §5.6).

Pre-registered criterion (2026-10-19): the median across people of the
within-person Spearman correlation between F and S, on 60 min grid windows
with at least one session, is <= 0.5. Also reported: share of positives
(p = 0.80) only by F, only by S and by both.

Input: data/processed/real_preprocess_3.csv. Output: data/reports/e7_f_s_overlap.json

    python -m experiments.e7_f_s_overlap
"""

import json
from pathlib import Path

import pandas as pd
from scipy.stats import spearmanr

from experiments.common import above_p, paired_grid

SESSIONS_CSV = Path("data/processed/real_preprocess_3.csv")
OUT_JSON = Path("data/reports/e7_f_s_overlap.json")
MAX_CORR = 0.5
W = 3600.0
P = 0.80


def main() -> None:
    sessions = pd.read_csv(SESSIONS_CSV, parse_dates=["start_utc", "start_local", "end_utc"])
    windows = paired_grid({"x": sessions}, W)

    busy = windows[windows["n_x"] > 0]
    per_user = busy.groupby("user_id").apply(
        lambda g: spearmanr(g["f_x"], g["s_x"]).statistic, include_groups=False).dropna()

    f_pos = above_p(windows["f_x"], windows["user_id"], P)
    s_pos = above_p(windows["s_x"], windows["user_id"], P)
    positive = f_pos | s_pos
    result = {
        "windows": len(windows), "windows_with_sessions": len(busy),
        "users": int(windows["user_id"].nunique()),
        "spearman_per_user": {"median": float(per_user.median()), "min": float(per_user.min()),
                              "max": float(per_user.max())},
        "spearman_pooled": float(spearmanr(busy["f_x"], busy["s_x"]).statistic),
        "positives": {"prevalence": float(positive.mean()),
                      "only_f": float((f_pos & ~s_pos).sum() / positive.sum()),
                      "only_s": float((s_pos & ~f_pos).sum() / positive.sum()),
                      "both": float((f_pos & s_pos).sum() / positive.sum())},
    }
    result["supports_or_label"] = result["spearman_per_user"]["median"] <= MAX_CORR

    OUT_JSON.parent.mkdir(parents=True, exist_ok=True)
    OUT_JSON.write_text(json.dumps(result, indent=2))
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
