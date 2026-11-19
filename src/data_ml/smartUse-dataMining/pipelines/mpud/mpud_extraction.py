"""Merge the per-user MPUD CSVs into a single event file.

Keeps only the App, Screen, Notif and NotifDismissed sensors and projects
each sensor's value column into a single `value` column. Each user's rows
are sorted by ts_utc (stable, so ties keep the source order); files are
processed in name order.

Both timestamps of the source are kept, unchanged in meaning:
  ts_utc   <- `ts_raw`: UTC, epoch ms (written with millisecond precision)
  ts_phone <- `ts`: the phone's local clock, truncated to the second
Neither is corrected here; the timezone phase decides which one to trust.

    python pipelines/mpud/mpud_extraction.py [raw_dir] [out_csv]
"""

import sys
from pathlib import Path

import pandas as pd

RAW_DIR = Path("data/mpud_raw")
OUT_CSV = Path("data/usage/mpud.csv")

# sensor_id -> column holding that sensor's value
VALUE_COLUMNS = {
    "App": "App_Value",
    "Screen": "Screen_Value",
    "Notif": "Notif_Action",
    "NotifDismissed": "NotifDismissed_Value",
}
OUT_COLUMNS = ["uuid", "ts_utc", "ts_phone", "sensor_id", "value"]


def extract_user(path: Path) -> pd.DataFrame:
    """Return one user's events as uuid, ts_utc, ts_phone, sensor_id, value."""
    raw = pd.read_csv(
        path,
        usecols=["uuid", "ts", "ts_raw", "sensor_id", *VALUE_COLUMNS.values()],
        dtype=str,
        keep_default_na=False,
    )
    raw = raw[raw["sensor_id"].isin(VALUE_COLUMNS)]
    value = pd.Series("", index=raw.index)
    for sensor, column in VALUE_COLUMNS.items():
        mask = raw["sensor_id"] == sensor
        value[mask] = raw.loc[mask, column]

    utc = pd.to_datetime(raw["ts_raw"].astype("int64"), unit="ms")
    events = pd.DataFrame(
        {
            "uuid": raw["uuid"],
            "ts_utc": utc,
            "ts_phone": raw["ts"],
            "sensor_id": raw["sensor_id"],
            "value": value,
        }
    ).sort_values("ts_utc", kind="stable")
    events["ts_utc"] = events["ts_utc"].dt.strftime("%Y-%m-%d %H:%M:%S.%f").str[:-3]
    return events[OUT_COLUMNS]


def main(raw_dir: Path = RAW_DIR, out_csv: Path = OUT_CSV) -> None:
    files = sorted(raw_dir.glob("*.csv"))
    if not files:
        raise SystemExit(f"no CSV files in {raw_dir}")
    out_csv.parent.mkdir(parents=True, exist_ok=True)
    rows = 0
    for i, path in enumerate(files):
        events = extract_user(path)
        events.to_csv(out_csv, mode="w" if i == 0 else "a", header=i == 0, index=False)
        rows += len(events)
        print(f"[{i + 1}/{len(files)}] {path.name}: {len(events)} events")
    print(f"{rows} events from {len(files)} users -> {out_csv}")


if __name__ == "__main__":
    args = [Path(a) for a in sys.argv[1:3]]
    main(*args)
