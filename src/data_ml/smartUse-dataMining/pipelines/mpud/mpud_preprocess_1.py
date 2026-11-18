"""MPUD phase 1: timezone.

Input: data/usage/mpud.csv (uuid, ts_utc, ts_phone, sensor_id, value).

The whole MPUD period (April-July 2016) falls in Spanish summer time, so
every event should have ts_phone - ts_utc = +2 h. For each event, the offset
is that difference rounded to 15 min. Then:

  1. Users with a stretch of at least TRAVEL_MIN_HOURS at an offset other
     than +2 h (travel, or living in another zone) are removed.
  2. In the remaining users, events whose offset is not +2 h are isolated
     glitches where ts_utc (the source's ts_raw) is wrong and ts_phone fits
     the surrounding events. Their ts_utc is rebuilt as ts_phone - 2 h, to
     the second, and flagged in `ts_rebuilt`.
  3. ts_local = ts_utc + 2 h. Rows are re-sorted by ts_utc per user.

Output: data/processed/mpud_preprocess_1.csv
(uuid, ts_utc, ts_local, sensor_id, value, ts_rebuilt).

    python pipelines/mpud/mpud_preprocess_1.py [in_csv] [out_csv]
"""

import sys
from pathlib import Path

import pandas as pd

IN_CSV = Path("data/usage/mpud.csv")
OUT_CSV = Path("data/processed/mpud_preprocess_1.csv")

SPAIN_OFFSET = pd.Timedelta(hours=2)
TRAVEL_MIN_HOURS = 1.0
OUT_COLUMNS = ["uuid", "ts_utc", "ts_local", "sensor_id", "value", "ts_rebuilt"]
TS_FORMAT = "%Y-%m-%d %H:%M:%S.%f"


def offset_minutes(events: pd.DataFrame) -> pd.Series:
    """ts_phone - ts_utc, rounded to 15 min."""
    seconds = (events["ts_phone"] - events["ts_utc"]).dt.total_seconds()
    return (seconds / 900).round().astype(int) * 15


def travel_users(events: pd.DataFrame, offset: pd.Series) -> pd.DataFrame:
    """Users with a run of consecutive events at one non-Spain offset lasting >= TRAVEL_MIN_HOURS."""
    new_run = (offset != offset.shift()) | (events["uuid"] != events["uuid"].shift())
    runs = events.assign(offset=offset, run=new_run.cumsum())
    runs = runs[runs["offset"] != SPAIN_OFFSET.total_seconds() / 60]
    spans = runs.groupby("run").agg(
        uuid=("uuid", "first"),
        offset=("offset", "first"),
        rows=("ts_utc", "size"),
        hours=("ts_utc", lambda s: (s.max() - s.min()).total_seconds() / 3600),
    )
    long = spans[spans["hours"] >= TRAVEL_MIN_HOURS]
    return long.groupby("uuid").agg(
        rows=("rows", "sum"),
        hours=("hours", "sum"),
        offsets=("offset", lambda s: sorted(set(s))),
    )


def main(in_csv: Path = IN_CSV, out_csv: Path = OUT_CSV) -> None:
    events = pd.read_csv(in_csv, dtype={"value": str}, keep_default_na=False)
    events["ts_utc"] = pd.to_datetime(events["ts_utc"])
    events["ts_phone"] = pd.to_datetime(events["ts_phone"])
    users_in = events["uuid"].nunique()

    travel = travel_users(events, offset_minutes(events))
    events = events[~events["uuid"].isin(travel.index)].copy()

    glitch = offset_minutes(events) != SPAIN_OFFSET.total_seconds() / 60
    events.loc[glitch, "ts_utc"] = events.loc[glitch, "ts_phone"] - SPAIN_OFFSET
    events["ts_rebuilt"] = glitch
    events["ts_local"] = events["ts_utc"] + SPAIN_OFFSET
    events = events.sort_values(["uuid", "ts_utc"], kind="stable")

    for column in ("ts_utc", "ts_local"):
        events[column] = events[column].dt.strftime(TS_FORMAT).str[:-3]
    out_csv.parent.mkdir(parents=True, exist_ok=True)
    events[OUT_COLUMNS].to_csv(out_csv, index=False)

    print(f"users in: {users_in}")
    print(f"removed, another timezone for >= {TRAVEL_MIN_HOURS:g} h: {len(travel)}")
    print(travel.to_string())
    print(f"users out: {events['uuid'].nunique()}")
    rebuilt = events[events["ts_rebuilt"]]
    print(f"rebuilt ts_utc: {len(rebuilt)} events in {rebuilt['uuid'].nunique()} users "
          f"{rebuilt['sensor_id'].value_counts().to_dict()}")
    print(f"users with every event at +2 h: {events['uuid'].nunique() - rebuilt['uuid'].nunique()}")
    print(f"{len(events)} events -> {out_csv}")


if __name__ == "__main__":
    main(*[Path(a) for a in sys.argv[1:3]])
