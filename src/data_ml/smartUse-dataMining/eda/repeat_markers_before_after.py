"""Marker transitions before and after phase 2 (merging consecutive markers of the same kind).

Inputs:
  MPUD: data/processed/mpud_preprocess_1.csv (before) and
        data/processed/mpud_preprocess_2.csv (after)
  Real: data/processed/real_preprocess_1.csv (before) and
        data/processed/real_preprocess_2.csv (after)

For each transition between consecutive markers of a person: count, users
and the gap distribution, before and after. Phase 2 merges every run of
same-kind starts into one epoch, which absorbs the near-instant duplicates
and the MPUD unlock/screen-on race that earlier versions handled with
separate rules (README §4.4).

    python eda/repeat_markers_before_after.py
"""

from pathlib import Path

import pandas as pd

from mpud_screen_markers import TRANSITIONS, screen_transitions

MPUD = (Path("data/processed/mpud_preprocess_1.csv"), Path("data/processed/mpud_preprocess_2.csv"))
REAL = (Path("data/processed/real_preprocess_1.csv"), Path("data/processed/real_preprocess_2.csv"))
REAL_TRANSITIONS = [(True, True), (False, False), (False, True), (True, False)]
LABEL = {True: "use", False: "non-use"}


def mpud_table(path: Path) -> pd.DataFrame:
    events = pd.read_csv(path, usecols=["uuid", "ts_utc", "sensor_id", "value"], dtype={"value": str},
                         keep_default_na=False, parse_dates=["ts_utc"])
    return screen_transitions(events).set_index("transition")


def real_table(path: Path) -> pd.DataFrame:
    markers = pd.read_csv(path, parse_dates=["start_utc"])
    by_user = markers.groupby("user_id")
    markers["prev"] = by_user["is_use"].shift()
    markers["gap_s"] = by_user["start_utc"].diff().dt.total_seconds()
    rows = []
    for before, after in REAL_TRANSITIONS:
        pairs = markers[(markers["prev"] == before) & (markers["is_use"] == after)]
        rows.append({"transition": f"{LABEL[before]} -> {LABEL[after]}", "n": len(pairs),
                     "users": pairs["user_id"].nunique(), "median_gap_s": round(pairs["gap_s"].median(), 2),
                     "<1s": round((pairs["gap_s"] < 1).mean(), 2)})
    return pd.DataFrame(rows).set_index("transition")


def compare(before: pd.DataFrame, after: pd.DataFrame, gap_columns: list[str]) -> pd.DataFrame:
    table = pd.DataFrame({
        "n_before": before["n"], "n_after": after["n"],
        "removed": before["n"] - after["n"],
        "users_before": before["users"], "users_after": after["users"],
    })
    for column in gap_columns:
        table[f"after {column}"] = after[column]
    return table


def main() -> None:
    gaps = ["median_gap_s", "<1s", "<10s", "<60s"]
    print("== MPUD Screen transitions (phase 1 -> phase 2) ==")
    print(compare(mpud_table(MPUD[0]), mpud_table(MPUD[1]), gaps).to_string())
    print("\n== Real data transitions (phase 1 -> phase 2) ==")
    print(compare(real_table(REAL[0]), real_table(REAL[1]), ["median_gap_s", "<1s"]).to_string())


if __name__ == "__main__":
    main()
