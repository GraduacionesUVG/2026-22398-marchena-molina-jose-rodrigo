"""Real data phase 1: timezone.

Input: data/usage/real_data.csv, with `start_ts` in UTC.

Participants are in Guatemala (UTC-6, no daylight saving), so local time is
a fixed -6 h. `start_ts` is renamed to `start_utc` and `start_local` is
added. Rows are sorted by start_utc per user.

People in EXCLUDED_USERS are removed: too little data to build a profile
and still leave days to evaluate on. They are listed by pseudonym ("p" + the
first 8 hex of the SHA-256 of the id) so no participant id is published.

Output: data/processed/real_preprocess_1.csv
(client_record_id, user_id, start_utc, start_local, distinct_app_count,
granularity, is_use).

    python pipelines/real/real_preprocess_1.py [in_csv] [out_csv]
"""

import hashlib
import sys
from pathlib import Path

import pandas as pd

IN_CSV = Path("data/usage/real_data.csv")
OUT_CSV = Path("data/processed/real_preprocess_1.csv")

GUATEMALA_OFFSET = pd.Timedelta(hours=-6)
OUT_COLUMNS = ["client_record_id", "user_id", "start_utc", "start_local",
               "distinct_app_count", "granularity", "is_use"]
TS_FORMAT = "%Y-%m-%d %H:%M:%S.%f"

# pseudonym -> reason
EXCLUDED_USERS = {
    "pe93bcf45": "about 2 days of data, 34 sessions",
}


def pseudonym(user_id: str) -> str:
    return "p" + hashlib.sha256(str(user_id).encode()).hexdigest()[:8]


def main(in_csv: Path = IN_CSV, out_csv: Path = OUT_CSV) -> None:
    epochs = pd.read_csv(in_csv)
    excluded = epochs["user_id"].map(pseudonym).isin(EXCLUDED_USERS)
    epochs = epochs[~excluded].copy()
    epochs["start_utc"] = pd.to_datetime(epochs["start_ts"], format="ISO8601")
    epochs["start_local"] = epochs["start_utc"] + GUATEMALA_OFFSET
    epochs = epochs.sort_values(["user_id", "start_utc"], kind="stable")

    for column in ("start_utc", "start_local"):
        epochs[column] = epochs[column].dt.strftime(TS_FORMAT).str[:-3]
    out_csv.parent.mkdir(parents=True, exist_ok=True)
    epochs[OUT_COLUMNS].to_csv(out_csv, index=False)
    print(f"excluded: {excluded.sum()} epochs from {len(EXCLUDED_USERS)} users")
    print(f"{len(epochs)} epochs from {epochs['user_id'].nunique()} users -> {out_csv}")


if __name__ == "__main__":
    main(*[Path(a) for a in sys.argv[1:3]])
