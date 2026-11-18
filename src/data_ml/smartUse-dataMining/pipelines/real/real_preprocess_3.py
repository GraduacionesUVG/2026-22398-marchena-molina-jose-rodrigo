"""Real data phase 3: sessions.

Input: data/processed/real_preprocess_2.csv.

Rules from README §4.6 (Siebers et al., 2023), in this order:

  1. A use epoch counts toward a session only with at least MIN_APPS apps.
     A use epoch with 0 apps is a screen-on with no foreground activity (lock
     screen or notification shade, which emit no ACTIVITY_RESUMED): it is
     treated as non-use. Epochs with 1-2 apps are kept.
  2. Use epochs whose gap to the end of the person's previous use epoch is
     shorter than SESSION_GAP_S are joined into one session. With a gap of 0
     nothing is joined.

Per session:
  - start_utc / start_local / client_record_id: those of the first epoch
  - end_utc: end of the last epoch (its start + duration_s)
  - duration_s: end_utc - start_utc, including the joined short gaps
  - n_epochs: use epochs joined
  - app_count_max: sum of the epochs' distinct_app_count. Repeated apps
    across epochs cannot be identified, so it is an upper bound.
  - merged_markers: sum over the epochs (repeated screen-on markers, an
    input for the missing-close heuristic, E6)
  - no_close: duration_s > T_MAX_H hours. Almost always a screen-off that
    was never logged (in MPUD, sessions over 3 h are a median of 13 min of
    use around hours with no app). Windows that overlap it get no label.
    The same rule runs in deployment: inference stops once the current
    session passes T_MAX_H and resumes at the next screen change. MPUD
    tunes T_MAX_H around 3 h in E6.

Output: data/processed/real_preprocess_3.csv
(client_record_id, user_id, start_utc, start_local, end_utc, duration_s,
n_epochs, app_count_max, merged_markers, no_close).

    python pipelines/real/real_preprocess_3.py [in_csv] [out_csv] [session_gap_s] [t_max_h]
"""

import sys
from pathlib import Path

import pandas as pd

IN_CSV = Path("data/processed/real_preprocess_2.csv")
OUT_CSV = Path("data/processed/real_preprocess_3.csv")

MIN_APPS = 1
SESSION_GAP_S = 30.0
T_MAX_H = 3.0
OUT_COLUMNS = ["client_record_id", "user_id", "start_utc", "start_local", "end_utc", "duration_s",
               "n_epochs", "app_count_max", "merged_markers", "no_close"]
TS_FORMAT = "%Y-%m-%d %H:%M:%S.%f"


def main(in_csv: Path = IN_CSV, out_csv: Path = OUT_CSV, session_gap_s: float = SESSION_GAP_S,
         t_max_h: float = T_MAX_H) -> None:
    epochs = pd.read_csv(in_csv, dtype={"start_utc": str, "start_local": str})
    zero_app = epochs["is_use"] & (epochs["distinct_app_count"] < MIN_APPS)
    use = epochs[epochs["is_use"] & ~zero_app].copy()

    use["start"] = pd.to_datetime(use["start_utc"])
    use["end"] = use["start"] + pd.to_timedelta(use["duration_s"], unit="s")
    gap_s = (use["start"] - use.groupby("user_id")["end"].shift()).dt.total_seconds()
    new_session = ~(gap_s < session_gap_s)  # NaN (first epoch of a person) starts a session
    sessions = use.groupby(new_session.cumsum()).agg(
        client_record_id=("client_record_id", "first"),
        user_id=("user_id", "first"),
        start_utc=("start_utc", "first"),
        start_local=("start_local", "first"),
        start=("start", "first"),
        end=("end", "last"),
        n_epochs=("is_use", "size"),
        app_count_max=("distinct_app_count", "sum"),
        merged_markers=("merged_markers", "sum"),
    ).reset_index(drop=True)
    sessions["duration_s"] = (sessions["end"] - sessions["start"]).dt.total_seconds().round(3)
    sessions["end_utc"] = sessions["end"].dt.strftime(TS_FORMAT).str[:-3]
    sessions["no_close"] = sessions["duration_s"] > t_max_h * 3600

    out_csv.parent.mkdir(parents=True, exist_ok=True)
    sessions[OUT_COLUMNS].to_csv(out_csv, index=False)

    joined = sessions[sessions["n_epochs"] > 1]
    print(f"use epochs in: {epochs['is_use'].sum()}; with 0 apps, now non-use: {zero_app.sum()}")
    print(f"gap < {session_gap_s:g} s: {len(joined)} sessions join {joined['n_epochs'].sum()} epochs")
    print(f"no_close (> {t_max_h:g} h): {sessions['no_close'].sum()} sessions")
    print(f"{len(sessions)} sessions from {sessions['user_id'].nunique()} users; "
          f"median duration {sessions['duration_s'].median():.1f} s -> {out_csv}")


if __name__ == "__main__":
    args = sys.argv[1:]
    paths = [Path(a) for a in args[:2]]
    numbers = [float(a) for a in args[2:4]]
    main(*paths, *numbers)
