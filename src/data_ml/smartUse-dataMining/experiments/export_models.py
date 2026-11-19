"""Export the production models and check Java/Python parity.

  1. data/models/{F,S} (MergedModel) -> export/models/{F,S}.model, and
     data/models/production.json copied to export/models/
  2. parity vectors from MPUD (public data): 3 000 random rows plus edge rows
     (all zeros, very large values) -> export/parity/{F,S}_vectors.csv, with
     the expected score from XGBoost itself
  3. the model file read back in Python must match XGBoost (<= 1e-6)
  4. compile export/java and run smartuse.ParityCheck on both models

    python -m experiments.export_models
"""

import shutil
import subprocess
import tempfile
from pathlib import Path

import numpy as np
import pandas as pd

from experiments.export import read_model_file, write_model_file
from smartuse.model import MergedModel

TABLES = {"F": ("mpud_features", "mpud_features_dynamics"), "S": ("mpud_features_b30", None)}
OUT = Path("export")
TOL = 1e-6
N_RANDOM = 3000


def vectors(pattern: str, features: list[str]) -> np.ndarray:
    base, dyn = TABLES[pattern]
    cols = [c for c in features]
    table = pd.read_csv(f"data/processed/{base}.csv")
    if dyn:
        d = pd.read_csv(f"data/processed/{dyn}.csv")
        assert (table["t_utc"].values == d["t_utc"].values).all()
        table = pd.concat([table, d.drop(columns=["user_id", "t_utc"])], axis=1)
    X = table[cols].sample(N_RANDOM, random_state=0).to_numpy(dtype=np.float32)
    edge = np.vstack([np.zeros(len(cols)), np.abs(X).max(axis=0) * 10, -np.abs(X).max(axis=0) * 10]).astype(np.float32)
    return np.vstack([X, edge])


def main() -> None:
    (OUT / "models").mkdir(parents=True, exist_ok=True)
    (OUT / "parity").mkdir(parents=True, exist_ok=True)
    for pattern in ("F", "S"):
        model = MergedModel.load(Path("data/models") / pattern)
        path = OUT / "models" / f"{pattern}.model"
        write_model_file(model, path, pattern)
        X = vectors(pattern, model.features)
        expected = model.score(X)
        from_file = read_model_file(path)["score"](X)
        diff = float(np.abs(from_file - expected).max())
        assert diff <= TOL, f"{pattern}: model file differs from XGBoost by {diff}"
        csv = pd.DataFrame(X, columns=model.features).map(lambda v: repr(float(v)))
        csv["expected_score"] = [repr(float(s)) for s in expected]
        csv["expected_fires"] = ["true" if s > model.cutoff else "false" for s in expected]
        csv.to_csv(OUT / "parity" / f"{pattern}_vectors.csv", index=False)
        size_kb = path.stat().st_size / 1024
        print(f"{pattern}: cutoff {model.cutoff:.4f}, {len(model.boosters)} seeds, {sum(b.num_boosted_rounds() for b in model.boosters)} trees, "
              f"{len(model.features)} features, file {size_kb:.0f} KB; python file vs xgboost max diff {diff:.2e}")

    shutil.copyfile(Path("data/models/production.json"), OUT / "models" / "production.json")

    if not shutil.which("javac"):
        print("javac not found: skipping the Java parity check")
        return
    sources = sorted(str(p) for p in (OUT / "java" / "smartuse").glob("*.java"))
    with tempfile.TemporaryDirectory() as classes:  # compiled classes stay out of the repo
        subprocess.run(["javac", "-d", classes, *sources], check=True)
        for pattern in ("F", "S"):
            subprocess.run(["java", "-cp", classes, "smartuse.ParityCheck",
                            str(OUT / "models" / f"{pattern}.model"), str(OUT / "parity" / f"{pattern}_vectors.csv"),
                            str(TOL)], check=True)


if __name__ == "__main__":
    main()
