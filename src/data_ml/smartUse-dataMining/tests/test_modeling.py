import numpy as np
import pandas as pd
import pytest

from experiments.modeling import (BLOCKS, evaluate, feature_cols, loso_folds, persistence_score,
                                  temporal_split)


def frame():
    rng = np.random.default_rng(0)
    users = np.repeat([f"u{i}" for i in range(6)], 20)
    days = np.tile(np.repeat(pd.date_range("2026-10-01", periods=5).date, 4), 6)
    df = pd.DataFrame({"user_id": users, "t_utc": "x", "t_local": "x", "day": days, "is_unlock": True,
                       "y": rng.random(120) > 0.5, "y_f": False, "y_s": False,
                       "f_60": rng.random(120) * 4, "s_60": rng.random(120) * 8,
                       "prof_pf": 2.0, "prof_ps": 6.0})
    return df


def test_loso_folds_are_disjoint_and_cover_everyone():
    df = frame()
    seen = []
    for test, val, train in loso_folds(df["user_id"], n_val=2, seed=0):
        assert not (set(test) & set(val)) and not (set(test) & set(train)) and not (set(val) & set(train))
        assert len(test) == 1 and len(val) == 2 and len(train) == 3
        seen += test
    assert sorted(seen) == sorted(df["user_id"].unique())


def test_temporal_split_takes_first_days_of_each_person():
    df = frame()
    train, test = temporal_split(df, 0.6)
    assert train.groupby("user_id")["day"].max().max() < test.groupby("user_id")["day"].min().min()
    assert set(train["user_id"]) == set(test["user_id"])


def test_persistence_is_last_hour_label():
    df = pd.DataFrame({"f_60": [3.0, 1.0, 1.0], "s_60": [0.0, 7.0, 1.0], "prof_pf": 2.0, "prof_ps": 6.0})
    assert persistence_score(df).tolist() == [1.0, 1.0, 0.0]


def test_evaluate_per_person_auc_and_onset():
    df = pd.DataFrame({"user_id": ["a"] * 4 + ["b"] * 4, "y": [0, 0, 1, 1, 0, 1, 0, 1],
                       "persist": [0, 0, 0, 1, 0, 0, 1, 1.0]})
    score = np.array([0.1, 0.2, 0.8, 0.9, 0.9, 0.1, 0.5, 0.5])
    r = evaluate(df["y"], score, df["user_id"], df["persist"], n_boot=50)
    assert r["auc_per_person"]["a"] == pytest.approx(1.0)
    # b: positives score .1 and .5, negatives .9 and .5 -> only the .5/.5 tie counts (half of 1 of 4 pairs)
    assert r["auc_per_person"]["b"] == pytest.approx(0.125)
    assert r["auc_median"] == pytest.approx(0.5625)
    assert "auc_onset_median" in r and "auc_median_ci95" in r


def test_feature_blocks_partition_the_features():
    cols = feature_cols(pd.read_csv("data/processed/real_features.csv", nrows=1))
    assert len(cols) == 52
    assigned = [c for block in BLOCKS.values() for c in block(cols)]
    assert sorted(assigned) == sorted(cols)
