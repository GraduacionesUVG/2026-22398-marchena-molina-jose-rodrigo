"""Logistic regression (L2) and XGBoost: pretraining on MPUD, fine-tuning on
the real data, and the leave-one-person-out and temporal runs (E8, E13).

Both models expose the same three functions so the runners are shared:
  pretrain(X, y, X_val, y_val)                 -> model
  finetune(model | None, X, y, X_val, y_val)   -> model  (None = real data only)
  predict(model, X)                            -> probabilities
"""

import copy

import numpy as np
import pandas as pd
import xgboost as xgb
from sklearn.linear_model import SGDClassifier
from sklearn.metrics import log_loss
from sklearn.preprocessing import StandardScaler

from experiments.modeling import evaluate, loso_folds, persistence_score, temporal_split

SEED = 0


# ---------------------------------------------------------------- logistic regression

class LogReg:
    """SGD logistic regression with L2, on standardized features."""

    PRE_ETA, FINE_ETA, ALPHA = 0.01, 0.001, 1e-4
    MAX_EPOCHS, PATIENCE = 50, 3

    def __init__(self, scaler: StandardScaler, clf: SGDClassifier):
        self.scaler, self.clf = scaler, clf

    @classmethod
    def _fit(cls, model, X, y, X_val, y_val, eta):
        Xs, Xv = model.scaler.transform(X), model.scaler.transform(X_val)
        model.clf.set_params(eta0=eta)
        rng = np.random.default_rng(SEED)
        best, best_loss, bad = copy.deepcopy(model.clf), np.inf, 0
        for _ in range(cls.MAX_EPOCHS):
            order = rng.permutation(len(Xs))
            model.clf.partial_fit(Xs[order], y[order], classes=[0, 1])
            loss = log_loss(y_val, model.clf.predict_proba(Xv)[:, 1], labels=[0, 1])
            if loss < best_loss - 1e-5:
                best, best_loss, bad = copy.deepcopy(model.clf), loss, 0
            else:
                bad += 1
                if bad >= cls.PATIENCE:
                    break
        return cls(model.scaler, best)

    @classmethod
    def new(cls, X):
        clf = SGDClassifier(loss="log_loss", penalty="l2", alpha=cls.ALPHA, learning_rate="constant",
                            eta0=cls.PRE_ETA, random_state=SEED)
        return cls(StandardScaler().fit(X), clf)

    @classmethod
    def pretrain(cls, X, y, X_val, y_val):
        return cls._fit(cls.new(X), X, y, X_val, y_val, cls.PRE_ETA)

    @classmethod
    def finetune(cls, model, X, y, X_val, y_val):
        if model is None:  # real data only: same schedule as pretraining
            return cls._fit(cls.new(X), X, y, X_val, y_val, cls.PRE_ETA)
        return cls._fit(cls(model.scaler, copy.deepcopy(model.clf)), X, y, X_val, y_val, cls.FINE_ETA)

    @staticmethod
    def predict(model, X):
        return model.clf.predict_proba(model.scaler.transform(X))[:, 1]


# ---------------------------------------------------------------- XGBoost

class XGB:
    """Gradient-boosted trees of depth 4; fine-tuning keeps adding trees."""

    PARAMS = {"objective": "binary:logistic", "eval_metric": "logloss", "max_depth": 4,
              "subsample": 0.8, "colsample_bytree": 0.8, "tree_method": "hist", "seed": SEED}
    PRE_ETA, FINE_ETA, ONLY_ETA = 0.1, 0.02, 0.05
    ROUNDS, EARLY = 2000, 50

    @classmethod
    def _train(cls, X, y, X_val, y_val, eta, base=None):
        dtr, dval = xgb.DMatrix(X, label=y), xgb.DMatrix(X_val, label=y_val)
        booster = xgb.train({**cls.PARAMS, "eta": eta}, dtr, cls.ROUNDS, evals=[(dval, "val")],
                            early_stopping_rounds=cls.EARLY, xgb_model=base, verbose_eval=False)
        return booster[: booster.best_iteration + 1]

    @classmethod
    def pretrain(cls, X, y, X_val, y_val):
        return cls._train(X, y, X_val, y_val, cls.PRE_ETA)

    @classmethod
    def finetune(cls, model, X, y, X_val, y_val):
        if model is None:
            return cls._train(X, y, X_val, y_val, cls.ONLY_ETA)
        return cls._train(X, y, X_val, y_val, cls.FINE_ETA, base=model)

    @staticmethod
    def predict(model, X):
        return model.predict(xgb.DMatrix(X))


# ---------------------------------------------------------------- runners

def xy(df: pd.DataFrame, cols: list[str]):
    return df[cols].to_numpy(dtype=np.float32), df["y"].to_numpy(dtype=int)


def pretrain_on_mpud(Model, mpud: pd.DataFrame, cols: list[str], n_val: int = 20):
    users = sorted(mpud["user_id"].unique())
    val = set(np.random.default_rng(SEED).choice(users, size=n_val, replace=False))
    tr, va = mpud[~mpud["user_id"].isin(val)], mpud[mpud["user_id"].isin(val)]
    return Model.pretrain(*xy(tr, cols), *xy(va, cols))


def run_loso(Model, real: pd.DataFrame, cols: list[str], pre=None) -> np.ndarray:
    """Out-of-fold scores, one fold per person. pre=None trains on real data only."""
    scores = np.full(len(real), np.nan)
    for test, val, train in loso_folds(real["user_id"]):
        tr, va, te = (real["user_id"].isin(g) for g in (train, val, test))
        model = Model.finetune(pre, *xy(real[tr], cols), *xy(real[va], cols))
        scores[te.to_numpy()] = Model.predict(model, xy(real[te], cols)[0])
    return scores


def run_temporal(Model, real: pd.DataFrame, cols: list[str], pre=None, n_val: int = 3):
    """Train on each person's first 60 % of days, test on the rest."""
    train, test = temporal_split(real)
    users = sorted(train["user_id"].unique())
    val = set(np.random.default_rng(SEED).choice(users, size=n_val, replace=False))
    tr, va = train[~train["user_id"].isin(val)], train[train["user_id"].isin(val)]
    model = Model.finetune(pre, *xy(tr, cols), *xy(va, cols))
    return test, Model.predict(model, xy(test, cols)[0])


def report(df: pd.DataFrame, scores: np.ndarray) -> dict:
    return evaluate(df["y"], scores, df["user_id"], persistence_score(df))


def summary(results: dict) -> pd.DataFrame:
    """One row per arm: the headline numbers."""
    rows = {name: {"AUC median": r["auc_median"], "CI95": "%.3f-%.3f" % tuple(r["auc_median_ci95"]),
                   "AUC min": r["auc_min"], "AUC max": r["auc_max"], "AUC pooled": r["auc_pooled"],
                   "PR-AUC": r["pr_auc_pooled"], "Brier": r.get("brier", np.nan),
                   "Onset AUC": r["auc_onset_median"]}
            for name, r in results.items()}
    return pd.DataFrame(rows).T.round(3)
