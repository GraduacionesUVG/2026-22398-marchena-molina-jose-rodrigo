import numpy as np
import pandas as pd

from experiments.common import label_p, paired_grid, pseudonym
from experiments.cutoff_f1 import best_f1_exhaustive


def log(starts, ends, no_close=None):
    start = pd.to_datetime(starts)
    return pd.DataFrame({
        "user_id": "u",
        "start_utc": start,
        "start_local": start - pd.Timedelta(hours=6),
        "end_utc": pd.to_datetime(ends),
        "no_close": no_close or [False] * len(starts),
    })


def test_paired_grid_shares_windows_and_drops_masked_in_any():
    a = log(["2026-10-07 06:10", "2026-10-07 07:10", "2026-10-07 08:10"],
            ["2026-10-07 06:20", "2026-10-07 07:20", "2026-10-07 09:59"])
    b = a.assign(no_close=[False, True, False])
    out = paired_grid({"a": a, "b": b}, 3600.0)
    # local-hour grid 06:00, 07:00, 08:00, 09:00 UTC; 06:00 starts before data (censored),
    # 07:00 masked in b, 09:00 runs past the data end (censored) -> only 08:00 stays
    assert out["t"].tolist() == [pd.Timestamp("2026-10-07 08:00").timestamp()]
    assert out[["n_a", "n_b"]].iloc[0].tolist() == [1, 1]


def test_label_is_f_or_s_above_person_percentile():
    df = pd.DataFrame({"user_id": ["u"] * 5, "f_x": [0, 0, 0, 0, 9.0], "s_x": [1, 2, 3, 4, 0.0]})
    assert label_p(df, "x", 0.8).tolist() == [False, False, False, True, True]


def test_pseudonym_is_stable_and_hides_the_id():
    uid = "123e4567-e89b-12d3-a456-426614174000"
    alias = pseudonym(uid)
    assert alias == pseudonym(uid) and alias != pseudonym(uid + "x")
    assert alias.startswith("p") and len(alias) == 9 and uid[:8] not in alias


def test_best_f1_exhaustive_matches_brute_force():
    rng = np.random.default_rng(0)
    raw = np.round(rng.random(300), 2)  # ties on purpose
    y = rng.random(300) < raw

    def f1(c):
        pred = raw > c
        tp = (pred & y).sum()
        return 2 * tp / (pred.sum() + y.sum())

    c = best_f1_exhaustive(raw, y)
    best = max(f1(v) for v in np.unique(np.r_[raw, raw.min() - 1]))
    assert np.isclose(f1(c), best)
