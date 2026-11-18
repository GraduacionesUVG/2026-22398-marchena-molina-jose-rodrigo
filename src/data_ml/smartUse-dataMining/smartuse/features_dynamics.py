"""Activity dynamics at three scales, without the profile (E8d, README §6.4).

Two signals per bin, from the session log up to t (ends clipped to t):
sessions started in the bin and screen-on minutes in the bin. Scales:
last 15 min (3 bins of 5 min), last hour (4 of 15 min), last 24 h (24 of 1 h).
Per scale and signal: velocity, acceleration, relative change, centre of
mass, peak and trend persistence. Across scales, per signal: log ratios of
per-minute variability (15 min vs 1 h, 1 h vs 24 h) and of level (15 min vs
24 h).
"""

import math

import numpy as np

SCALES = {"15m": (900.0, 300.0), "1h": (3600.0, 900.0), "24h": (86400.0, 3600.0)}
SIGNALS = ("n", "min")
STATS = ("slope", "accel", "rel_change", "com", "peak", "persist")
EPS = 0.01

GROUPS = {
    **{f"dyn_{sc}": [f"{sig}_{sc}_{st}" for sig in SIGNALS for st in STATS] for sc in SCALES},
    "dyn_cross": [f"{sig}_{name}" for sig in SIGNALS
                  for name in ("var_15m_1h", "var_1h_24h", "level_15m_24h")],
}


def _slope(x: np.ndarray) -> float:
    if len(x) < 2:
        return 0.0
    i = np.arange(len(x), dtype=float)
    return float(np.cov(i, x, bias=True)[0, 1] / i.var())


def shape_stats(x: np.ndarray) -> dict:
    """Velocity, acceleration, relative change, centre of mass, peak, persistence."""
    b = len(x)
    half = math.ceil(b / 2)
    prev = x[:-1].mean()
    total = x.sum()
    d = np.diff(x)
    persist = 0.0
    if len(d) and d[-1] != 0:
        sign = np.sign(d[-1])
        for step in d[::-1]:
            if np.sign(step) != sign:
                break
            persist += sign
    return {
        "slope": _slope(x),
        "accel": _slope(x[-half:]) - _slope(x[:half]),
        "rel_change": float((x[-1] - prev) / (prev + 1)),
        "com": float((np.arange(b) * x).sum() / total / (b - 1)) if total > 0 else 0.5,
        "peak": float(x.max() / x.mean()) if total > 0 else 0.0,
        "persist": float(persist),
    }


def bins(starts: np.ndarray, ends: np.ndarray, t: float, window: float, width: float):
    """Per bin over [t - window, t): sessions started and screen-on minutes."""
    edges = t - window + width * np.arange(int(window // width) + 1)
    i = int(np.searchsorted(ends, edges[0], side="right"))
    k = int(np.searchsorted(starts, t, side="right"))
    s, e = starts[i:k], np.minimum(ends[i:k], t)
    lo, hi = edges[:-1], edges[1:]
    overlap = np.clip(np.minimum(e[:, None], hi) - np.maximum(s[:, None], lo), 0, None)
    started = ((s[:, None] >= lo) & (s[:, None] < hi)).sum(axis=0)
    return started.astype(float), overlap.sum(axis=0) / 60


def extract_dynamics(starts: np.ndarray, ends: np.ndarray, t: float) -> dict:
    series = {sc: dict(zip(SIGNALS, bins(starts, ends, t, w, b))) for sc, (w, b) in SCALES.items()}
    v = {}
    for sc in SCALES:
        for sig in SIGNALS:
            for st, val in shape_stats(series[sc][sig]).items():
                v[f"{sig}_{sc}_{st}"] = val
    for sig in SIGNALS:
        rate = {sc: series[sc][sig] / (SCALES[sc][1] / 60) for sc in SCALES}  # per minute
        v[f"{sig}_var_15m_1h"] = math.log((rate["15m"].std() + EPS) / (rate["1h"].std() + EPS))
        v[f"{sig}_var_1h_24h"] = math.log((rate["1h"].std() + EPS) / (rate["24h"].std() + EPS))
        v[f"{sig}_level_15m_24h"] = math.log((rate["15m"].mean() + EPS) / (rate["24h"].mean() + EPS))
    return v
