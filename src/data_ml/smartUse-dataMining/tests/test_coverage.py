import numpy as np
import pandas as pd

from smartuse.coverage import day_coverage, missing_intervals
from smartuse.windows import person_windows

H = 3600.0


def epochs(rows):
    """rows: (start_utc, is_use, duration_s); local time is UTC - 6 h."""
    start = pd.to_datetime([r[0] for r in rows])
    return pd.DataFrame({"user_id": "u", "start_utc": start, "start_local": start - pd.Timedelta(hours=6),
                         "is_use": [r[1] for r in rows], "duration_s": [r[2] for r in rows]})


def test_missing_intervals_are_long_non_use_epochs():
    e = epochs([("2026-10-07 10:00", True, 60), ("2026-10-07 10:01", False, 11 * H),
                ("2026-10-07 21:01", True, 60), ("2026-10-07 21:02", False, 9 * H)])
    gaps = missing_intervals(e, 10 * H)
    assert len(gaps) == 1
    assert gaps.iloc[0]["start_utc"] == pd.Timestamp("2026-10-07 10:01")
    assert gaps.iloc[0]["end_utc"] == pd.Timestamp("2026-10-07 21:01")


def test_day_coverage_counts_local_hours_with_an_event():
    # local 04:00, 04:30, 05:10 and 13:00 on 10-07 -> hours 4, 5, 13 -> 3 active hours
    e = epochs([("2026-10-07 10:00", True, 60), ("2026-10-07 10:30", False, 60),
                ("2026-10-07 11:10", True, 60), ("2026-10-07 19:00", False, 60)])
    days = day_coverage(e, min_hours=3)
    assert days[["active_hours", "day_ok"]].iloc[0].tolist() == [3, True]
    assert day_coverage(e, min_hours=4)["day_ok"].iloc[0] == np.False_


def test_window_overlapping_missing_data_is_censored():
    out = person_windows(np.array([0.0]), np.array([60.0]), np.array([False]), 0.0, 20 * H,
                         np.array([0.0, 5 * H]), H,
                         missing_starts=np.array([2 * H]), missing_ends=np.array([4 * H]))
    assert out["censored"].tolist() == [False, False]
    out = person_windows(np.array([0.0]), np.array([60.0]), np.array([False]), 0.0, 20 * H,
                         np.array([1.5 * H, 3.5 * H, 4 * H]), H,
                         missing_starts=np.array([2 * H]), missing_ends=np.array([4 * H]))
    assert out["censored"].tolist() == [True, True, False]
