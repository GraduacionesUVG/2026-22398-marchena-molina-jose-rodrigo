import numpy as np
import pytest
import xgboost as xgb

from smartuse.model import MergedModel


def boosters(n=3):
    rng = np.random.default_rng(0)
    X = rng.normal(size=(300, 4))
    y = (X[:, 0] + rng.normal(0, 0.5, 300) > 0).astype(int)
    return X, [xgb.train({"max_depth": 2, "eta": 0.3, "seed": s, "subsample": 0.8}, xgb.DMatrix(X, label=y), 10)
               for s in range(n)]


def test_merged_score_is_sigmoid_of_mean_margin():
    X, bs = boosters()
    m = MergedModel(bs, calibrator={"method": "raw"}, cutoff=0.5, features=["a", "b", "c", "d"])
    margins = np.mean([b.predict(xgb.DMatrix(X), output_margin=True) for b in bs], axis=0)
    np.testing.assert_allclose(m.score(X), 1 / (1 + np.exp(-margins)), rtol=1e-6)


def test_platt_calibration_and_cutoff():
    X, bs = boosters()
    raw = MergedModel(bs, {"method": "raw"}, 0.5, list("abcd")).score(X)
    m = MergedModel(bs, {"method": "platt", "a": 2.0, "b": -1.0}, 0.6, list("abcd"))
    lg = np.log(raw / (1 - raw))
    np.testing.assert_allclose(m.score(X), 1 / (1 + np.exp(-(2.0 * lg - 1.0))), rtol=1e-6)
    assert (m.fires(X) == (m.score(X) > 0.6)).all()


def test_save_and_load_roundtrip(tmp_path):
    X, bs = boosters()
    m = MergedModel(bs, {"method": "platt", "a": 1.5, "b": 0.2}, 0.4, list("abcd"))
    m.save(tmp_path / "F")
    back = MergedModel.load(tmp_path / "F")
    np.testing.assert_allclose(back.score(X), m.score(X), rtol=1e-6)
    assert back.cutoff == pytest.approx(0.4) and back.features == list("abcd")
