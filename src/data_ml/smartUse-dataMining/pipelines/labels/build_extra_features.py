"""Extra feature groups (E8c) for the rows of data/processed/{dataset}_features.csv.

Same people, profile days (first D usable days) and examples as
build_features.py; the profile thresholds pf and ps are read from that table.
Rows come out in the same order, keyed by user_id and t_utc.

Output: data/processed/{dataset}_features_extra.csv

    python -m pipelines.labels.build_extra_features real [--d 3]
"""

import argparse

import pandas as pd

from pipelines.labels.build_windows import EPOCH, seconds
from smartuse.features import DAY
from smartuse.features_extra import build_extra_profile, extract_extra
from smartuse.profile import usable


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("dataset", choices=["real", "mpud"])
    parser.add_argument("--d", type=int, default=3)
    args = parser.parse_args()

    dates = ["start_utc", "start_local", "end_utc"]
    sessions = pd.read_csv(f"data/processed/{args.dataset}_preprocess_3.csv", parse_dates=dates)
    points = pd.read_csv(f"data/processed/{args.dataset}_windows_points.csv", parse_dates=["t_utc", "t_local"])
    points = points[usable(points)].copy()
    points["day"] = points["t_local"].dt.date
    table = pd.read_csv(f"data/processed/{args.dataset}_features.csv",
                        usecols=["user_id", "t_utc", "prof_pf", "prof_ps"], parse_dates=["t_utc"])

    rows = []
    for user_id, ex in table.groupby("user_id", sort=False):
        s = sessions[sessions["user_id"] == user_id]
        starts, ends = seconds(s["start_utc"]), seconds(s["end_utc"])
        apps = s["app_count_max"].to_numpy(dtype=float)
        offset_s = float((s["start_local"].iloc[0] - s["start_utc"].iloc[0]).total_seconds())
        pts = points[points["user_id"] == user_id]
        prof_days = sorted(pts["day"].unique())[: args.d]
        prof = pts[pts["day"].isin(prof_days)]
        midnights = [(pd.Timestamp(d) - EPOCH).total_seconds() - offset_s for d in prof_days]
        pf, ps = float(ex["prof_pf"].iloc[0]), float(ex["prof_ps"].iloc[0])
        xp = build_extra_profile(starts, ends, seconds(prof["t_utc"]), prof["f"].to_numpy(), prof["s"].to_numpy(),
                                 pf, ps, [(a, a + DAY) for a in midnights], offset_s)
        for t, t_utc in zip(seconds(ex["t_utc"]), ex["t_utc"]):
            rows.append({"user_id": user_id, "t_utc": t_utc,
                         **extract_extra(starts, ends, apps, t, offset_s, xp, pf, ps)})

    out = pd.DataFrame(rows)
    out["t_utc"] = out["t_utc"].dt.strftime("%Y-%m-%d %H:%M:%S.%f").str[:-3]
    path = f"data/processed/{args.dataset}_features_extra.csv"
    out.to_csv(path, index=False)
    print(f"{len(out)} rows, {out.shape[1] - 2} extra features -> {path}")


if __name__ == "__main__":
    main()
