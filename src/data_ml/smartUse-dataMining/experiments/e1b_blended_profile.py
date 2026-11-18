"""E1b: E1 with the profile blended with the population prior (README §5.7).

Pre-registered (2026-10-19), profile B, p = 0.80: blended profile
w * own + (1 - w) * prior, w = n / (n + n0), n = own decision points in the
profile days, n0 from E2 (N0_POINTS); prior = mean of the other people's
all-days percentiles. Passes if at d = 3 the blended profile is not worse
than own-only in median label kappa and in median relative F-percentile
error. F-mark and S-mark kappas are reported separately.

Output: data/reports/e1b_blended_profile.json

    python -m experiments.e1b_blended_profile
"""

import json
from pathlib import Path

import pandas as pd
from sklearn.metrics import cohen_kappa_score

from experiments.common import load_profile_inputs, ok_days
from smartuse.profile import blend, label, percentiles

OUT_JSON = Path("data/reports/e1b_blended_profile.json")
P = 0.80
DS = [1, 2, 3]
MIN_EVAL_DAYS = 2
N0_POINTS = {"pf": 71.0, "ps": 247.0}


def kappa(a: pd.Series, b: pd.Series) -> float:
    return cohen_kappa_score(a, b) if a.nunique() > 1 and b.nunique() > 1 else float("nan")


def rel_error(a: float, b: float) -> float:
    return abs(a - b) / b if b else float("nan")


def main() -> None:
    _, points = load_profile_inputs()
    full = percentiles(points, P)
    rows = []
    for user, days in ok_days(points).items():
        pts = points[points["user_id"] == user]
        prior = full.drop(index=user).mean()
        for d in DS:
            for s in range(len(days) - d - MIN_EVAL_DAYS + 1):
                prof_pts = pts[pts["day"].isin(days[s:s + d])]
                ev = pts[pts["day"].isin(days[s + d:])]
                own = percentiles(prof_pts, P)
                n = len(prof_pts)
                blended = pd.DataFrame({c: [blend(own[c].iloc[0], prior[c], n, N0_POINTS[c])] for c in ("pf", "ps")},
                                       index=own.index)
                ref = full.loc[[user]]
                for variant, prof in (("own", own), ("blended", blended)):
                    rows.append({
                        "user_id": user, "d": d, "start": s, "variant": variant,
                        "kappa": kappa(label(ev, ref), label(ev, prof)),
                        "kappa_f": kappa(ev["f"] > ref["pf"].iloc[0], ev["f"] > prof["pf"].iloc[0]),
                        "kappa_s": kappa(ev["s"] > ref["ps"].iloc[0], ev["s"] > prof["ps"].iloc[0]),
                        "err_f": rel_error(prof["pf"].iloc[0], ref["pf"].iloc[0]),
                        "err_s": rel_error(prof["ps"].iloc[0], ref["ps"].iloc[0]),
                    })
    r = pd.DataFrame(rows)
    metrics = ["kappa", "kappa_f", "kappa_s", "err_f", "err_s"]
    per_user = r.groupby(["variant", "d", "user_id"])[metrics].mean()
    table = per_user.groupby(["variant", "d"]).median()
    own3, bl3 = table.loc[("own", 3)], table.loc[("blended", 3)]
    result = {
        "by_variant_d": {f"{v}_d{d}": {m: float(x) for m, x in row.items()} for (v, d), row in table.iterrows()},
        "passes": bool(bl3["kappa"] >= own3["kappa"] and bl3["err_f"] <= own3["err_f"]),
    }
    OUT_JSON.parent.mkdir(parents=True, exist_ok=True)
    OUT_JSON.write_text(json.dumps(result, indent=2))
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
