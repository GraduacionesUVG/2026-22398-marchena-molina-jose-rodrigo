"""MPUD phase 2: events to epochs, in the real data's schema.

Input: data/processed/mpud_preprocess_1.csv
(uuid, ts_utc, ts_local, sensor_id, value, ts_rebuilt).

Screen markers become epoch starts:
  - use: an On, or an Unlocked right after an Off (an unlock with no logged
    screen-on acts as the screen-on)
  - non-use: an Off
  - any other Unlocked is inside the open use epoch and starts nothing
Consecutive starts of the same kind (use -> use, non-use -> non-use) are
merged into one epoch, the same rule as the real data: the epoch keeps the
first start and merged_markers counts the starts it absorbed. This also
absorbs duplicate markers and an Unlocked logged just before its On.

Each epoch ends where the person's next epoch starts, so duration_s is the
time to it. Each person's last epoch has no known end and is dropped.

distinct_app_count of a use epoch: distinct App values in [start, end), with
the MPUD category prefix (+ launcher, - system, = keyboard) stripped. Not
counted:
  - MPUD's own logger (org.pielot.sns2)
  - the notification panel (com.android.systemui)
  - keyguard and lock screens (com.android.keyguard, OEM and third-party
    lock screens)
  - keyboards: any package ever logged with the = prefix, or with
    "inputmethod" in its name
Launchers count, and so does everything else (including the `android`
system dialogs and `null`). Non-use epochs have 0, as in the real data.
Notification events are not used.

unlocked: a use epoch with at least one Unlocked (as its start or inside it,
any part of a merged chain). The real data has no such signal; it is kept
to measure the error of the at-least-one-app rule (E12). Non-use: False.

Output: data/processed/mpud_preprocess_2.csv
(user_id, start_utc, start_local, is_use, unlocked, distinct_app_count,
merged_markers, duration_s).

    python pipelines/mpud/mpud_preprocess_2.py [in_csv] [out_csv]
"""

import sys
from pathlib import Path

import pandas as pd

IN_CSV = Path("data/processed/mpud_preprocess_1.csv")
OUT_CSV = Path("data/processed/mpud_preprocess_2.csv")

EXCLUDED_PACKAGES = {
    "org.pielot.sns2",                    # MPUD's logger
    "com.android.systemui",               # notification panel
    "com.android.keyguard",
    "com.htc.lockscreen",
    "com.sonyericsson.lockscreen.uxpnxt",
    "com.nbbsw.leatherlockscreen",
    "com.keypad.bubble.lockscreen",
}
KEYBOARD_PREFIX = "="
OUT_COLUMNS = ["user_id", "start_utc", "start_local", "is_use", "unlocked",
               "distinct_app_count", "merged_markers", "duration_s"]
TS_FORMAT = "%Y-%m-%d %H:%M:%S.%f"


def epoch_starts(events: pd.DataFrame) -> pd.DataFrame:
    """Merged epoch starts per person: uuid, ts_utc, ts_local, is_use, merged_markers."""
    screen = events[events["sensor_id"] == "Screen"]
    prev = screen.groupby("uuid")["value"].shift()
    starts_use = (screen["value"] == "On") | ((screen["value"] == "Unlocked") & (prev == "Off"))
    starts = screen[starts_use | (screen["value"] == "Off")].assign(is_use=starts_use)
    new_epoch = (starts["is_use"] != starts.groupby("uuid")["is_use"].shift()) | (
        starts["uuid"] != starts["uuid"].shift()
    )
    return starts.groupby(new_epoch.cumsum()).agg(
        uuid=("uuid", "first"),
        ts_utc=("ts_utc", "first"),
        ts_local=("ts_local", "first"),
        is_use=("is_use", "first"),
        merged_markers=("is_use", "size"),
    ).reset_index(drop=True)


def counted_apps(events: pd.DataFrame) -> pd.DataFrame:
    """App events that count as apps: uuid, ts_utc, value, package (prefix stripped)."""
    apps = events[events["sensor_id"] == "App"][["uuid", "ts_utc", "value"]]
    apps = apps.assign(package=apps["value"].str.lstrip("+-="))
    keyboards = set(apps.loc[apps["value"].str.startswith(KEYBOARD_PREFIX), "package"])
    excluded = apps["package"].isin(EXCLUDED_PACKAGES | keyboards) \
        | apps["package"].str.contains("inputmethod", regex=False)
    return apps[~excluded]


def app_counts(events: pd.DataFrame, epochs: pd.DataFrame) -> pd.Series:
    """Distinct counted packages per use epoch; 0 for non-use."""
    apps = counted_apps(events)
    owner = epochs[["uuid", "ts_utc"]].assign(epoch=epochs.index)
    apps = pd.merge_asof(apps.sort_values("ts_utc"), owner.sort_values("ts_utc"),
                         on="ts_utc", by="uuid", direction="backward").dropna(subset=["epoch"])
    counts = apps.groupby("epoch")["package"].nunique().reindex(epochs.index, fill_value=0)
    return counts.where(epochs["is_use"], 0).astype(int)


def unlocked(events: pd.DataFrame, epochs: pd.DataFrame) -> pd.Series:
    """True for use epochs with at least one Unlocked inside [start, end)."""
    unlocks = events[(events["sensor_id"] == "Screen") & (events["value"] == "Unlocked")][["uuid", "ts_utc"]]
    owner = epochs[["uuid", "ts_utc"]].assign(epoch=epochs.index)
    unlocks = pd.merge_asof(unlocks.sort_values("ts_utc"), owner.sort_values("ts_utc"),
                            on="ts_utc", by="uuid", direction="backward").dropna(subset=["epoch"])
    has_unlock = pd.Series(epochs.index.isin(unlocks["epoch"].astype(int)), index=epochs.index)
    return has_unlock & epochs["is_use"]


def main(in_csv: Path = IN_CSV, out_csv: Path = OUT_CSV) -> None:
    events = pd.read_csv(in_csv, usecols=["uuid", "ts_utc", "ts_local", "sensor_id", "value"],
                         dtype={"value": str}, keep_default_na=False, parse_dates=["ts_utc", "ts_local"])
    epochs = epoch_starts(events)
    epochs["distinct_app_count"] = app_counts(events, epochs)
    epochs["unlocked"] = unlocked(events, epochs)
    epochs["duration_s"] = (epochs.groupby("uuid")["ts_utc"].shift(-1) - epochs["ts_utc"]).dt.total_seconds().round(3)
    last = epochs["duration_s"].isna()
    epochs = epochs[~last].rename(columns={"uuid": "user_id", "ts_utc": "start_utc", "ts_local": "start_local"})

    for column in ("start_utc", "start_local"):
        epochs[column] = epochs[column].dt.strftime(TS_FORMAT).str[:-3]
    out_csv.parent.mkdir(parents=True, exist_ok=True)
    epochs[OUT_COLUMNS].to_csv(out_csv, index=False)

    merged = epochs[epochs["merged_markers"] > 1]
    print(f"merged epochs: {len(merged)} ({merged['is_use'].sum()} use, {(~merged['is_use']).sum()} non-use) "
          f"in {merged['user_id'].nunique()} users; last epoch of each person dropped: {last.sum()}")
    print(f"{len(epochs)} epochs ({epochs['is_use'].sum()} use, {epochs['unlocked'].sum()} unlocked) "
          f"from {epochs['user_id'].nunique()} users -> {out_csv}")


if __name__ == "__main__":
    main(*[Path(a) for a in sys.argv[1:3]])
