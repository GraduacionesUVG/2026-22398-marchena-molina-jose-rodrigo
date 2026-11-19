"""F1 of the production F and S models with leave-one-person-out (one fold per person).

Uses the out-of-fold scores already cached by E8d (F: y_f, b = 0, pruned +
all dynamics) and E4b (S: y_s, b = 30, pruned): same training as
experiments/train_final.py, but each person scored by models that never saw
them. The 5 seeds are merged as in production (mean margin).

Point-level F1 (score above the cutoff vs the label, no episode rule) per held-out
person, under three cutoffs:
  - production: the calibrator and cutoff of data/models/production.json
    (chosen on these same pooled scores, so slightly optimistic)
  - nested_rule: the E9b rule (precision floor, daily cap) rerun on the other 17
  - nested_f1: the cutoff maximising pooled F1 on the other 17
Baselines: persistence (the past hour's F or S above the profile) and always positive.

    python -m experiments.f1_loso
"""

import json
from pathlib import Path

import numpy as np
import pandas as pd

from experiments import calibration as C
from experiments import e9b as E
from pipelines.labels.build_windows import seconds

SEEDS = range(5)
KEEP = ["user_id", "t_utc", "day", "f_60", "s_60", "prof_pf", "prof_ps"]
OUT = Path("data/reports/f1_loso.json")


def load(pat: str) -> pd.DataFrame:
    if pat == "F":
        df = pd.read_csv("data/processed/real_features.csv", usecols=KEEP + ["y_f"], parse_dates=["day"])
        sc = pd.read_parquet("data/reports/e8d_scores.parquet")
        per_seed = {s: sc[f"y_f|+ all dynamics|{s}"].to_numpy() for s in SEEDS}
        df["y"], df["persist"] = df["y_f"].astype(int), (df["f_60"] > df["prof_pf"]).astype(int)
    else:
        df = pd.read_csv("data/processed/real_features_b30.csv", usecols=KEEP + ["y_s"], parse_dates=["day"])
        sc = pd.read_parquet("data/reports/e4b_scores_b30.parquet")
        per_seed = {s: sc[f"y_s|{s}"].to_numpy() for s in SEEDS}
        df["y"], df["persist"] = df["y_s"].astype(int), (df["s_60"] > df["prof_ps"]).astype(int)
    assert len(df) == len(sc)
    df["raw"] = C.merge_probs(per_seed)
    df["t"] = seconds(pd.to_datetime(df["t_utc"]))
    return df.sort_values(["user_id", "t"]).reset_index(drop=True)


def prf(y: np.ndarray, pred: np.ndarray) -> dict:
    tp, fp, fn = int((y & pred).sum()), int((~y & pred).sum()), int((y & ~pred).sum())
    p = tp / (tp + fp) if tp + fp else np.nan
    r = tp / (tp + fn) if tp + fn else np.nan
    f1 = 2 * tp / (2 * tp + fp + fn) if tp + fn else np.nan  # undefined without positives
    return {"f1": f1, "precision": p, "recall": r, "tp": tp, "fp": fp, "fn": fn}


def best_f1_cut(raw: np.ndarray, y: np.ndarray) -> float:
    cuts = np.unique(np.quantile(raw, np.linspace(0.01, 0.99, 197)))
    return float(max(cuts, key=lambda c: prf(y, raw > c)["f1"]))


def rule_cut(train: pd.DataFrame, pat: str) -> float:
    d = train.drop(columns=["y_f", "y_s"], errors="ignore").rename(columns={"raw": "p_f" if pat == "F" else "p_s", "y": "y_f" if pat == "F" else "y_s"})
    d["p_s" if pat == "F" else "p_f"] = 0.0
    cuts = np.unique(np.quantile(d["p_f" if pat == "F" else "p_s"], np.linspace(0.30, 0.995, 80)))
    return E.choose(E.sweep(d, pat, cuts))[0]


def production_cut_on_raw(model: dict, raw: np.ndarray) -> np.ndarray:
    cal = model["calibrator"]
    p = raw
    if cal["method"] == "platt":
        p = C.sigmoid(cal["a"] * C.logit(raw) + cal["b"])
    return p > model["cutoff"]


def run(pat: str, prod: dict) -> pd.DataFrame:
    df = load(pat)
    y = df["y"].to_numpy().astype(bool)
    preds = {"production": production_cut_on_raw(prod, df["raw"].to_numpy()),
             "persistence": df["persist"].to_numpy().astype(bool),
             "always_positive": np.ones(len(df), dtype=bool)}
    for name in ("nested_rule", "nested_f1"):
        preds[name] = np.zeros(len(df), dtype=bool)
    users = df["user_id"].to_numpy()
    for u in np.unique(users):
        te, tr = users == u, users != u
        c_f1 = best_f1_cut(df["raw"].to_numpy()[tr], y[tr])
        c_rule = rule_cut(df[tr].reset_index(drop=True), pat)
        preds["nested_f1"][te] = df["raw"].to_numpy()[te] > c_f1
        preds["nested_rule"][te] = df["raw"].to_numpy()[te] > c_rule
    rows = []
    for name, pred in preds.items():
        for u in np.unique(users):
            m = users == u
            rows.append({"pattern": pat, "cutoff": name, "user_id": u, "n": int(m.sum()),
                         "prevalence": float(y[m].mean()), **prf(y[m], pred[m])})
        rows.append({"pattern": pat, "cutoff": name, "user_id": "POOLED", "n": len(df),
                     "prevalence": float(y.mean()), **prf(y, pred)})
    return pd.DataFrame(rows)


def main() -> None:
    prod = json.loads(Path("data/models/production.json").read_text())["models"]
    prod = {p: {"cutoff": prod[p]["cutoff"],
                "calibrator": json.loads(Path(f"data/models/{p}/model.json").read_text())["calibrator"]}
            for p in ("F", "S")}
    res = pd.concat([run(p, prod[p]) for p in ("F", "S")], ignore_index=True)
    people = res[res["user_id"] != "POOLED"]
    order = ["production", "nested_rule", "nested_f1", "persistence", "always_positive"]
    summary = (people.groupby(["pattern", "cutoff"])["f1"]
               .agg(median="median", mean="mean", std="std", min="min", max="max")
               .join(res[res["user_id"] == "POOLED"].set_index(["pattern", "cutoff"])[["f1", "precision", "recall"]]
                     .rename(columns=lambda c: f"pooled_{c}"))
               .reindex(pd.MultiIndex.from_product([["F", "S"], order])))
    pd.set_option("display.width", 200)
    pd.set_option("display.max_columns", 20)
    print(summary.round(3))
    for pat in ("F", "S"):
        wide = people[people["pattern"] == pat].pivot(index="user_id", columns="cutoff", values="f1")[order]
        wide.index = wide.index.str[:8]
        prev = people[(people["pattern"] == pat) & (people["cutoff"] == "production")]
        wide.insert(0, "prevalence", prev.set_index(prev["user_id"].str[:8])["prevalence"])
        print(f"\n{pat}: F1 per held-out person")
        print(wide.round(3).sort_values("production"))
    OUT.write_text(json.dumps({"summary": summary.reset_index().to_dict("records"),
                               "per_person": res.to_dict("records")}, indent=2, default=float))
    print("->", OUT)


if __name__ == "__main__":
    main()
