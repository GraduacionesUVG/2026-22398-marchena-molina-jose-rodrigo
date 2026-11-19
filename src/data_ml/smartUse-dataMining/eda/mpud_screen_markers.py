"""Describe MPUD screen markers and app events before converting to epochs.

Input: data/processed/mpud_preprocess_1.csv.

1. Transitions between consecutive Screen markers of each person: count,
   users, median gap and share of gaps under several thresholds. Separates
   near-instant duplicates (same marker logged twice) from repeats with real
   time in between.
2. App events: package prefixes (+ launcher/home, - system, = keyboard, as
   they appear in MPUD), the most frequent packages, and the screen state
   (last Screen marker) at each App event; for App events while the screen
   is off, the time since that Off.

    python eda/mpud_screen_markers.py
"""

from pathlib import Path

import pandas as pd

IN_CSV = Path("data/processed/mpud_preprocess_1.csv")
THRESHOLDS_S = [0.01, 0.1, 1, 10, 60]
TRANSITIONS = [("On", "On"), ("Off", "Off"), ("Unlocked", "Unlocked"),
               ("Off", "Unlocked"), ("Unlocked", "On"),
               ("Off", "On"), ("On", "Unlocked"), ("On", "Off"), ("Unlocked", "Off")]
PREFIXES = {"+": "launcher/home", "-": "system", "=": "keyboard"}


def screen_transitions(events: pd.DataFrame) -> pd.DataFrame:
    screen = events[events["sensor_id"] == "Screen"].copy()
    by_user = screen.groupby("uuid")
    screen["prev"] = by_user["value"].shift()
    screen["gap_s"] = by_user["ts_utc"].diff().dt.total_seconds()
    rows = []
    for before, after in TRANSITIONS:
        pairs = screen[(screen["prev"] == before) & (screen["value"] == after)]
        row = {"transition": f"{before} -> {after}", "n": len(pairs), "users": pairs["uuid"].nunique(),
               "median_gap_s": round(pairs["gap_s"].median(), 2)}
        row.update({f"<{t:g}s": round((pairs["gap_s"] < t).mean(), 2) for t in THRESHOLDS_S})
        rows.append(row)
    return pd.DataFrame(rows)


def app_summary(events: pd.DataFrame) -> None:
    apps = events.loc[events["sensor_id"] == "App", "value"]
    prefix = apps.str[0].map(PREFIXES).fillna("none")
    print(f"App events: {len(apps)}; distinct values: {apps.nunique()}; "
          f"distinct packages without prefix: {apps.str.lstrip('+-=').nunique()}")
    print("by prefix:", prefix.value_counts().to_dict())
    for symbol, name in PREFIXES.items():
        packages = apps[apps.str.startswith(symbol)].str[1:].value_counts()
        print(f"  {symbol} ({name}), {len(packages)} packages:", packages.head(6).to_dict())
    print("top unprefixed:", apps[prefix == "none"].value_counts().head(10).to_dict())

    both = events[events["sensor_id"].isin(["Screen", "App"])].copy()
    both["state"] = both["value"].where(both["sensor_id"] == "Screen")
    both["state_ts"] = both["ts_utc"].where(both["sensor_id"] == "Screen")
    both[["state", "state_ts"]] = both.groupby("uuid")[["state", "state_ts"]].ffill()
    app_rows = both[both["sensor_id"] == "App"]
    print("screen state at each App event:", app_rows["state"].value_counts(dropna=False).to_dict())
    off = app_rows[app_rows["state"] == "Off"]
    since_off = (off["ts_utc"] - off["state_ts"]).dt.total_seconds()
    print(f"App events while off ({len(off)}), seconds since the Off:",
          since_off.describe(percentiles=[0.25, 0.5, 0.75, 0.9]).round(2).to_dict())


def main() -> None:
    events = pd.read_csv(IN_CSV, dtype={"value": str}, keep_default_na=False, parse_dates=["ts_utc"])
    print("== Screen transitions ==")
    print(screen_transitions(events).to_string(index=False))
    print("\n== App events ==")
    app_summary(events)


if __name__ == "__main__":
    main()
