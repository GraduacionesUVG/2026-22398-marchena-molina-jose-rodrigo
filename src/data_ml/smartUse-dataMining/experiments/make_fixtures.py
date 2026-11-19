"""Golden fixtures for porting the feature extractor (README §9).

Two MPUD people (public data). For each person, under export/fixtures/<id>/:
  epochs.csv          input: epochs (start_utc, start_local, is_use, distinct_app_count, duration_s)
  sessions.csv        expected sessions (30 s merge, 0-app epochs out, no_close > 3 h)
  points_b0.csv       expected decision points and their label windows for F (b = 0)
  points_b30.csv      same for S (label window shifted 30 min back)
  profile_F.json      expected frozen profile for the F model (from b = 0 windows)
  profile_S.json      expected frozen profile for the S model (from b = 30 windows)
  vectors_F.csv       expected F feature vector (model order) + score + fires, per example point
  vectors_S.csv       same for S
tests/test_fixtures.py recomputes all of it from epochs.csv with the library code.

    python -m experiments.make_fixtures
"""

import json
from pathlib import Path

import numpy as np
import pandas as pd

from pipelines.labels.build_windows import EPOCH, seconds
from smartuse.features import DAY, build_profile
from smartuse.model import MergedModel
from smartuse.profile import usable

OUT = Path("export/fixtures")
D = 3


def pick_people(n: int = 2) -> list[str]:
    """MPUD people closest to the median number of examples (typical, deterministic)."""
    counts = pd.read_csv("data/processed/mpud_features.csv", usecols=["user_id"])["user_id"].value_counts()
    return list((counts - counts.median()).abs().sort_values().index[:n])


def profile_dict(user, sessions, points) -> dict:
    s = sessions
    starts, ends = seconds(s["start_utc"]), seconds(s["end_utc"])
    apps = s["app_count_max"].to_numpy(dtype=float)
    offset_s = float((s["start_local"].iloc[0] - s["start_utc"].iloc[0]).total_seconds())
    pts = points[usable(points)].copy()
    pts["day"] = pts["t_local"].dt.date
    days = sorted(pts["day"].unique())[:D]
    prof = pts[pts["day"].isin(days)]
    midnights = [(pd.Timestamp(d) - EPOCH).total_seconds() - offset_s for d in days]  # local midnight, UTC s
    bounds = [(a, a + DAY) for a in midnights]
    p = build_profile(starts, ends, apps, prof["f"].to_numpy(), prof["s"].to_numpy(), bounds, offset_s)
    out = {k: (v.tolist() if isinstance(v, np.ndarray) else v) for k, v in p.__dict__.items()}
    out.update({"profile_days": [str(d) for d in days], "offset_s": offset_s, "n_profile_points": len(prof)})
    return out


def main() -> None:
    people = pick_people()
    models = {p: MergedModel.load(Path("data/models") / p) for p in ("F", "S")}
    dates = ["start_utc", "start_local", "end_utc"]
    epochs = pd.read_csv("data/processed/mpud_preprocess_2.csv")
    sessions = pd.read_csv("data/processed/mpud_preprocess_3.csv", parse_dates=dates)
    points = {b: pd.read_csv(f"data/processed/mpud_windows_points{'' if b == 0 else '_b30'}.csv",
                             parse_dates=["t_utc", "t_local"]) for b in (0, 30)}
    feats = {"F": pd.concat([pd.read_csv("data/processed/mpud_features.csv"),
                             pd.read_csv("data/processed/mpud_features_dynamics.csv").drop(columns=["user_id", "t_utc"])],
                            axis=1),
             "S": pd.read_csv("data/processed/mpud_features_b30.csv")}
    for user in people:
        folder = OUT / user
        folder.mkdir(parents=True, exist_ok=True)
        epochs[epochs["user_id"] == user].to_csv(folder / "epochs.csv", index=False)
        s = sessions[sessions["user_id"] == user]
        s.assign(**{c: s[c].dt.strftime("%Y-%m-%d %H:%M:%S.%f").str[:-3] for c in dates}).to_csv(
            folder / "sessions.csv", index=False)
        for b, pat in ((0, "F"), (30, "S")):
            pts = points[b][points[b]["user_id"] == user]
            pts.assign(t_utc=pts["t_utc"].dt.strftime("%Y-%m-%d %H:%M:%S.%f").str[:-3],
                       t_local=pts["t_local"].dt.strftime("%Y-%m-%d %H:%M:%S.%f").str[:-3]).to_csv(
                folder / f"points_b{b}.csv", index=False)
            (folder / f"profile_{pat}.json").write_text(json.dumps(profile_dict(user, s, pts), indent=2))
            m = models[pat]
            rows = feats[pat][feats[pat]["user_id"] == user]
            score = m.score(rows[m.features].to_numpy(dtype=np.float32))
            out = rows[["t_utc", "is_unlock"]].copy()
            out = pd.concat([out.reset_index(drop=True), rows[m.features].reset_index(drop=True)], axis=1)
            out["expected_score"], out["expected_fires"] = score, score > m.cutoff
            out.to_csv(folder / f"vectors_{pat}.csv", index=False)
        print(f"{user}: {len(s)} sessions, {len(feats['F'][feats['F']['user_id'] == user])} F examples, "
              f"{len(feats['S'][feats['S']['user_id'] == user])} S examples -> {folder}")
    (OUT / "README.txt").write_text("Golden fixtures for the SmartUse feature extractor. See README.md, section 9.\n"
                                    "Source: MPUD (public dataset). People: " + ", ".join(people) + "\n")


if __name__ == "__main__":
    main()
