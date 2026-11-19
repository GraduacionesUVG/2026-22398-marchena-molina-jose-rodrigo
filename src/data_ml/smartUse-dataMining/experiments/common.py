"""Shared helpers for experiments that compare session logs on the same windows."""

import hashlib

import pandas as pd

from pipelines.labels.build_windows import seconds
from smartuse.windows import grid_starts, person_windows


def pseudonym(user_id: str) -> str:
    """Stable public alias of a participant id: "p" + first 8 hex of its SHA-256.

    Used wherever an id leaves this machine (published reports, notebooks,
    production.json); the raw ids stay in data/.
    """
    return "p" + hashlib.sha256(str(user_id).encode()).hexdigest()[:8]


def paired_grid(logs: dict[str, pd.DataFrame], w: float) -> pd.DataFrame:
    """The same local-hour grid windows per person over several session logs.

    Windows masked or censored in any log are dropped. Columns: user_id, t
    (window start, epoch seconds) and f_<name>, s_<name>, n_<name> per log.
    """
    first = next(iter(logs.values()))
    parts = []
    for user_id, ref in first.groupby("user_id", sort=False):
        subsets = {name: df[df["user_id"] == user_id] for name, df in logs.items()}
        if any(s.empty for s in subsets.values()):
            continue
        arrays = {name: (seconds(s["start_utc"]), seconds(s["end_utc"]), s["no_close"].to_numpy())
                  for name, s in subsets.items()}
        start = min(float(a[0].min()) for a in arrays.values())
        end = max(float(a[1].max()) for a in arrays.values())
        offset_s = float((ref["start_local"].iloc[0] - ref["start_utc"].iloc[0]).total_seconds())
        times = grid_starts(start, end, w, offset_s)
        out = pd.DataFrame({"user_id": user_id, "t": times})
        drop = pd.Series(False, index=out.index)
        for name, (s, e, nc) in arrays.items():
            win = person_windows(s, e, nc, start, end, times, w)
            drop |= win["masked"] | win["censored"]
            out[f"f_{name}"], out[f"s_{name}"], out[f"n_{name}"] = win["f"], win["s"], win["n_sessions"]
        parts.append(out[~drop])
    return pd.concat(parts, ignore_index=True)


def above_p(x: pd.Series, user: pd.Series, p: float) -> pd.Series:
    """x above the person's own p-quantile."""
    return x > x.groupby(user).transform(lambda v: v.quantile(p))


def label_p(df: pd.DataFrame, name: str, p: float) -> pd.Series:
    """Provisional label: F or S above the person's p-quantile."""
    return above_p(df[f"f_{name}"], df["user_id"], p) | above_p(df[f"s_{name}"], df["user_id"], p)


def load_profile_inputs(name: str = "real") -> tuple[pd.DataFrame, pd.DataFrame]:
    """Usable grid windows and decision points of a dataset, with a local `day`.

    Built by pipelines/labels/build_windows.py with --epochs (coverage).
    """
    from smartuse.profile import usable

    out = []
    for mode in ("grid", "points"):
        w = pd.read_csv(f"data/processed/{name}_windows_{mode}.csv", parse_dates=["t_local"])
        w = w[usable(w)].copy()
        w["day"] = w["t_local"].dt.date
        out.append(w)
    return out[0], out[1]


def ok_days(grid: pd.DataFrame) -> dict:
    """Per person: sorted local days with usable windows."""
    return {u: sorted(g["day"].unique()) for u, g in grid.groupby("user_id")}
