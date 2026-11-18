"""Feature and label table for the model (README §5–§6).

Inputs: the session log (phase 3) and the decision-point windows built with
coverage (phase 4, --mode points --epochs). Per person:

  1. Usable points: not masked, not censored, on a day with enough data.
     Usable days are the local days that have usable points.
  2. People with fewer than D + MIN_EVAL_DAYS usable days are left out
     (cleaning step 9).
  3. The profile is frozen on the first D usable days: p-quantiles of F and
     S over those days' points (profile B, p = 0.80, E3) plus Lin parameters
     and hourly counts from the session log. Own data only (E1b).
  4. Examples: usable points on later days. y_f = F > pf, y_s = S > ps,
     y = y_f or y_s. Features come from smartuse.features.extract with the
     session log up to t.

Output: data/processed/{dataset}_features.csv

    python -m pipelines.labels.build_features real [--d 3] [--out CSV] [--points CSV] [--profile-all]
"""

import argparse
from pathlib import Path

import pandas as pd

from pipelines.labels.build_windows import EPOCH, seconds
from smartuse.features import DAY, build_profile, extract
from smartuse.profile import usable

P = 0.80
MIN_EVAL_DAYS = 2


def person_rows(user_id: str, sessions: pd.DataFrame, points: pd.DataFrame, d: int,
                profile_all: bool = False) -> list[dict]:
    starts, ends = seconds(sessions["start_utc"]), seconds(sessions["end_utc"])
    apps = sessions["app_count_max"].to_numpy(dtype=float)
    offset_s = float((sessions["start_local"].iloc[0] - sessions["start_utc"].iloc[0]).total_seconds())
    days = sorted(points["day"].unique())
    prof_days = days if profile_all else days[:d]  # all days: E1c reference, not deployable
    prof_pts = points[points["day"].isin(prof_days)]
    # local midnight of each profile day, in UTC seconds
    midnights = [(pd.Timestamp(day) - EPOCH).total_seconds() - offset_s for day in prof_days]
    bounds = [(a, a + DAY) for a in midnights]
    profile = build_profile(starts, ends, apps, prof_pts["f"].to_numpy(), prof_pts["s"].to_numpy(),
                            bounds, offset_s, P)

    examples = points[points["day"] > days[d - 1]]
    rows = []
    for t, row in zip(seconds(examples["t_utc"]), examples.itertuples(index=False)):
        y_f, y_s = row.f > profile.pf, row.s > profile.ps
        rows.append({"user_id": user_id, "t_utc": row.t_utc, "t_local": row.t_local, "day": row.day,
                     "is_unlock": row.is_unlock, "y": y_f or y_s, "y_f": y_f, "y_s": y_s,
                     **extract(starts, ends, apps, t, offset_s, not row.is_unlock, profile)})
    return rows


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("dataset", choices=["real", "mpud"])
    parser.add_argument("--d", type=int, default=3)
    parser.add_argument("--out", type=Path)
    parser.add_argument("--points", type=Path)
    parser.add_argument("--profile-all", action="store_true",
                        help="profile from all usable days (E1c reference); examples stay after day d")
    args = parser.parse_args()

    dates = ["start_utc", "start_local", "end_utc"]
    sessions = pd.read_csv(f"data/processed/{args.dataset}_preprocess_3.csv", parse_dates=dates)
    points_csv = args.points or Path(f"data/processed/{args.dataset}_windows_points.csv")
    points = pd.read_csv(points_csv, parse_dates=["t_utc", "t_local"])
    points = points[usable(points)].copy()
    points["day"] = points["t_local"].dt.date

    rows, left_out = [], []
    for user_id, pts in points.groupby("user_id", sort=False):
        if pts["day"].nunique() < args.d + MIN_EVAL_DAYS:
            left_out.append(user_id)
            continue
        rows.extend(person_rows(user_id, sessions[sessions["user_id"] == user_id], pts, args.d, args.profile_all))

    table = pd.DataFrame(rows)
    out = args.out or Path(f"data/processed/{args.dataset}_features.csv")
    table.to_csv(out, index=False)
    n_features = table.shape[1] - 8
    print(f"{len(table)} examples, {n_features} features, {table['user_id'].nunique()} users "
          f"({len(left_out)} left out with < d + {MIN_EVAL_DAYS} usable days); "
          f"y = {table['y'].mean():.1%} (F {table['y_f'].mean():.1%}, S {table['y_s'].mean():.1%}) -> {out}")


if __name__ == "__main__":
    main()
