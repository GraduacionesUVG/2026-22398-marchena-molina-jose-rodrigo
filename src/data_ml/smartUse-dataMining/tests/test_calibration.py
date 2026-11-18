import numpy as np
import pytest

from experiments.calibration import ece, lopo_calibrate, merge_probs, representative_seed, validation_people


def test_merge_averages_margins():
    p = {0: np.array([0.5, 0.9]), 1: np.array([0.5, 0.1])}
    out = merge_probs(p)
    assert out[0] == pytest.approx(0.5) and out[1] == pytest.approx(0.5)  # logit(.9) = -logit(.1)


def test_representative_seed_is_most_correlated_with_the_mean():
    rng = np.random.default_rng(0)
    z = rng.normal(0, 1, 500)  # true logits
    sig = lambda x: 1 / (1 + np.exp(-x))
    seeds = {0: sig(z + rng.normal(0, 1.0, 500)), 1: sig(z + rng.normal(0, 0.05, 500)),
             2: sig(z + rng.normal(0, 1.0, 500))}
    assert representative_seed(seeds) == 1


def test_ece_of_perfectly_calibrated_scores_is_small():
    rng = np.random.default_rng(1)
    p = rng.random(20000)
    assert ece(rng.random(20000) < p, p) < 0.02


def test_lopo_calibration_never_uses_the_person_it_scores():
    users = np.array(["a"] * 200 + ["b"] * 200)
    s = np.r_[np.linspace(0, 1, 200), np.linspace(0, 1, 200)]
    y = np.r_[np.linspace(0, 1, 200) > 0.5, np.linspace(0, 1, 200) > 0.9]
    out = lopo_calibrate(s, y, users, "isotonic")
    # b's calibrator is fitted on a only: a is positive above 0.5, so b's 0.7 maps to ~1
    assert out[200 + 140] == pytest.approx(1.0)


def test_validation_people_one_chunky_two_from_middle_third():
    counts = {f"u{i}": 10 * (i + 1) for i in range(18)}  # u17 largest; middle third u6..u11
    val = validation_people(counts, seed=0)
    assert val[0] == "u17" and len(val) == 3
    assert all(v in {f"u{i}" for i in range(6, 12)} for v in val[1:])
