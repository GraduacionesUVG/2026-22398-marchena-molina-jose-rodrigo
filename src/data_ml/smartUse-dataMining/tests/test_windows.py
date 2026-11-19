import math

import numpy as np
import pytest

from smartuse.windows import (decision_points, grid_starts, person_windows, window_indices,
                              window_metrics)

W = 3600.0


def arr(*xs):
    return np.array(xs, dtype=float)


def test_empty_window():
    m = window_metrics(arr(0), arr(10), 100.0, W)
    assert (m.n_sessions, m.f, m.s) == (0, 0.0, 0.0)


def test_single_session_has_no_fragmentation():
    m = window_metrics(arr(100), arr(400), 0.0, W)
    assert m.n_sessions == 1
    assert m.f == 0.0
    assert m.s == pytest.approx(math.log(300))


def test_fragmentation_is_count_times_span_over_w():
    # sessions [0,60), [1000,1060), [1800,1900): span = 1900 - 0
    m = window_metrics(arr(0, 1000, 1800), arr(60, 1060, 1900), 0.0, W)
    assert m.n_sessions == 3
    assert m.span_s == pytest.approx(1900)
    assert m.f == pytest.approx(3 * 1900 / W)
    assert m.longest_s == pytest.approx(100)


def test_session_crossing_both_edges_is_clipped_to_w():
    m = window_metrics(arr(-500), arr(W + 500), 0.0, W)
    assert m.longest_s == pytest.approx(W)
    assert m.s == pytest.approx(math.log(W))


def test_half_open_edges():
    # ends exactly at a, starts exactly at a + w: neither overlaps
    assert window_indices(arr(-10, W), arr(0, W + 10), 0.0, W) == (1, 1)


def test_sub_second_session_gives_non_negative_s():
    m = window_metrics(arr(5), arr(5.4), 0.0, W)
    assert m.s == 0.0



def test_decision_points_unlock_then_every_k():
    # one session [0, 1500), k = 600: points at 0, 600, 1200
    times, sid, unlock = decision_points(arr(0), arr(1500), 600.0, 3 * 3600.0)
    assert times.tolist() == [0, 600, 1200]
    assert sid.tolist() == [0, 0, 0]
    assert unlock.tolist() == [True, False, False]


def test_short_session_gets_only_the_unlock_point():
    times, sid, unlock = decision_points(arr(0, 5000), arr(30, 5010), 600.0, 3 * 3600.0)
    assert times.tolist() == [0, 5000]
    assert unlock.tolist() == [True, True]


def test_no_inference_past_t_max():
    # 5 h session, t_max = 3 h, k = 1 h: points at 0, 1, 2 h only
    times, _, _ = decision_points(arr(0), arr(5 * 3600), 3600.0, 3 * 3600.0)
    assert times.tolist() == [0, 3600, 7200]


def test_grid_aligned_to_local_hour():
    # UTC-6: local midnight is 06:00 UTC = 21600 s
    starts = grid_starts(21600 + 100.0, 21600 + 7300.0, 3600.0, -6 * 3600.0)
    assert starts.tolist() == [21600, 25200, 28800]



def test_censored_past_data_end():
    out = person_windows(arr(0), arr(60), np.array([False]), 0.0, 1000.0, arr(0), W)
    assert out["censored"].tolist() == [True]


def test_masked_when_window_overlaps_no_close_session():
    starts, ends = arr(0, 4000, 20000), arr(60, 4000 + 4 * 3600, 20060)
    no_close = np.array([False, True, False])
    out = person_windows(starts, ends, no_close, 0.0, 40000.0, arr(0, 1000, 20000), W)
    assert out["masked"].tolist() == [False, True, False]


def test_censored_before_data_start():
    # grid window [0, 3600) starts before the first session at 1000
    out = person_windows(arr(1000, 5000), arr(1060, 5060), np.array([False, False]), 1000.0, 9000.0,
                         arr(0, 3600), W)
    assert out["censored"].tolist() == [True, False]
