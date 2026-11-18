import numpy as np
import pytest

from smartuse.features_extra import build_extra_profile, extract_extra

H, DAY = 3600.0, 86400.0
OFFSET = -6 * H
T0 = 20 * DAY - OFFSET  # local midnight


def log():
    starts = T0 + np.array([1, 1.5, 1.9, 2.0, 2.5, 3.0, 9.0]) * H
    ends = starts + np.array([30, 300, 30, 900, 45, 1800, 600.0])
    apps = np.array([1, 3, 1, 4, 2, 6, 2.0])
    return starts, ends, apps


def profile():
    s, e, _ = log()
    t = T0 + np.array([1, 1.2, 2.0, 2.1, 9.0]) * H
    return build_extra_profile(s, e, t, np.array([0, 3, 0, 1, 5.0]), np.array([7, 2, 2, 6, 1.0]),
                               pf=2.0, ps=5.0, day_bounds=[(T0, T0 + DAY)], offset_s=OFFSET)


def test_vector_uses_only_data_available_at_t():
    s, e, a = log()
    t = s[5] + 600
    full = extract_extra(s, e, a, t, OFFSET, profile(), pf=2.0, ps=5.0)
    trunc = extract_extra(s[:6], np.append(e[:5], t), np.append(a[:5], 0), t, OFFSET, profile(), pf=2.0, ps=5.0)
    assert full.keys() == trunc.keys()
    for k in full:
        assert full[k] == pytest.approx(trunc[k]), k


def test_hourly_rates_and_fallback():
    xp = profile()
    # hour 1: points f=[0, 3] -> F rate 1/2, s=[7, 2] -> S rate 1/2; hour 5 has no points -> overall rates
    assert xp.rate_f_hour[1] == pytest.approx(0.5) and xp.rate_s_hour[1] == pytest.approx(0.5)
    assert xp.rate_f_hour[5] == pytest.approx(2 / 5) and xp.rate_s_hour[5] == pytest.approx(2 / 5)


def test_in_session_survival_from_profile_durations():
    s, e, a = log()
    xp = profile()
    # 2 min into the 30 min session: profile durations > 120 s are 300, 900, 1800, 600;
    # of those, longer than 120 + 600 s: 900 and 1800 -> 2 / 4
    v = extract_extra(s, e, a, s[5] + 120, OFFSET, xp, pf=2.0, ps=5.0)
    assert v["cur_survival_10"] == pytest.approx(0.5)
    assert v["cur_dur_pct"] == pytest.approx(3 / 7)  # 30, 30, 45 of 7 are <= 120 s
