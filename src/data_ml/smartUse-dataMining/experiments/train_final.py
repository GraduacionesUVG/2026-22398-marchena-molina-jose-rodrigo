"""Phase G, step 4: train the production models (README §7.7).

Per pattern and seed (0..4): pretrain XGBoost on MPUD (20 MPUD people as
validation), fine-tune on the real data with early stopping on 3 people (the
one with the most decision points + 2 at random from the middle third), the
other 15 train. The 5 boosters form one MergedModel per pattern, with the
calibrator from notebooks/g_calibration.ipynb. Cutoffs: F from
experiments/cutoff_f1.py (maximum F1), S from the E9b rule in g_calibration.

Outputs: data/models/F/, data/models/S/ (boosters + model.json) and
data/models/production.json (rules and provenance; validation people as
pseudonyms).

    python -m experiments.train_final
"""

import json
from pathlib import Path

import numpy as np
import pandas as pd

from experiments import tabular as T
from experiments import variants as V
from experiments.calibration import validation_people
from experiments.common import pseudonym
from experiments.modeling import evaluate, feature_cols, persistence_score
from smartuse.features_dynamics import GROUPS
from smartuse.model import MergedModel

SEEDS = range(5)
OUT = Path("data/models")


def table(name: str, suffix: str, dynamics: bool) -> pd.DataFrame:
    base = pd.read_csv(f"data/processed/{name}_features{suffix}.csv", parse_dates=["day"])
    if not dynamics:
        return base
    dyn = pd.read_csv(f"data/processed/{name}_features_dynamics{suffix}.csv")
    assert (base["t_utc"].values == dyn["t_utc"].values).all()
    return pd.concat([base, dyn.drop(columns=["user_id", "t_utc"])], axis=1)


def main() -> None:
    gcal = json.loads(Path("data/reports/g_calibration.json").read_text())
    cutoffs = {"F": json.loads(Path("data/reports/cutoff_f1.json").read_text())["cutoff_platt"],
               "S": gcal["cutoffs"]["S"]["c"]}
    rules = {"F": "max pooled F1 (experiments/cutoff_f1.py)", "S": "E9b rule (notebooks/g_calibration.ipynb)"}
    pruned = V.pruned(feature_cols(pd.read_csv("data/processed/real_features.csv", nrows=1)))
    specs = {
        "F": {"label": "y_f", "suffix": "", "dynamics": True, "offset_min": 0,
              "cols": pruned + [c for cols in GROUPS.values() for c in cols]},
        "S": {"label": "y_s", "suffix": "_b30", "dynamics": False, "offset_min": 30, "cols": pruned},
    }
    counts = table("real", "", False).groupby("user_id").size().to_dict()
    val = validation_people(counts, seed=0)
    print("validation people:", [(pseudonym(u), counts[u]) for u in val])

    production = {"rules": {"k_min": 10, "cooldown_min": 10, "episode_min": 60, "daily_cap": 12,
                            "session_gap_s": 30, "t_max_h": 3, "profile_days": 3, "p": 0.80, "w_min": 60},
                  "validation_people": [pseudonym(u) for u in val], "models": {}}
    for pat, sp in specs.items():
        real, mpud = table("real", sp["suffix"], sp["dynamics"]), table("mpud", sp["suffix"], sp["dynamics"])
        real, mpud = real.assign(y=real[sp["label"]].astype(int)), mpud.assign(y=mpud[sp["label"]].astype(int))
        tr, va = real[~real["user_id"].isin(val)], real[real["user_id"].isin(val)]
        boosters = []
        for seed in SEEDS:
            V.set_seed(seed)
            pre = T.pretrain_on_mpud(T.XGB, mpud, sp["cols"])
            boosters.append(T.XGB.finetune(pre, *T.xy(tr, sp["cols"]), *T.xy(va, sp["cols"])))
        model = MergedModel(boosters, gcal["calibrator"][pat], cutoffs[pat], sp["cols"])
        model.save(OUT / pat)
        s = model.score(va[sp["cols"]].to_numpy(dtype=np.float32))
        val_auc = evaluate(va["y"], s, va["user_id"], persistence_score(va), n_boot=10)["auc_median"]
        production["models"][pat] = {
            "folder": f"data/models/{pat}", "label": sp["label"], "label_offset_min": sp["offset_min"],
            "n_features": len(sp["cols"]), "calibrator": gcal["calibrator"][pat]["method"],
            "cutoff": cutoffs[pat], "cutoff_rule": rules[pat], "trees_per_seed": [b.num_boosted_rounds() for b in boosters],
            "validation_auc_median": val_auc, "validation_mean_score": float(s.mean()),
            "validation_positive_rate": float(va["y"].mean())}
        print(pat, production["models"][pat])
    (OUT / "production.json").write_text(json.dumps(production, indent=2, default=float))
    print("->", OUT / "production.json")


if __name__ == "__main__":
    main()
