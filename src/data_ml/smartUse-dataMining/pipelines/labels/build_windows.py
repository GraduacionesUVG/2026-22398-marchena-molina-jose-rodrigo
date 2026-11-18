"""Window metrics table: F and S of [t, t + W) per person.

Input: a session log from phase 3 (data/processed/*_preprocess_3.csv).

Modes:
  points  decision points: each session start and every k min inside the
          session, up to T_max (README §5.2)
  grid    contiguous W windows aligned to the local clock, from the
          person's first session to their last session end

A person's data runs from their first session start to their last session
end; windows that start before it or run past it are marked censored.

With --epochs (the phase 2 epochs of the same dataset), coverage is applied
(smartuse/coverage.py):
  - a non-use epoch longer than --missing-h hours is missing data (step 6);
    windows overlapping it are censored
  - day_ok: the window starts on a local day with at least --min-day-hours
    active hours (step 9)

    python -m pipelines.labels.build_windows SESSIONS OUT --mode points
        [--epochs EPOCHS] [--w-min 60] [--k-min 10] [--t-max-h 3]
        [--missing-h 10] [--min-day-hours 6] [--offset-min 0]

--offset-min b (E4b) slides the label window back: at decision time t it
covers [t - b, t + W - b). t_utc stays the decision time.
"""

import argparse
from pathlib import Path

import numpy as np
import pandas as pd

from smartuse.coverage import day_coverage, missing_intervals
from smartuse.windows import decision_points, grid_starts, person_windows

EPOCH = pd.Timestamp("1970-01-01")
TS_FORMAT = "%Y-%m-%d %H:%M:%S.%f"


def seconds(ts: pd.Series) -> np.ndarray:
    return (ts - EPOCH).dt.total_seconds().to_numpy()


def build(sessions: pd.DataFrame, mode: str, w: float, k: float, t_max: float,
          epochs: pd.DataFrame | None = None, missing_h: float = 10.0,
          min_day_hours: int = 6, label_offset_s: float = 0.0) -> pd.DataFrame:
    if epochs is not None:
        missing = missing_intervals(epochs, missing_h * 3600)
        ok_days = day_coverage(epochs, min_day_hours)
        ok_days = set(ok_days.loc[ok_days["day_ok"], ["user_id", "day"]].itertuples(index=False, name=None))
    parts = []
    for user_id, s in sessions.groupby("user_id", sort=False):
        starts, ends = seconds(s["start_utc"]), seconds(s["end_utc"])
        offset_s = float((s["start_local"].iloc[0] - s["start_utc"].iloc[0]).total_seconds())
        data_start, data_end = float(starts.min()), float(ends.max())
        if mode == "points":
            times, idx, unlock = decision_points(starts, ends, k, t_max)
        else:
            times = grid_starts(data_start, data_end, w, offset_s)
        gaps = {}
        if epochs is not None:
            m = missing[missing["user_id"] == user_id]
            gaps = {"missing_starts": seconds(m["start_utc"]), "missing_ends": seconds(m["end_utc"])}
        # the label window starts label_offset_s before the decision time (E4b); t stays the decision time
        out = person_windows(starts, ends, s["no_close"].to_numpy(), data_start, data_end,
                             times - label_offset_s, w, **gaps)
        out["t"] = times
        if mode == "points":
            out.insert(1, "session_row", s.index.to_numpy()[idx])
            out.insert(2, "is_unlock", unlock)
        # float seconds lose sub-ms precision; round so t matches the session start
        t = EPOCH + pd.to_timedelta(out.pop("t"), unit="s").dt.round("ms")
        out.insert(0, "t_local", (t + pd.Timedelta(seconds=offset_s)).dt.strftime(TS_FORMAT).str[:-3])
        out.insert(0, "t_utc", t.dt.strftime(TS_FORMAT).str[:-3])
        out.insert(0, "user_id", user_id)
        if epochs is not None:
            local_day = (t + pd.Timedelta(seconds=offset_s)).dt.date
            out["day_ok"] = [(user_id, d) in ok_days for d in local_day]
        parts.append(out)
    return pd.concat(parts, ignore_index=True)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("sessions", type=Path)
    parser.add_argument("out", type=Path)
    parser.add_argument("--mode", choices=["points", "grid"], default="points")
    parser.add_argument("--w-min", type=float, default=60.0)
    parser.add_argument("--k-min", type=float, default=10.0)
    parser.add_argument("--t-max-h", type=float, default=3.0)
    parser.add_argument("--epochs", type=Path)
    parser.add_argument("--missing-h", type=float, default=10.0)
    parser.add_argument("--min-day-hours", type=int, default=6)
    parser.add_argument("--offset-min", type=float, default=0.0)
    args = parser.parse_args()

    sessions = pd.read_csv(args.sessions, parse_dates=["start_utc", "start_local", "end_utc"])
    epochs = None
    if args.epochs:
        epochs = pd.read_csv(args.epochs, usecols=["user_id", "start_utc", "start_local", "is_use", "duration_s"],
                             parse_dates=["start_utc", "start_local"])
    table = build(sessions, args.mode, args.w_min * 60, args.k_min * 60, args.t_max_h * 3600,
                  epochs, args.missing_h, args.min_day_hours, args.offset_min * 60)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    table.to_csv(args.out, index=False)
    usable = ~table["masked"] & ~table["censored"]
    if "day_ok" in table:
        print(f"windows on days with enough data: {table['day_ok'].mean():.1%}")
        usable &= table["day_ok"]
    print(f"{len(table)} windows ({args.mode}) from {table['user_id'].nunique()} users; "
          f"masked {table['masked'].sum()}, censored {table['censored'].sum()}, usable {usable.sum()}")
    print(f"F > 0: {(table.loc[usable, 'f'] > 0).mean():.0%}; "
          f"median S of non-empty: {table.loc[usable & (table['n_sessions'] > 0), 's'].median():.2f} -> {args.out}")


if __name__ == "__main__":
    main()
