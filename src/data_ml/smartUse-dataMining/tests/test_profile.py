import pandas as pd
import pytest

from smartuse.profile import blend, label, percentiles, usable


def test_usable_drops_masked_censored_and_bad_days():
    w = pd.DataFrame({"masked": [False, True, False, False], "censored": [False, False, True, False],
                      "day_ok": [True, True, True, False]})
    assert usable(w).tolist() == [True, False, False, False]


def test_percentiles_per_person_include_empty_windows():
    w = pd.DataFrame({"user_id": ["a"] * 5 + ["b"] * 5,
                      "f": [0, 0, 0, 1, 2, 5, 5, 5, 5, 5.0], "s": [0, 1, 2, 3, 4, 0, 0, 0, 0, 0.0]})
    prof = percentiles(w, 0.5)
    assert prof.loc["a"].tolist() == [0.0, 2.0]
    assert prof.loc["b"].tolist() == [5.0, 0.0]


def test_label_is_f_or_s_strictly_above_profile():
    prof = pd.DataFrame({"pf": [1.0], "ps": [5.0]}, index=pd.Index(["a"], name="user_id"))
    pts = pd.DataFrame({"user_id": ["a"] * 4, "f": [1.0, 2.0, 0.0, 0.0], "s": [5.0, 0.0, 6.0, 0.0]})
    assert label(pts, prof).tolist() == [False, True, True, False]


def test_blend_weights_own_by_n_over_n_plus_n0():
    assert blend(own=10.0, prior=0.0, n=30, n0=10) == pytest.approx(7.5)
