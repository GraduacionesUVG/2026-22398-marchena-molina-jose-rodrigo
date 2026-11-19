"""E3: label percentile p (README §5.7).

Profile B: the person's p-quantiles of F and S over their usable decision
points. Pre-registered (2026-10-19): at p = 0.80 on decision points, F
positive in 20 % +- 3 pts, S in 20 % +- 3 pts and the label in 36 % +- 4
pts -> p = 0.80 is fixed. Also reported: p = 0.70 and 0.90, prevalence on
grid windows and stability (kappa between labels from the first-half-days
profile and the second-half-days profile).

Output: data/reports/e3_percentile.json

    python -m experiments.e3_percentile
"""

import json
from pathlib import Path

import pandas as pd
from sklearn.metrics import cohen_kappa_score

from experiments.common import load_profile_inputs, ok_days
from smartuse.profile import label, percentiles

OUT_JSON = Path("data/reports/e3_percentile.json")
PS = [0.70, 0.80, 0.90]


def half_profiles(points: pd.DataFrame, p: float) -> tuple[pd.DataFrame, pd.DataFrame]:
    first, second = [], []
    for user, days in ok_days(points).items():
        g = points[points["user_id"] == user]
        half = len(days) // 2
        first.append(g[g["day"].isin(days[:half])])
        second.append(g[g["day"].isin(days[half:])])
    return percentiles(pd.concat(first), p), percentiles(pd.concat(second), p)


def main() -> None:
    grid, points = load_profile_inputs()
    result = {"grid_windows": len(grid), "decision_points": len(points), "users": int(points["user_id"].nunique()),
              "by_p": {}}
    for p in PS:
        prof = percentiles(points, p)
        f_pos = points["f"] > points["user_id"].map(prof["pf"])
        s_pos = points["s"] > points["user_id"].map(prof["ps"])
        y = f_pos | s_pos
        h1, h2 = half_profiles(points, p)
        y1, y2 = label(points, h1), label(points, h2)
        per_user = y.groupby(points["user_id"]).mean()
        result["by_p"][p] = {
            "prevalence_points": float(y.mean()),
            "f_positive_points": float(f_pos.mean()),
            "s_positive_points": float(s_pos.mean()),
            "prevalence_per_user": {"min": float(per_user.min()), "median": float(per_user.median()),
                                    "max": float(per_user.max())},
            "prevalence_grid": float(label(grid, prof).mean()),
            "share_only_f": float((f_pos & ~s_pos).sum() / y.sum()),
            "share_only_s": float((s_pos & ~f_pos).sum() / y.sum()),
            "share_both": float((f_pos & s_pos).sum() / y.sum()),
            "stability_kappa": float(cohen_kappa_score(y1, y2)),
            "stability_agreement": float((y1 == y2).mean()),
        }
    r80 = result["by_p"][0.8]
    result["fixes_p80"] = (abs(r80["f_positive_points"] - 0.20) <= 0.03
                           and abs(r80["s_positive_points"] - 0.20) <= 0.03
                           and abs(r80["prevalence_points"] - 0.36) <= 0.04)
    OUT_JSON.parent.mkdir(parents=True, exist_ok=True)
    OUT_JSON.write_text(json.dumps(result, indent=2))
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
