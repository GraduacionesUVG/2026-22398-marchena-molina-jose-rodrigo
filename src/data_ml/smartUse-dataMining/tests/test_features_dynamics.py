import numpy as np
import pytest

from smartuse.features_dynamics import GROUPS, extract_dynamics, shape_stats

H = 3600.0


def log():
    starts = 1_000_000.0 + np.array([0.1, 0.5, 2.0, 20.0, 22.5, 23.2, 23.6, 23.8, 23.9]) * H
    ends = starts + np.array([60, 300, 30, 900, 45, 600, 120, 200, 900.0])
    return starts, ends


def test_vector_uses_only_data_available_at_t():
    s, e = log()
    t = s[8] + 300  # inside the last session
    full = extract_dynamics(s, e, t)
    trunc = extract_dynamics(s, np.append(e[:8], t), t)
    for k in full:
        assert full[k] == pytest.approx(trunc[k]), k
    assert len(full) == sum(len(v) for v in GROUPS.values()) == 42


def test_shape_stats_on_a_ramp():
    st = shape_stats(np.array([0.0, 1.0, 2.0]))
    assert st["slope"] == pytest.approx(1.0)
    assert st["accel"] == pytest.approx(0.0)
    assert st["rel_change"] == pytest.approx((2 - 0.5) / 1.5)
    assert st["com"] == pytest.approx(5 / 6)       # (0*0 + 1*1 + 2*2) / 3 / 2
    assert st["peak"] == pytest.approx(2.0)
    assert st["persist"] == 2.0                    # two rises in a row


def test_shape_stats_falling_and_empty():
    assert shape_stats(np.array([3.0, 2.0, 2.0, 1.0]))["persist"] == -1.0
    empty = shape_stats(np.zeros(4))
    assert empty["com"] == 0.5 and empty["peak"] == 0.0 and empty["persist"] == 0.0
