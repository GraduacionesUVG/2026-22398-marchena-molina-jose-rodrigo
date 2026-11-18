import pandas as pd

from pipelines.labels.build_windows import build


def sessions_frame():
    start = pd.to_datetime(["2026-10-07 21:08:24.645", "2026-10-07 22:00:00.001"])
    end = pd.to_datetime(["2026-10-07 21:10:00.123", "2026-10-07 22:01:00.000"])
    return pd.DataFrame({
        "user_id": ["u", "u"],
        "start_utc": start,
        "start_local": start - pd.Timedelta(hours=6),
        "end_utc": end,
        "no_close": [False, False],
    })


def test_unlock_points_keep_exact_session_start():
    out = build(sessions_frame(), "points", 3600.0, 600.0, 3 * 3600.0)
    unlock = out[out["is_unlock"]]
    assert unlock["t_utc"].tolist() == ["2026-10-07 21:08:24.645", "2026-10-07 22:00:00.001"]
    assert unlock["t_local"].tolist() == ["2026-10-07 15:08:24.645", "2026-10-07 16:00:00.001"]


def test_coverage_censors_missing_data_and_marks_days():
    sessions = sessions_frame()
    start = pd.to_datetime(["2026-10-07 21:08:24.645", "2026-10-07 21:10:00.123", "2026-10-07 22:00:00.001"])
    epochs = pd.DataFrame({"user_id": "u", "start_utc": start, "start_local": start - pd.Timedelta(hours=6),
                           "is_use": [True, False, True], "duration_s": [95.478, 2999.878, 59.999]})
    out = build(sessions, "points", 3600.0, 600.0, 3 * 3600.0, epochs=epochs, missing_h=0.5, min_day_hours=2)
    # the 50 min non-use epoch is "missing" at 0.5 h -> the first point's window overlaps it
    assert out["censored"].tolist() == [True, True]
    assert out["day_ok"].tolist() == [True, True]
    out = build(sessions, "points", 3600.0, 600.0, 3 * 3600.0, epochs=epochs, missing_h=1.0, min_day_hours=3)
    assert out["day_ok"].tolist() == [False, False]


def test_offset_shifts_the_label_window_but_keeps_the_decision_time():
    sessions = sessions_frame()
    base = build(sessions, "points", 3600.0, 600.0, 3 * 3600.0)
    shifted = build(sessions, "points", 3600.0, 600.0, 3 * 3600.0, label_offset_s=1800.0)
    assert shifted["t_utc"].tolist() == base["t_utc"].tolist()
    # 21:08:24 looks at [20:38, 21:38): starts before the data -> censored, sees only its own session.
    # 22:00 looks at [21:30, 22:30): the 21:08 session ended at 21:10, so only the 22:00 one is in it.
    assert shifted["censored"].tolist() == [True, True]
    assert shifted["n_sessions"].tolist() == [1, 1]
