"""Mask the final-send CSVs to the inference-group users in users.csv.

The raw exports also contain test users and control users; only the inference
group ran the models and saw the interventions.

Usage:  python plots/clean_final_answers.py
Output: data/deployment_data/final_answers_inference.csv
        data/deployment_data/final_metrics_inference.csv
"""
from pathlib import Path

import pandas as pd

DATA = Path(__file__).resolve().parents[1] / "data" / "deployment_data"
SOURCES = ["final_answers", "final_metrics"]


def main() -> None:
    users = pd.read_csv(DATA / "users.csv", encoding="utf-8-sig")
    inference = set(users.loc[users.experiment_group == "inference", "user_id"])
    for name in SOURCES:
        raw = pd.read_csv(DATA / f"{name}.csv", encoding="utf-8-sig")
        kept = raw[raw.user_id.isin(inference)]
        dst = DATA / f"{name}_inference.csv"
        kept.to_csv(dst, index=False, encoding="utf-8")
        print(f"{name}: {raw.user_id.nunique()} users / {len(raw)} rows -> "
              f"{kept.user_id.nunique()} users / {len(kept)} rows  ({dst.name})")


if __name__ == "__main__":
    main()
