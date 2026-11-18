"""Shared pieces of the model notebooks (README §7)."""

import math

import numpy as np
import pandas as pd
from sklearn.metrics import average_precision_score, brier_score_loss, roc_auc_score

META_COLS = ["user_id", "t_utc", "t_local", "day", "is_unlock", "y", "y_f", "y_s"]
PROFILE_EXTRA = {"f60_rank", "s60_rank", "sessions_vs_hour_typical"}
TIME_CONTEXT = {"tod_sin", "tod_cos", "weekend", "in_session"}
CURRENT = {"cur_logdur", "since_last_logmin", "no_previous"}


def load(dataset: str) -> pd.DataFrame:
    return pd.read_csv(f"data/processed/{dataset}_features.csv", parse_dates=["day"])


def feature_cols(df: pd.DataFrame) -> list[str]:
    return [c for c in df.columns if c not in META_COLS]


def block_of(col: str) -> str:
    if col in CURRENT:
        return "current"
    if col.startswith("prof_") or col in PROFILE_EXTRA:
        return "profile"
    if col in TIME_CONTEXT:
        return "time_context"
    if col.endswith("_15"):
        return "w15"
    if col.endswith("_60"):
        return "w60"
    return "day"


BLOCKS = {name: (lambda cols, name=name: [c for c in cols if block_of(c) == name])
          for name in ("current", "w15", "w60", "day", "profile", "time_context")}


def persistence_score(df: pd.DataFrame) -> pd.Series:
    """Baseline: the past hour's label (F_60 or S_60 above the profile)."""
    return ((df["f_60"] > df["prof_pf"]) | (df["s_60"] > df["prof_ps"])).astype(float)


def loso_folds(users: pd.Series, n_val: int = 3, seed: int = 0):
    """Yield (test, val, train) person lists, one fold per person."""
    everyone = sorted(users.unique())
    for i, test in enumerate(everyone):
        rest = [u for u in everyone if u != test]
        val = list(np.random.default_rng(seed + i).choice(rest, size=n_val, replace=False))
        yield [test], val, [u for u in rest if u not in val]


def temporal_split(df: pd.DataFrame, frac: float = 0.6) -> tuple[pd.DataFrame, pd.DataFrame]:
    """Per person, the first frac of example days train and the rest test."""
    train_mask = pd.Series(False, index=df.index)
    for _, g in df.groupby("user_id"):
        days = sorted(g["day"].unique())
        cut = min(max(1, math.floor(len(days) * frac)), len(days) - 1)
        train_mask[g.index] = g["day"].isin(days[:cut])
    return df[train_mask], df[~train_mask]


def _per_person_auc(y: np.ndarray, score: np.ndarray, users: np.ndarray) -> dict:
    out = {}
    for u in np.unique(users):
        m = users == u
        if len(np.unique(y[m])) == 2:
            out[u] = float(roc_auc_score(y[m], score[m]))
    return out


def evaluate(y, score, users, persist, n_boot: int = 1000, seed: int = 0) -> dict:
    """AUC per person (median, range, bootstrap CI over people), pooled AUC,
    PR-AUC, Brier and onset AUC (points whose past hour was negative)."""
    y, score = np.asarray(y).astype(int), np.asarray(score, dtype=float)
    users, persist = np.asarray(users), np.asarray(persist)
    per = _per_person_auc(y, score, users)
    values = np.array(list(per.values()))
    rng = np.random.default_rng(seed)
    boot = [np.median(rng.choice(values, size=len(values), replace=True)) for _ in range(n_boot)]
    onset = persist == 0
    per_onset = _per_person_auc(y[onset], score[onset], users[onset])
    r = {
        "auc_per_person": per,
        "auc_median": float(np.median(values)),
        "auc_min": float(values.min()), "auc_max": float(values.max()),
        "auc_median_ci95": [float(np.percentile(boot, 2.5)), float(np.percentile(boot, 97.5))],
        "auc_pooled": float(roc_auc_score(y, score)),
        "pr_auc_pooled": float(average_precision_score(y, score)),
        "prevalence": float(y.mean()),
        "auc_onset_median": float(np.median(list(per_onset.values()))) if per_onset else float("nan"),
    }
    if score.min() >= 0 and score.max() <= 1:
        r["brier"] = float(brier_score_loss(y, score))
    return r
