"""Phase G: merged ensemble, calibration and the final validation split (README §7.6)."""

import numpy as np
from scipy.stats import spearmanr
from sklearn.isotonic import IsotonicRegression
from sklearn.linear_model import LogisticRegression

EPS = 1e-6


def logit(p: np.ndarray) -> np.ndarray:
    p = np.clip(p, EPS, 1 - EPS)
    return np.log(p / (1 - p))


def sigmoid(x: np.ndarray) -> np.ndarray:
    return 1 / (1 + np.exp(-x))


def merge_probs(per_seed: dict) -> np.ndarray:
    """Merged ensemble: sigmoid of the mean margin (logit) over seeds."""
    return sigmoid(np.mean([logit(np.asarray(p)) for p in per_seed.values()], axis=0))


def representative_seed(per_seed: dict):
    """Seed whose scores have the highest Spearman correlation with the merged ensemble."""
    merged = merge_probs(per_seed)
    return max(per_seed, key=lambda s: spearmanr(per_seed[s], merged).statistic)


def ece(y, p, bins: int = 10) -> float:
    """Expected calibration error over equal-width bins."""
    y, p = np.asarray(y, dtype=float), np.asarray(p, dtype=float)
    idx = np.minimum((p * bins).astype(int), bins - 1)
    total = 0.0
    for b in range(bins):
        m = idx == b
        if m.any():
            total += m.mean() * abs(y[m].mean() - p[m].mean())
    return float(total)


def fit_calibrator(s, y, method: str):
    """Returns a function score -> calibrated probability, fitted on (s, y)."""
    s, y = np.asarray(s, dtype=float), np.asarray(y, dtype=int)
    if method == "platt":
        lr = LogisticRegression(C=1e6).fit(logit(s)[:, None], y)
        return lambda x: lr.predict_proba(logit(np.asarray(x, dtype=float))[:, None])[:, 1]
    iso = IsotonicRegression(y_min=0.0, y_max=1.0, out_of_bounds="clip").fit(s, y)
    return lambda x: iso.predict(np.asarray(x, dtype=float))


def lopo_calibrate(s, y, users, method: str) -> np.ndarray:
    """Each person's scores calibrated with a calibrator fitted on everyone else."""
    s, y, users = np.asarray(s, dtype=float), np.asarray(y, dtype=int), np.asarray(users)
    out = np.empty_like(s)
    for u in np.unique(users):
        m = users == u
        out[m] = fit_calibrator(s[~m], y[~m], method)(s[m])
    return out


def validation_people(counts: dict, seed: int = 0) -> list:
    """The person with the most decision points, plus 2 at random from the middle third by count."""
    order = sorted(counts, key=counts.get)
    chunky = order[-1]
    third = len(order) // 3
    middle = [u for u in order[third:2 * third] if u != chunky]
    picks = np.random.default_rng(seed).choice(middle, size=2, replace=False)
    return [chunky, *[str(u) for u in picks]]
