"""Operating cutoff of model F: maximum pooled F1 (decision of 2026-10-22, README).

E1d showed that the F1 of F was limited by the E9b precision floor, not by
the profile, so the cutoff of F moved from the E9b rule to the maximum
point-level F1 over the pooled leave-one-person-out scores (the merged E8d
ensemble: y_f, b = 0, pruned + all dynamics; d = 3). S keeps the E9b cutoff.

The search is exhaustive (every midpoint between distinct scores) on the raw
scale and the result is mapped to the Platt scale that model F uses.

Writes data/reports/cutoff_f1.json and, when they exist, updates the cutoff
of F in data/models/F/model.json and data/models/production.json. Afterwards
rerun `python -m experiments.export_models` and `python -m experiments.make_fixtures`.

    python -m experiments.cutoff_f1
"""

import json
from pathlib import Path

import numpy as np

from experiments import calibration as C
from experiments.f1_loso import load, prf

OUT = Path("data/reports/cutoff_f1.json")
MODELS = Path("data/models")


def best_f1_exhaustive(raw: np.ndarray, y: np.ndarray) -> float:
    """Cutoff c maximising F1 of (raw > c): midpoint below the best distinct score."""
    order = np.argsort(-raw, kind="stable")
    r, t = raw[order], y[order]
    tp = np.cumsum(t)
    f1 = 2 * tp / (np.arange(1, len(r) + 1) + t.sum())
    last_of_tie = np.r_[r[:-1] > r[1:], True]  # only cut between distinct scores
    i = int(np.argmax(np.where(last_of_tie, f1, -1)))
    return float((r[i] + r[i + 1]) / 2) if i + 1 < len(r) else float(r[i] - 1e-9)


def main() -> None:
    df = load("F")
    y, raw = df["y"].to_numpy().astype(bool), df["raw"].to_numpy()
    cal = json.loads((MODELS / "F" / "model.json").read_text())["calibrator"]
    assert cal["method"] == "platt"
    c_raw = best_f1_exhaustive(raw, y)
    c = float(C.sigmoid(cal["a"] * C.logit(np.array([c_raw])) + cal["b"])[0])
    stats = prf(y, raw > c_raw)
    report = {"pattern": "F", "rule": "max pooled point-level F1, LOSO scores", "n_points": len(df),
              "n_people": int(df["user_id"].nunique()), "cutoff_raw": c_raw, "cutoff_platt": c,
              "fire_rate": float((raw > c_raw).mean()), **stats}
    OUT.write_text(json.dumps(report, indent=2, default=float))
    print(json.dumps(report, indent=2, default=float), "\n->", OUT)

    meta_path, prod_path = MODELS / "F" / "model.json", MODELS / "production.json"
    if meta_path.exists():
        meta = json.loads(meta_path.read_text())
        meta["cutoff"] = c
        meta_path.write_text(json.dumps(meta, indent=2))
        print("updated", meta_path)
    if prod_path.exists():
        prod = json.loads(prod_path.read_text())
        prod["models"]["F"]["cutoff"] = c
        prod["models"]["F"]["cutoff_rule"] = "max pooled F1 (experiments/cutoff_f1.py)"
        prod["models"]["S"]["cutoff_rule"] = "E9b rule (notebooks/g_calibration.ipynb)"
        prod_path.write_text(json.dumps(prod, indent=2, default=float))
        print("updated", prod_path)


if __name__ == "__main__":
    main()
