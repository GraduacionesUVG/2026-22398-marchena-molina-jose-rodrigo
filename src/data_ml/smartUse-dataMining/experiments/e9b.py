"""E9b: operating cutoffs c_F and c_S with the episode rule (README §8.3).

Simulation over each person's decision points in time order:
  - a pattern fires when its score is above its cutoff
  - at most one intervention per pattern every EPISODE_S (60 min, the label horizon)
  - COOLDOWN_S (10 min) between interventions of any pattern
  - if both fire on the same point, the one with the larger margin over its cutoff wins

Metrics per pattern: precision (interventions on points positive for that
pattern), episode sensitivity (positive episodes with at least one intervention;
an episode is a run of positive points less than EPISODE_S apart), point
coverage (positive points with an intervention of that pattern in the previous
EPISODE_S) and interventions per person-day (mean and p90).
"""

import numpy as np
import pandas as pd

COOLDOWN_S = 600.0
EPISODE_S = 3600.0
PRECISION_FLOOR = 0.50
PER_PATTERN_MAX_DAY = 6.0
TOTAL_MAX_DAY = 12.0


def simulate_joint(df: pd.DataFrame, c_f: float, c_s: float) -> np.ndarray:
    """'F', 'S' or '' per row; df sorted by user_id and t (seconds)."""
    out = np.full(len(df), "", dtype=object)
    users, t = df["user_id"].to_numpy(), df["t"].to_numpy()
    m_f, m_s = df["p_f"].to_numpy() - c_f, df["p_s"].to_numpy() - c_s
    last_any = last_f = last_s = -np.inf
    prev_user = None
    for i in range(len(df)):
        if users[i] != prev_user:
            last_any = last_f = last_s = -np.inf
            prev_user = users[i]
        if t[i] - last_any < COOLDOWN_S:
            continue
        ok_f = m_f[i] > 0 and t[i] - last_f >= EPISODE_S
        ok_s = m_s[i] > 0 and t[i] - last_s >= EPISODE_S
        if ok_f and (not ok_s or m_f[i] >= m_s[i]):
            out[i], last_f, last_any = "F", t[i], t[i]
        elif ok_s:
            out[i], last_s, last_any = "S", t[i], t[i]
    return out


def episodes(df: pd.DataFrame, label: str) -> pd.Series:
    """Episode id per positive point of `label` (NaN for negatives)."""
    y = df[label].astype(bool).to_numpy()
    same_user = (df["user_id"] == df["user_id"].shift()).to_numpy()
    close = np.diff(df["t"].to_numpy(), prepend=-np.inf) < EPISODE_S
    prev_pos = np.roll(y, 1)
    prev_pos[0] = False
    new = y & ~(prev_pos & same_user & close)
    return pd.Series(np.cumsum(new), index=df.index, dtype=float).where(y)


def metrics(df: pd.DataFrame, given: np.ndarray, pattern: str) -> dict:
    label = {"F": "y_f", "S": "y_s"}[pattern]
    y = df[label].astype(bool).to_numpy()
    hit = given == pattern
    ep = episodes(df, label)
    # point coverage: an intervention of this pattern in the previous EPISODE_S, same person
    last = pd.Series(np.where(hit, df["t"], np.nan), index=df.index).groupby(df["user_id"]).ffill()
    covered = (df["t"] - last) < EPISODE_S
    per_day = pd.Series(hit).groupby([df["user_id"].to_numpy(), df["day"].to_numpy()]).sum()
    n = int(hit.sum())
    return {"interventions": n,
            "precision": float(y[hit].mean()) if n else float("nan"),
            "episode_sensitivity": float(ep[hit].dropna().nunique() / ep.nunique()) if ep.nunique() else float("nan"),
            "point_coverage": float(covered[y].mean()) if y.any() else float("nan"),
            "per_day_mean": float(per_day.mean()), "per_day_p90": float(per_day.quantile(0.9))}


def sweep(df: pd.DataFrame, pattern: str, cuts: np.ndarray) -> pd.DataFrame:
    """One pattern alone (the other never fires), over candidate cutoffs."""
    rows = []
    for c in cuts:
        cf, cs = (c, np.inf) if pattern == "F" else (np.inf, c)
        rows.append({"c": float(c), **metrics(df, simulate_joint(df, cf, cs), pattern)})
    return pd.DataFrame(rows)


def choose(table: pd.DataFrame) -> tuple[float, bool]:
    """Lowest c meeting the precision floor and the per-pattern daily cap;
    else the most precise c. Returns (c, met_both)."""
    ok = table[(table["precision"] >= PRECISION_FLOOR) & (table["per_day_mean"] <= PER_PATTERN_MAX_DAY)]
    if len(ok):
        return float(ok["c"].min()), True
    return float(table.loc[table["precision"].idxmax(), "c"]), False
