"""Recompute the golden fixtures (export/fixtures) from their input epochs.

This is the reference a port of the extractor has to reproduce
(README §9): epochs -> sessions -> decision points ->
profiles -> feature vectors -> scores.
"""

import json
from pathlib import Path

import numpy as np
import pandas as pd
import pytest

from experiments.export import read_model_file
from pipelines.labels.build_windows import build, seconds
from pipelines.mpud import mpud_preprocess_3
from smartuse.features import Profile, extract
from smartuse.features_dynamics import extract_dynamics

FIX = Path("export/fixtures")
PEOPLE = sorted(p for p in FIX.iterdir() if p.is_dir()) if FIX.exists() else []
pytestmark = pytest.mark.skipif(not PEOPLE, reason="run python -m experiments.make_fixtures first")
DATES = ["start_utc", "start_local", "end_utc"]


def profile(path: Path) -> Profile:
    d = json.loads(path.read_text())
    fields = {k: (np.array(v) if isinstance(v, list) else v) for k, v in d.items() if k in Profile.__dataclass_fields__}
    return Profile(**fields)


@pytest.mark.parametrize("folder", PEOPLE, ids=lambda p: p.name)
def test_sessions_from_epochs(folder, tmp_path):
    mpud_preprocess_3.main(folder / "epochs.csv", tmp_path / "s.csv")
    got = pd.read_csv(tmp_path / "s.csv")
    want = pd.read_csv(folder / "sessions.csv")
    pd.testing.assert_frame_equal(got[want.columns].reset_index(drop=True), want, check_dtype=False)


@pytest.mark.parametrize("folder", PEOPLE, ids=lambda p: p.name)
@pytest.mark.parametrize("b", [0, 30])
def test_decision_points_and_label_windows(folder, b):
    sessions = pd.read_csv(folder / "sessions.csv", parse_dates=DATES)
    epochs = pd.read_csv(folder / "epochs.csv", usecols=["user_id", "start_utc", "start_local", "is_use", "duration_s"],
                         parse_dates=["start_utc", "start_local"])
    got = build(sessions, "points", 3600.0, 600.0, 3 * 3600.0, epochs=epochs, label_offset_s=b * 60.0)
    want = pd.read_csv(folder / f"points_b{b}.csv")
    assert got["t_utc"].tolist() == want["t_utc"].tolist()
    for col in ("n_sessions", "f", "s", "masked", "censored", "day_ok"):
        np.testing.assert_allclose(got[col].astype(float), want[col].astype(float), atol=1e-9, err_msg=col)


@pytest.mark.parametrize("folder", PEOPLE, ids=lambda p: p.name)
@pytest.mark.parametrize("pattern", ["F", "S"])
def test_feature_vectors_and_scores(folder, pattern):
    sessions = pd.read_csv(folder / "sessions.csv", parse_dates=DATES)
    starts, ends = seconds(sessions["start_utc"]), seconds(sessions["end_utc"])
    apps = sessions["app_count_max"].to_numpy(dtype=float)
    prof_json = json.loads((folder / f"profile_{pattern}.json").read_text())
    prof = profile(folder / f"profile_{pattern}.json")
    model = read_model_file(Path("export/models") / f"{pattern}.model")
    want = pd.read_csv(folder / f"vectors_{pattern}.csv")
    rows = []
    for t, unlock in zip(seconds(pd.to_datetime(want["t_utc"])), want["is_unlock"]):
        v = extract(starts, ends, apps, t, prof_json["offset_s"], not unlock, prof)
        if pattern == "F":
            v.update(extract_dynamics(starts, ends, t))
        rows.append([v[c] for c in model["features"]])
    got = np.array(rows, dtype=np.float32)
    np.testing.assert_allclose(got, want[model["features"]].to_numpy(dtype=np.float32), rtol=1e-5, atol=1e-5)
    np.testing.assert_allclose(model["score"](got), want["expected_score"], atol=1e-6)
