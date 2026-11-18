"""E8b: XGBoost variants (README §7.4).

Every variant is scored with leave-one-person-out against the real label y,
over several seeds (XGBoost runs differ by ~0.015 AUC from run to run).
"""

import numpy as np
import pandas as pd

from experiments import tabular as T
from experiments.modeling import BLOCKS, evaluate, persistence_score

PRUNED_BLOCKS = ("w60", "time_context")  # ablation drop < 0.01 in E8


def set_seed(seed: int) -> None:
    T.SEED = seed
    T.XGB.PARAMS = {**T.XGB.PARAMS, "seed": seed}


def pruned(cols: list[str]) -> list[str]:
    drop = {c for b in PRUNED_BLOCKS for c in BLOCKS[b](cols)}
    return [c for c in cols if c not in drop]


def scores_for(real: pd.DataFrame, mpud: pd.DataFrame, cols: list[str], target: str) -> np.ndarray:
    """Out-of-fold probabilities of XGBoost + MPUD trained on one target column."""
    pre = T.pretrain_on_mpud(T.XGB, mpud.assign(y=mpud[target].astype(int)), cols)
    return T.run_loso(T.XGB, real.assign(y=real[target].astype(int)), cols, pre)


def auc(real: pd.DataFrame, score: np.ndarray, target: str = "y") -> float:
    return evaluate(real[target], score, real["user_id"], persistence_score(real), n_boot=10)["auc_median"]


def run(real: pd.DataFrame, mpud: pd.DataFrame, cols: list[str], seeds) -> pd.DataFrame:
    """Per seed: single model, and F and S models with their combinations."""
    rows = []
    for seed in seeds:
        set_seed(seed)
        single = scores_for(real, mpud, cols, "y")
        p_f = scores_for(real, mpud, cols, "y_f")
        p_s = scores_for(real, mpud, cols, "y_s")
        rows.append({
            "seed": seed,
            "single": auc(real, single),
            "two_models": auc(real, 1 - (1 - p_f) * (1 - p_s)),
            "two_models_max": auc(real, np.maximum(p_f, p_s)),
            "f_model_on_y_f": auc(real, p_f, "y_f"), "single_on_y_f": auc(real, single, "y_f"),
            "s_model_on_y_s": auc(real, p_s, "y_s"), "single_on_y_s": auc(real, single, "y_s"),
        })
    return pd.DataFrame(rows).set_index("seed")


def common_rows(d3: pd.DataFrame, d4: pd.DataFrame) -> np.ndarray:
    """Mask over d3 rows that also exist in d4 (same person and time)."""
    keys = set(zip(d4["user_id"], d4["t_utc"]))
    return np.array([k in keys for k in zip(d3["user_id"], d3["t_utc"])])
