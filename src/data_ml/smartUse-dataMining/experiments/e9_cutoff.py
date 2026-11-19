"""E9: operating cutoff c (README §8.2). Report only.

Walks each person's decision points in time order with the leave-one-person-out
scores of XGBoost + MPUD: intervene when score > c and COOLDOWN_S have passed
since the last intervention. Per c: precision, lift over prevalence, episode
coverage and interventions per person-day. Persistence as a rule is the
reference.

Outputs: data/reports/e8_xgb_scores.csv (out-of-fold scores, reused later),
data/reports/e9_cutoff.json

    python -m experiments.e9_cutoff
"""

import json
from pathlib import Path

import numpy as np
import pandas as pd

from experiments import tabular as T
from experiments.modeling import feature_cols, load, persistence_score
from pipelines.labels.build_windows import seconds

SCORES_CSV = Path("data/reports/e8_xgb_scores.csv")
OUT_JSON = Path("data/reports/e9_cutoff.json")
COOLDOWN_S = 600.0
EPISODE_GAP_S = 3600.0


def scores() -> pd.DataFrame:
    if SCORES_CSV.exists():
        return pd.read_csv(SCORES_CSV, parse_dates=["day"])
    real, mpud = load("real"), load("mpud")
    cols = feature_cols(real)
    pre = T.pretrain_on_mpud(T.XGB, mpud, cols)
    out = real[["user_id", "t_utc", "day", "y"]].copy()
    out["score"] = T.run_loso(T.XGB, real, cols, pre)
    out["persist"] = persistence_score(real).to_numpy()
    out.to_csv(SCORES_CSV, index=False)
    return out


def episodes(df: pd.DataFrame) -> pd.Series:
    """Episode id per positive point (NaN for negatives); df sorted by user and t."""
    t = df["t"].to_numpy()
    new = df["y"].to_numpy() & ~(
        df["y"].shift(fill_value=False).to_numpy()
        & (df["user_id"] == df["user_id"].shift()).to_numpy()
        & (np.diff(t, prepend=-np.inf) <= EPISODE_GAP_S))
    ep = pd.Series(np.cumsum(new), index=df.index).where(df["y"])
    return ep


def simulate(df: pd.DataFrame, fire: np.ndarray) -> np.ndarray:
    """Interventions given where the rule fires, respecting the cooldown."""
    out = np.zeros(len(df), dtype=bool)
    t, users = df["t"].to_numpy(), df["user_id"].to_numpy()
    last, last_user = -np.inf, None
    for i in range(len(df)):
        if users[i] != last_user:
            last, last_user = -np.inf, users[i]
        if fire[i] and t[i] - last >= COOLDOWN_S:
            out[i], last = True, t[i]
    return out


def describe(df: pd.DataFrame, given: np.ndarray, ep: pd.Series) -> dict:
    y = df["y"].to_numpy()
    per_day = pd.Series(given).groupby([df["user_id"].to_numpy(), df["day"].to_numpy()]).sum()
    reached = ep[given].dropna().nunique()
    n = int(given.sum())
    return {"interventions": n,
            "precision": float(y[given].mean()) if n else float("nan"),
            "lift": float(y[given].mean() / y.mean()) if n else float("nan"),
            "episode_coverage": float(reached / ep.nunique()),
            "per_day_median": float(per_day.median()), "per_day_p90": float(per_day.quantile(0.9))}


def main() -> None:
    df = scores()
    df["t"] = seconds(pd.to_datetime(df["t_utc"]))
    df = df.sort_values(["user_id", "t"]).reset_index(drop=True)
    df["y"] = df["y"].astype(bool)
    ep = episodes(df)
    cuts = np.round(np.quantile(df["score"], np.arange(0.05, 1.0, 0.05)), 3)
    rows = {f"c={c:.3f}": describe(df, simulate(df, df["score"].to_numpy() > c), ep) for c in cuts}
    rows["persistence rule"] = describe(df, simulate(df, df["persist"].to_numpy() > 0), ep)
    result = {"prevalence": float(df["y"].mean()), "episodes": int(ep.nunique()),
              "person_days": int(df.groupby(["user_id", "day"]).ngroups), "by_cutoff": rows}
    OUT_JSON.write_text(json.dumps(result, indent=2))
    print(f"prevalence {result['prevalence']:.1%}, {result['episodes']} episodes, {result['person_days']} person-days")
    print(pd.DataFrame(rows).T.round(3).to_string())


if __name__ == "__main__":
    main()
