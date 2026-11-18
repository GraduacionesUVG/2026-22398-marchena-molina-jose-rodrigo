"""E2: weight of the population prior, n0 (README §5.7).

Estimate only, profile B, p = 0.80. For F and S:
  sigma2_day  within-person variance of the percentile estimated from a
              single usable day (days with >= MIN_DAY_POINTS points),
              averaged over people
  tau2        between-person variance of the all-days percentile, minus
              the estimation noise of those percentiles (sigma2_day / days)
  n0_days     sigma2_day / tau2: own days at which own and prior weigh the same
  n0_points   n0_days * median usable decision points per day (the unit of
              n in w = n / (n + n0))

Also writes E10 at d = 3 (kappa by weekday or weekend start), for when the
user fixes d = 3.

Output: data/reports/e2_prior_weight.json

    python -m experiments.e2_prior_weight
"""

import json
from pathlib import Path

import pandas as pd

from experiments.common import load_profile_inputs
from experiments.e1_profile_days import runs
from smartuse.profile import percentiles

OUT_JSON = Path("data/reports/e2_prior_weight.json")
P = 0.80
MIN_DAY_POINTS = 10


def main() -> None:
    _, points = load_profile_inputs()
    full = percentiles(points, P)
    per_day = points.groupby(["user_id", "day"])
    counts = per_day.size()
    day_prof = per_day[["f", "s"]].quantile(P)[counts >= MIN_DAY_POINTS]
    days_used = day_prof.groupby("user_id").size()

    result = {"users": int(points["user_id"].nunique()),
              "median_points_per_day": float(counts.median()), "metrics": {}}
    for metric, col in (("f", "pf"), ("s", "ps")):
        sigma2_by_user = day_prof[metric].groupby("user_id").var()
        sigma2_day = float(sigma2_by_user.mean())
        noise = float((sigma2_by_user / days_used).mean())
        tau2 = float(full[col].var()) - noise
        n0_days = sigma2_day / tau2 if tau2 > 0 else float("inf")
        result["metrics"][metric] = {
            "between_person_var_raw": float(full[col].var()), "tau2": tau2,
            "sigma2_day": sigma2_day, "n0_days": n0_days,
            "n0_points": n0_days * float(counts.median()),
            "weight_own_at_d": {d: d / (d + n0_days) for d in (1, 2, 3)},
        }

    r = runs(points)
    at3 = r[r["d"] == 3].groupby(["user_id", "weekend_start"])["kappa"].mean().unstack().dropna()
    result["e10_at_d3"] = {"users_with_both": len(at3),
                           "kappa_weekday_median": float(at3[False].median()),
                           "kappa_weekend_median": float(at3[True].median()),
                           "median_diff_weekday_minus_weekend": float((at3[False] - at3[True]).median())}
    OUT_JSON.parent.mkdir(parents=True, exist_ok=True)
    OUT_JSON.write_text(json.dumps(result, indent=2))
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
