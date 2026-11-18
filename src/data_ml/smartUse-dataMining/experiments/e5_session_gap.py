"""E5: robustness of the 30 s session gap (README §5.6).

Pre-registered criterion (2026-10-19): the F-or-S label (p = 0.80) with each
gap in OTHER_GAPS_S agrees with the 30 s label at Cohen's kappa >= 0.80, on
the same 60 min grid windows.

Input: data/processed/real_preprocess_2.csv (epochs). Session logs per gap
go to data/processed/e5/. Output: data/reports/e5_session_gap.json

    python -m experiments.e5_session_gap
"""

import json
from pathlib import Path

import pandas as pd
from sklearn.metrics import cohen_kappa_score

from experiments.common import label_p, paired_grid
from pipelines.real import real_preprocess_3

EPOCHS_CSV = Path("data/processed/real_preprocess_2.csv")
SESSIONS_DIR = Path("data/processed/e5")
OUT_JSON = Path("data/reports/e5_session_gap.json")
BASE_GAP_S = 30
OTHER_GAPS_S = [0, 10, 60, 90]
MIN_KAPPA = 0.80
W = 3600.0
P = 0.80


def main() -> None:
    logs = {}
    for gap in [BASE_GAP_S, *OTHER_GAPS_S]:
        path = SESSIONS_DIR / f"real_preprocess_3_gap{gap}.csv"
        real_preprocess_3.main(EPOCHS_CSV, path, float(gap))
        logs[f"g{gap}"] = pd.read_csv(path, parse_dates=["start_utc", "start_local", "end_utc"])

    windows = paired_grid(logs, W)
    base = label_p(windows, f"g{BASE_GAP_S}", P)
    result = {"windows": len(windows), "users": int(windows["user_id"].nunique()),
              "base": {"sessions": len(logs[f"g{BASE_GAP_S}"]), "prevalence": float(base.mean())},
              "gaps": {}}
    for gap in OTHER_GAPS_S:
        lab = label_p(windows, f"g{gap}", P)
        result["gaps"][gap] = {"sessions": len(logs[f"g{gap}"]), "prevalence": float(lab.mean()),
                               "agreement": float((lab == base).mean()),
                               "kappa": float(cohen_kappa_score(base, lab))}
    result["confirms_30s"] = all(g["kappa"] >= MIN_KAPPA for g in result["gaps"].values())

    OUT_JSON.parent.mkdir(parents=True, exist_ok=True)
    OUT_JSON.write_text(json.dumps(result, indent=2))
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
