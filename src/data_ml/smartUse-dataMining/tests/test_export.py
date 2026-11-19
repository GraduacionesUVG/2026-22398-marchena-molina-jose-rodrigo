import numpy as np
import pytest
import xgboost as xgb

from experiments.export import base_margin, margin_reference, parse_trees, read_model_file, write_model_file
from smartuse.model import MergedModel


def booster(seed=0, n=12):
    rng = np.random.default_rng(seed)
    X = rng.normal(size=(400, 5)).astype(np.float32)
    y = (X[:, 0] - X[:, 2] + rng.normal(0, 0.5, 400) > 0).astype(int)
    b = xgb.train({"max_depth": 4, "eta": 0.3, "seed": seed, "subsample": 0.8, "objective": "binary:logistic"},
                  xgb.DMatrix(X, label=y), n)
    return X, b


def test_reference_margin_matches_xgboost():
    X, b = booster()
    ref = margin_reference(parse_trees(b), base_margin(b), X)
    np.testing.assert_allclose(ref, b.predict(xgb.DMatrix(X), output_margin=True), atol=1e-5)


def test_reference_margin_handles_values_on_the_threshold():
    X, b = booster(1)
    trees = parse_trees(b)
    # put every feature exactly on some split threshold: x < t goes to 'yes', x == t to 'no'
    thr = [t["threshold"][t["feature"] >= 0] for t in trees]
    X2 = X.copy()
    X2[:, 0] = np.float32(np.concatenate(thr)[0])
    np.testing.assert_allclose(margin_reference(trees, base_margin(b), X2),
                               b.predict(xgb.DMatrix(X2), output_margin=True), atol=1e-5)


def test_model_file_roundtrip_scores_like_merged_model(tmp_path):
    X, b0 = booster(0)
    _, b1 = booster(1)
    m = MergedModel([b0, b1], {"method": "platt", "a": 1.25, "b": 0.6}, 0.45, list("abcde"))
    path = tmp_path / "F.model"
    write_model_file(m, path, "F")
    back = read_model_file(path)
    assert back["features"] == list("abcde") and back["cutoff"] == pytest.approx(0.45)
    np.testing.assert_allclose(back["score"](X), m.score(X), atol=1e-6)


def test_java_parity_on_exported_models(tmp_path):
    import shutil
    import subprocess
    from pathlib import Path
    if not shutil.which("javac") or not Path("export/models/F.model").exists():
        pytest.skip("needs javac and python -m experiments.export_models")
    sources = sorted(str(p) for p in Path("export/java/smartuse").glob("*.java"))
    subprocess.run(["javac", "-d", str(tmp_path), *sources], check=True)
    for pattern in ("F", "S"):
        run = subprocess.run(["java", "-cp", str(tmp_path), "smartuse.ParityCheck", f"export/models/{pattern}.model",
                              f"export/parity/{pattern}_vectors.csv", "1e-6"], capture_output=True, text=True)
        assert run.returncode == 0, run.stdout + run.stderr
