"""Real data phase 2: epochs.

Input: data/processed/real_preprocess_1.csv.

Consecutive markers of the same kind of one person are merged into one
epoch: UsageStatsManager can emit SCREEN_INTERACTIVE or
SCREEN_NON_INTERACTIVE several times in a row (screen rotation, Doze,
proximity sensor, or the same event logged twice). Same rule as MPUD.
  - start_utc / start_local / client_record_id: those of the first marker
  - distinct_app_count: the max of the merged markers (the true distinct
    count of the union is unknown; the max never overcounts)
  - merged_markers: how many markers the epoch absorbed (1 = not merged)

Each epoch ends where the person's next epoch starts, so duration_s is the
time to it. Each person's last epoch has no known end and is dropped.

Output: data/processed/real_preprocess_2.csv
(client_record_id, user_id, start_utc, start_local, is_use,
distinct_app_count, merged_markers, duration_s).

    python pipelines/real/real_preprocess_2.py [in_csv] [out_csv]
"""

import sys
from pathlib import Path

import pandas as pd

IN_CSV = Path("data/processed/real_preprocess_1.csv")
OUT_CSV = Path("data/processed/real_preprocess_2.csv")

OUT_COLUMNS = ["client_record_id", "user_id", "start_utc", "start_local", "is_use",
               "distinct_app_count", "merged_markers", "duration_s"]


def main(in_csv: Path = IN_CSV, out_csv: Path = OUT_CSV) -> None:
    markers = pd.read_csv(in_csv, dtype={"start_utc": str, "start_local": str})
    new_epoch = (markers["is_use"] != markers.groupby("user_id")["is_use"].shift()) | (
        markers["user_id"] != markers["user_id"].shift()
    )
    epochs = markers.groupby(new_epoch.cumsum()).agg(
        client_record_id=("client_record_id", "first"),
        user_id=("user_id", "first"),
        start_utc=("start_utc", "first"),
        start_local=("start_local", "first"),
        is_use=("is_use", "first"),
        distinct_app_count=("distinct_app_count", "max"),
        merged_markers=("is_use", "size"),
    ).reset_index(drop=True)

    start = pd.to_datetime(epochs["start_utc"])
    epochs["duration_s"] = (start.groupby(epochs["user_id"]).shift(-1) - start).dt.total_seconds().round(3)
    last = epochs["duration_s"].isna()
    epochs = epochs[~last]

    out_csv.parent.mkdir(parents=True, exist_ok=True)
    epochs[OUT_COLUMNS].to_csv(out_csv, index=False)

    merged = epochs[epochs["merged_markers"] > 1]
    print(f"markers in: {len(markers)}; merged epochs: {len(merged)} ({merged['is_use'].sum()} use, "
          f"{(~merged['is_use']).sum()} non-use) in {merged['user_id'].nunique()} users; "
          f"last epoch of each person dropped: {last.sum()}")
    print(f"{len(epochs)} epochs ({epochs['is_use'].sum()} use) "
          f"from {epochs['user_id'].nunique()} users -> {out_csv}")


if __name__ == "__main__":
    main(*[Path(a) for a in sys.argv[1:3]])
