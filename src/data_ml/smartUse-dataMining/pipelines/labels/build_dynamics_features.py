"""Dynamics features (E8d) for the rows of data/processed/{dataset}_features.csv.

No profile: only the session log up to each decision time. Rows come out in
the same order as the features table, keyed by user_id and t_utc.

Output: data/processed/{dataset}_features_dynamics.csv

    python -m pipelines.labels.build_dynamics_features real
"""

import argparse

import pandas as pd

from pipelines.labels.build_windows import seconds
from smartuse.features_dynamics import extract_dynamics


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("dataset", choices=["real", "mpud"])
    parser.add_argument("--features", help="features table whose rows to use")
    parser.add_argument("--out")
    args = parser.parse_args()

    dates = ["start_utc", "start_local", "end_utc"]
    sessions = pd.read_csv(f"data/processed/{args.dataset}_preprocess_3.csv", parse_dates=dates)
    table = pd.read_csv(args.features or f"data/processed/{args.dataset}_features.csv", usecols=["user_id", "t_utc"])

    rows = []
    for user_id, ex in table.groupby("user_id", sort=False):
        s = sessions[sessions["user_id"] == user_id]
        starts, ends = seconds(s["start_utc"]), seconds(s["end_utc"])
        for t, t_utc in zip(seconds(pd.to_datetime(ex["t_utc"])), ex["t_utc"]):
            rows.append({"user_id": user_id, "t_utc": t_utc, **extract_dynamics(starts, ends, t)})

    out = pd.DataFrame(rows)
    path = args.out or f"data/processed/{args.dataset}_features_dynamics.csv"
    out.to_csv(path, index=False)
    print(f"{len(out)} rows, {out.shape[1] - 2} dynamics features -> {path}")


if __name__ == "__main__":
    main()
