"""One production model per pattern: the merged ensemble of the seed boosters.

score(x) = calibrator(sigmoid(mean over seeds of the booster margin))
fires(x) = score(x) > cutoff

Averaging the margins of the seeds is the same as one booster holding all of
their trees with the leaves divided by the number of seeds, so the exported
Java code can be a single model (README §7.6). Calibrators:
"raw" (none) or "platt" (sigmoid(a * logit(p) + b)).
"""

import json
from pathlib import Path

import numpy as np
import xgboost as xgb

EPS = 1e-6


class MergedModel:
    def __init__(self, boosters: list, calibrator: dict, cutoff: float, features: list[str]):
        self.boosters, self.calibrator, self.cutoff, self.features = boosters, calibrator, cutoff, features

    def margin(self, X: np.ndarray) -> np.ndarray:
        d = xgb.DMatrix(np.asarray(X, dtype=np.float32))
        return np.mean([b.predict(d, output_margin=True) for b in self.boosters], axis=0)

    def score(self, X: np.ndarray) -> np.ndarray:
        p = 1 / (1 + np.exp(-self.margin(X)))
        if self.calibrator["method"] == "platt":
            p = np.clip(p, EPS, 1 - EPS)
            p = 1 / (1 + np.exp(-(self.calibrator["a"] * np.log(p / (1 - p)) + self.calibrator["b"])))
        return p

    def fires(self, X: np.ndarray) -> np.ndarray:
        return self.score(X) > self.cutoff

    def save(self, folder: Path) -> None:
        folder = Path(folder)
        folder.mkdir(parents=True, exist_ok=True)
        for k, b in enumerate(self.boosters):
            b.save_model(folder / f"seed{k}.json")
        meta = {"n_seeds": len(self.boosters), "calibrator": self.calibrator, "cutoff": self.cutoff,
                "features": self.features}
        (folder / "model.json").write_text(json.dumps(meta, indent=2))

    @classmethod
    def load(cls, folder: Path) -> "MergedModel":
        folder = Path(folder)
        meta = json.loads((folder / "model.json").read_text())
        boosters = []
        for k in range(meta["n_seeds"]):
            b = xgb.Booster()
            b.load_model(folder / f"seed{k}.json")
            boosters.append(b)
        return cls(boosters, meta["calibrator"], meta["cutoff"], meta["features"])
