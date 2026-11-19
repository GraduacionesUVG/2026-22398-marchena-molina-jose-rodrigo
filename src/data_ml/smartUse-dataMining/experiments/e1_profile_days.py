"""E1 and E10: profile days d and start day (README §5.7).

Pre-registered (2026-10-19), profile B, p = 0.80:
  E1   for d = 1..5 and every start day leaving >= 2 usable days after the
       profile, compare the d-day profile with the all-days profile:
       relative percentile error and label kappa on the decision points
       after the profile. Choose the smallest d in 1..3 whose median-kappa
       gain to d + 1 is < 0.02. Kappa per person is the mean over starts;
       the median is over people.
  E10  at the chosen d, kappa by whether the profile's first day is a
       weekday or a weekend day; a difference > 0.05 recommends weekdays.

Output: data/reports/e1_profile_days.json

    python -m experiments.e1_profile_days
"""

import json
from pathlib import Path

import pandas as pd
from sklearn.metrics import cohen_kappa_score

from experiments.common import load_profile_inputs, ok_days
from smartuse.profile import label, percentiles

OUT_JSON = Path("data/reports/e1_profile_days.json")
P = 0.80
DS = [1, 2, 3, 4, 5]
MIN_EVAL_DAYS = 2
MIN_GAIN = 0.02
MAX_START_DIFF = 0.05


def rel_error(a: float, b: float) -> float:
    return abs(a - b) / b if b else float("nan")


def runs(points: pd.DataFrame) -> pd.DataFrame:
    rows = []
    full = percentiles(points, P)
    for user, days in ok_days(points).items():
        pts = points[points["user_id"] == user]
        for d in DS:
            for s in range(len(days) - d - MIN_EVAL_DAYS + 1):
                prof_days, after = days[s:s + d], days[s + d:]
                prof = percentiles(pts[pts["day"].isin(prof_days)], P)
                ev = pts[pts["day"].isin(after)]
                y_d, y_full = label(ev, prof), label(ev, full)
                kappa = cohen_kappa_score(y_full, y_d) if y_full.nunique() > 1 else float("nan")
                rows.append({"user_id": user, "d": d, "start": s,
                             "weekend_start": pd.Timestamp(prof_days[0]).dayofweek >= 5,
                             "kappa": kappa, "agreement": float((y_d == y_full).mean()),
                             "err_f": rel_error(prof["pf"].iloc[0], full.loc[user, "pf"]),
                             "err_s": rel_error(prof["ps"].iloc[0], full.loc[user, "ps"])})
    return pd.DataFrame(rows)


def main() -> None:
    _, points = load_profile_inputs()
    r = runs(points)
    per_user = r.groupby(["d", "user_id"])[["kappa", "agreement", "err_f", "err_s"]].mean()
    by_d = per_user.groupby("d").median()
    users_per_d = r.groupby("d")["user_id"].nunique()

    chosen = None
    for d in (1, 2, 3):
        if by_d.loc[d + 1, "kappa"] - by_d.loc[d, "kappa"] < MIN_GAIN:
            chosen = d
            break

    e10 = None
    if chosen is not None:
        at_d = r[r["d"] == chosen].groupby(["user_id", "weekend_start"])["kappa"].mean().unstack()
        both = at_d.dropna()
        diff = float((both[False] - both[True]).median())
        e10 = {"d": chosen, "users_with_both": len(both),
               "kappa_weekday_median": float(both[False].median()),
               "kappa_weekend_median": float(both[True].median()),
               "median_diff_weekday_minus_weekend": diff,
               "recommend_weekday": diff > MAX_START_DIFF}

    result = {"runs": len(r),
              "by_d": {int(d): {**{k: float(v) for k, v in row.items()}, "users": int(users_per_d[d])}
                       for d, row in by_d.iterrows()},
              "chosen_d": chosen, "e10": e10}
    OUT_JSON.parent.mkdir(parents=True, exist_ok=True)
    OUT_JSON.write_text(json.dumps(result, indent=2))
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
