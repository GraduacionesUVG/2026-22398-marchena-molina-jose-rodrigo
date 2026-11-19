import numpy as np
import pandas as pd

from experiments.lstm import N_MAX, SessionLog


def sessions(n=5, end_last=None):
    start = pd.date_range("2026-10-07 10:00", periods=n, freq="30min")
    end = start + pd.Timedelta(minutes=5)
    if end_last is not None:
        end = end[:-1].append(pd.DatetimeIndex([end_last]))
    return pd.DataFrame({"user_id": "u", "start_utc": start, "start_local": start - pd.Timedelta(hours=6),
                         "end_utc": end, "app_count_max": np.arange(1, n + 1)})


def test_sequence_uses_only_data_available_at_t():
    t = pd.Timestamp("2026-10-07 12:02").timestamp()  # 2 min into the 5th session
    full = SessionLog(sessions()).sequence("u", t)
    # device view: the open session ends at t and its app count is unknown
    device = SessionLog(sessions(end_last=pd.Timestamp("2026-10-07 12:02"))).sequence("u", t)
    np.testing.assert_allclose(full, device)
    assert full.shape == (5, 6)
    assert full[-1, 2] == 0 and full[-1, 3] == 1  # open session: apps 0, flag 1


def test_sequence_covers_only_last_24h_and_n_max():
    log = SessionLog(sessions(n=60))
    t = pd.Timestamp("2026-10-08 12:00").timestamp()
    assert len(log.sequence("u", t)) == 48 + 1  # starts in [t - 24 h, t]
    assert len(SessionLog(sessions(n=N_MAX + 50)).sequence("u", pd.Timestamp("2026-10-20").timestamp())) <= N_MAX
