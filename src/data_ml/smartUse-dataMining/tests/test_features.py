import math

import numpy as np
import pytest

from smartuse.features import Profile, burstiness, build_profile, extract

H = 3600.0
DAY = 86400.0
OFFSET = -6 * H  # local = UTC - 6 h
T0 = 20 * DAY - OFFSET  # local midnight of epoch day 20 (a Wednesday)


def log():
    starts = T0 + np.array([1, 1.5, 1.9, 2.0, 2.5, 3.0, 9.0]) * H
    ends = starts + np.array([60, 300, 30, 900, 120, 1800, 600.0])
    apps = np.array([1, 3, 1, 4, 2, 6, 2])
    return starts, ends, apps


def profile():
    s, e, a = log()
    rng = np.random.default_rng(0)
    return build_profile(s, e, a, rng.uniform(0, 5, 200), rng.uniform(3, 8, 200),
                         [(T0, T0 + DAY)], OFFSET)


def test_vector_uses_only_data_available_at_t():
    s, e, a = log()
    t = s[5] + 600  # inside the 30 min session
    full = extract(s, e, a, t, OFFSET, False, profile())
    # what the device has at t: no future sessions, current still open (end = t),
    # and the current session's final app count is unknown
    k = 6
    trunc = extract(s[:k], np.append(e[:k - 1], t), np.append(a[:k - 1], 0), t, OFFSET, False, profile())
    assert full.keys() == trunc.keys()
    for name in full:
        assert full[name] == pytest.approx(trunc[name]), name


def test_low_data_uses_neutral_value_and_flag():
    s, e, a = log()
    v = extract(s, e, a, s[6] + 60, OFFSET, False, profile())  # 09:00 session alone in the last 15 min
    assert v["n_15"] == 1
    assert v["logdur_median_15"] == 0.0 and v["few2_15"] == 1.0
    assert v["burst_15"] == 0.0 and v["few3_15"] == 1.0


def test_burstiness_regular_intervals_is_minus_one():
    assert burstiness(np.array([10.0, 10.0, 10.0, 10.0])) == pytest.approx(-1.0)


def test_time_features():
    s, e, a = log()
    v = extract(s, e, a, T0 + 12 * H, OFFSET, False, profile())  # local noon
    assert v["tod_sin"] == pytest.approx(0.0, abs=1e-9) and v["tod_cos"] == pytest.approx(-1.0)
    assert v["weekend"] == 0.0


def test_profile_thresholds_and_hourly_counts():
    p = profile()
    assert isinstance(p, Profile)
    # starts at 1.0, 1.5, 1.9 | 2.0, 2.5 | 3.0 | 9.0 h local
    assert p.sessions_by_hour[[1, 2, 3, 9]].tolist() == [3, 2, 1, 1]
    assert 0 < p.pf < 5 and 3 < p.ps < 8
    assert math.isclose(p.freq_day, 7)
