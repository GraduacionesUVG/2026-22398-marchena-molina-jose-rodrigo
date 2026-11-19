"""E12: error of the at-least-one-app session rule (README §4.7).

MPUD has real unlock events, so F and S are computed twice on the same
grid windows: from sessions built with the at-least-one-app rule (what the
real data can see) and from sessions built with Unlocked. Descriptive only.

Inputs: data/processed/mpud_preprocess_2.csv (epochs) and
data/processed/mpud_preprocess_3.csv (sessions, apps rule). The unlock-rule
sessions are written to data/processed/mpud_preprocess_3_unlock.csv.
Output: data/reports/e12_one_app_rule.json

    python -m experiments.e12_one_app_rule
"""

import json
from pathlib import Path

import numpy as np
import pandas as pd
from scipy.stats import spearmanr
from sklearn.metrics import cohen_kappa_score

from pipelines.labels.build_windows import seconds
from pipelines.mpud import mpud_preprocess_3
from smartuse.windows import grid_starts, person_windows

EPOCHS_CSV = Path("data/processed/mpud_preprocess_2.csv")
APPS_CSV = Path("data/processed/mpud_preprocess_3.csv")
UNLOCK_CSV = Path("data/processed/mpud_preprocess_3_unlock.csv")
OUT_JSON = Path("data/reports/e12_one_app_rule.json")
W = 3600.0
P = 0.80  # provisional label percentile


def epoch_table(epochs: pd.DataFrame) -> dict:
    """Use epochs by app band and unlock."""
    use = epochs[epochs["is_use"]]
    band = pd.cut(use["distinct_app_count"], [-1, 0, 2, np.inf], labels=["0", "1-2", "3+"])
    table = pd.crosstab(band, use["unlocked"])
    return {
        "counts": {b: {"unlocked": int(table.loc[b, True]), "not_unlocked": int(table.loc[b, False])}
                   for b in table.index},
        "zero_app_with_unlock": float(table.loc["0", True] / table.loc["0"].sum()),
        "one_two_apps_without_unlock": float(table.loc["1-2", False] / table.loc["1-2"].sum()),
    }


def paired_windows(apps: pd.DataFrame, unlock: pd.DataFrame) -> pd.DataFrame:
    """Same grid windows per person over both session logs."""
    parts = []
    for user_id, a in apps.groupby("user_id", sort=False):
        u = unlock[unlock["user_id"] == user_id]
        if u.empty:
            continue
        logs = [(seconds(s["start_utc"]), seconds(s["end_utc"]), s["no_close"].to_numpy()) for s in (a, u)]
        start = min(float(s.min()) for s, _, _ in logs)
        end = max(float(e.max()) for _, e, _ in logs)
        offset_s = float((a["start_local"].iloc[0] - a["start_utc"].iloc[0]).total_seconds())
        times = grid_starts(start, end, W, offset_s)
        wa, wu = (person_windows(s, e, nc, start, end, times, W) for s, e, nc in logs)
        keep = ~(wa["masked"] | wu["masked"] | wa["censored"] | wu["censored"])
        parts.append(pd.DataFrame({
            "user_id": user_id,
            "f_apps": wa["f"][keep], "s_apps": wa["s"][keep], "n_apps": wa["n_sessions"][keep],
            "f_unlock": wu["f"][keep], "s_unlock": wu["s"][keep], "n_unlock": wu["n_sessions"][keep],
        }))
    return pd.concat(parts, ignore_index=True)


def above_p(x: pd.Series, user: pd.Series) -> pd.Series:
    return x > x.groupby(user).transform(lambda v: v.quantile(P))


def compare(w: pd.DataFrame) -> dict:
    out = {"windows": len(w), "users": int(w["user_id"].nunique()),
           "has_sessions_agreement": float(((w["n_apps"] > 0) == (w["n_unlock"] > 0)).mean())}
    for m in ("f", "s"):
        a, u = w[f"{m}_apps"], w[f"{m}_unlock"]
        per_user = w.groupby("user_id").apply(
            lambda g: spearmanr(g[f"{m}_apps"], g[f"{m}_unlock"]).statistic, include_groups=False)
        out[m] = {"median_abs_error": float((a - u).abs().median()),
                  "mean_apps": float(a.mean()), "mean_unlock": float(u.mean()),
                  "spearman": float(spearmanr(a, u).statistic),
                  "spearman_per_user_median": float(per_user.median())}
    flags = {}
    for m in ("f", "s"):
        flags[m] = (above_p(w[f"{m}_apps"], w["user_id"]), above_p(w[f"{m}_unlock"], w["user_id"]))
    flags["f_or_s"] = (flags["f"][0] | flags["s"][0], flags["f"][1] | flags["s"][1])
    out["label_p80"] = {k: {"agreement": float((a == u).mean()), "kappa": float(cohen_kappa_score(a, u)),
                            "prevalence_apps": float(a.mean()), "prevalence_unlock": float(u.mean())}
                        for k, (a, u) in flags.items()}
    return out


def main() -> None:
    epochs = pd.read_csv(EPOCHS_CSV)
    mpud_preprocess_3.main(EPOCHS_CSV, UNLOCK_CSV, rule="unlock")
    dates = ["start_utc", "start_local", "end_utc"]
    apps = pd.read_csv(APPS_CSV, parse_dates=dates)
    unlock = pd.read_csv(UNLOCK_CSV, parse_dates=dates)

    result = {"epochs": epoch_table(epochs),
              "sessions": {"apps": len(apps), "unlock": len(unlock)},
              "windows": compare(paired_windows(apps, unlock))}
    OUT_JSON.parent.mkdir(parents=True, exist_ok=True)
    OUT_JSON.write_text(json.dumps(result, indent=2))
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
