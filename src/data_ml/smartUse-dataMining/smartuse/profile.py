"""Per-person profile and label (README §5.4–§5.5).

The profile is the person's p-quantile of F and S over the future windows
of their usable decision points: the same population of windows the label
is computed on (an hourly grid profile, with its empty hours, sat far below
what decision points see; E3). A decision point is positive if F or S of
its future window is strictly above the profile. With few own days the
profile is blended with a population prior: w * own + (1 - w) * prior,
w = n / (n + n0).
"""

import pandas as pd


def usable(windows: pd.DataFrame) -> pd.Series:
    """Not masked, not censored and on a day with enough data."""
    return ~windows["masked"] & ~windows["censored"] & windows["day_ok"]


def percentiles(windows: pd.DataFrame, p: float) -> pd.DataFrame:
    """Per person: pf, ps = p-quantiles of f and s. Index: user_id."""
    by_user = windows.groupby("user_id")
    return pd.DataFrame({"pf": by_user["f"].quantile(p), "ps": by_user["s"].quantile(p)})


def label(points: pd.DataFrame, profile: pd.DataFrame) -> pd.Series:
    """F or S strictly above the person's profile."""
    pf = points["user_id"].map(profile["pf"])
    ps = points["user_id"].map(profile["ps"])
    return (points["f"] > pf) | (points["s"] > ps)


def blend(own: float, prior: float, n: float, n0: float) -> float:
    """Empirical-Bayes shrinkage of an own estimate toward the prior."""
    w = n / (n + n0)
    return w * own + (1 - w) * prior
