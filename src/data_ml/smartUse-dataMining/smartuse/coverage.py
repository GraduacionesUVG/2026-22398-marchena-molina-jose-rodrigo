"""Coverage of one or more people's epoch logs (cleaning steps 6 and 9).

Input epochs have user_id, start_utc, start_local, is_use, duration_s (the
phase 2 schema of both datasets).

  - missing data (step 6): a non-use epoch longer than the threshold is
    taken as missing data (phone off, app dead), not non-use
  - day coverage (step 9): a local day is usable if it has at least
    min_hours distinct clock hours with an epoch start
"""

import pandas as pd


def missing_intervals(epochs: pd.DataFrame, min_gap_s: float) -> pd.DataFrame:
    """Non-use epochs longer than min_gap_s: user_id, start_utc, end_utc."""
    long = epochs[~epochs["is_use"] & (epochs["duration_s"] > min_gap_s)]
    return pd.DataFrame({
        "user_id": long["user_id"],
        "start_utc": long["start_utc"],
        "end_utc": long["start_utc"] + pd.to_timedelta(long["duration_s"], unit="s"),
    }).reset_index(drop=True)


def day_coverage(epochs: pd.DataFrame, min_hours: int) -> pd.DataFrame:
    """Per person and local day: active_hours and day_ok."""
    local = epochs["start_local"]
    days = (epochs.assign(day=local.dt.date, hour=local.dt.hour)
            .groupby(["user_id", "day"])["hour"].nunique().rename("active_hours").reset_index())
    days["day_ok"] = days["active_hours"] >= min_hours
    return days
