"""E1d: profile days d = 3, 4, 5 with F1, new cutoffs and new labels (README §5.7).

Tables for d = 4, 5 (d = 3 are the production tables):

    python -m pipelines.labels.build_features {real,mpud} --d D --out data/processed/{ds}_features_profD.csv
    python -m pipelines.labels.build_features {real,mpud} --d D --points data/processed/{ds}_windows_points_b30.csv \
        --out data/processed/{ds}_features_b30_profD.csv

Dynamics features do not use the profile, so the d = 3 dynamics tables are
reused, matched by user_id and t_utc. Production configs: F = y_f, b = 0,
pruned + all dynamics; S = y_s, b = 30, pruned. Leave-one-person-out, 5 seeds,
merged ensemble. d = 3 reuses the E8d and E4b scores; d = 4, 5 are trained here
and cached in data/reports/e1d_scores_{F,S}_dD.parquet after each run, so the script
can be rerun until every run is there.

Evaluated on the common points (in all three arms) against each arm's labels:
AUC and PR-AUC, precision / recall / F1 at the nested E9b cutoff, F1 at the
nested best-F1 cutoff, F1 against the all-days profile labels (E1c), persistence
F1, prevalence and label kappa vs d = 3; plus the new in-sample E9b cutoff and
its interventions per day on each arm's full set.

    python -m experiments.e1d_profile_days_f1
"""

import json
from pathlib import Path

import numpy as np
import pandas as pd
from sklearn.metrics import average_precision_score, cohen_kappa_score, roc_auc_score

from experiments import calibration as C
from experiments import e9b as E
from experiments import variants as V
from experiments.f1_loso import best_f1_cut, prf, rule_cut
from experiments.modeling import feature_cols
from pipelines.labels.build_windows import seconds
from smartuse.features_dynamics import GROUPS

SEEDS = range(5)
DAYS = (3, 4, 5)
SPEC = {"F": {"label": "y_f", "table": "features", "dyn": True, "cached": ("e8d_scores", "y_f|+ all dynamics|{s}")},
        "S": {"label": "y_s", "table": "features_b30", "dyn": False, "cached": ("e4b_scores_b30", "y_s|{s}")}}
KEY = ["user_id", "t_utc"]
OUT = Path("data/reports/e1d_profile_days_f1.json")


def table(dataset: str, pat: str, d: int) -> pd.DataFrame:
    sp = SPEC[pat]
    name = f"{dataset}_{sp['table']}" + ("" if d == 3 else f"_prof{d}")
    df = pd.read_csv(f"data/processed/{name}.csv", parse_dates=["day"])
    if sp["dyn"]:
        dyn = pd.read_csv(f"data/processed/{dataset}_features_dynamics.csv")
        df = df.merge(dyn, on=KEY, how="left", validate="one_to_one")
        assert not df[[c for cols in GROUPS.values() for c in cols]].isna().any().any()
    return df


def columns(pat: str) -> list[str]:
    pruned = V.pruned(feature_cols(pd.read_csv("data/processed/real_features.csv", nrows=1)))
    return pruned + [c for cols in GROUPS.values() for c in cols] if SPEC[pat]["dyn"] else pruned


def train_missing() -> None:
    for d in DAYS[1:]:
        for pat, sp in SPEC.items():
            cache = Path(f"data/reports/e1d_scores_{pat}_d{d}.parquet")
            sc = pd.read_parquet(cache) if cache.exists() else None
            if sc is not None and all(f"{pat}|{s}" in sc for s in SEEDS):
                continue
            real, mpud = table("real", pat, d), table("mpud", pat, d)
            if sc is None:
                sc = pd.DataFrame(index=real.index)
            assert len(sc) == len(real)
            for seed in SEEDS:
                if f"{pat}|{seed}" in sc:
                    continue
                V.set_seed(seed)
                sc[f"{pat}|{seed}"] = V.scores_for(real, mpud, columns(pat), sp["label"])
                sc.to_parquet(cache)
                print(f"d={d} {pat} seed {seed} done", flush=True)


def arm(pat: str, d: int) -> pd.DataFrame:
    """The arm's real examples with label y, persistence and the merged out-of-fold score."""
    sp = SPEC[pat]
    df = pd.read_csv(f"data/processed/real_{sp['table']}{'' if d == 3 else f'_prof{d}'}.csv", parse_dates=["day"])
    if d == 3:
        name, fmt = sp["cached"]
        sc = pd.read_parquet(f"data/reports/{name}.parquet")
        per_seed = {s: sc[fmt.format(s=s)].to_numpy() for s in SEEDS}
    else:
        sc = pd.read_parquet(f"data/reports/e1d_scores_{pat}_d{d}.parquet")
        per_seed = {s: sc[f"{pat}|{s}"].to_numpy() for s in SEEDS}
    assert len(sc) == len(df)
    m, prof = ("f_60", "prof_pf") if pat == "F" else ("s_60", "prof_ps")
    out = df[KEY + ["day"]].assign(y=df[sp["label"]].astype(int), persist=(df[m] > df[prof]).astype(int),
                                   raw=C.merge_probs(per_seed), t=seconds(pd.to_datetime(df["t_utc"])))
    return out.sort_values(["user_id", "t"]).reset_index(drop=True)


def reference_labels(pat: str) -> pd.DataFrame:
    """Labels from the all-days profile (E1c)."""
    sp = SPEC[pat]
    ref = pd.read_csv(f"data/processed/real_{sp['table']}_profall.csv", usecols=KEY + [sp["label"]])
    return ref.rename(columns={sp["label"]: "y_ref"})


def nested_preds(full: pd.DataFrame, people: list) -> tuple[np.ndarray, np.ndarray]:
    """Per held-out person, the E9b rule and best-F1 cutoffs from everyone else in the arm."""
    rule, f1 = np.zeros(len(full), dtype=bool), np.zeros(len(full), dtype=bool)
    users, raw, y = full["user_id"].to_numpy(), full["raw"].to_numpy(), full["y"].to_numpy().astype(bool)
    for u in people:
        te, tr = users == u, users != u
        rule[te] = raw[te] > rule_cut(full[tr].reset_index(drop=True), "F")
        f1[te] = raw[te] > best_f1_cut(raw[tr], y[tr])
    return rule, f1


def ranking(y: np.ndarray, s: np.ndarray, users: np.ndarray) -> dict:
    per_auc, per_ap = {}, {}
    for u in np.unique(users):
        m = users == u
        if len(np.unique(y[m])) == 2:
            per_auc[u], per_ap[u] = roc_auc_score(y[m], s[m]), average_precision_score(y[m], s[m])
    return {"auc_per_person": per_auc, "pr_auc_per_person": per_ap,
            "auc_median": float(np.median(list(per_auc.values()))),
            "pr_auc_median": float(np.median(list(per_ap.values()))),
            "auc_pooled": float(roc_auc_score(y, s)), "pr_auc_pooled": float(average_precision_score(y, s))}


def per_person(df: pd.DataFrame, y_col: str, pred: np.ndarray) -> pd.DataFrame:
    rows = {u: prf(df[y_col].to_numpy()[m].astype(bool), pred[m])
            for u in df["user_id"].unique() for m in [df["user_id"].to_numpy() == u]}
    return pd.DataFrame(rows).T


def evaluate_pattern(pat: str) -> tuple[dict, dict]:
    arms = {d: arm(pat, d) for d in DAYS}
    common = set(map(tuple, arms[5][KEY].to_numpy()))
    for d in DAYS[:-1]:
        common &= set(map(tuple, arms[d][KEY].to_numpy()))
    ref = reference_labels(pat)
    summary, persons = {}, {}
    for d, full in arms.items():
        people = sorted({k[0] for k in common})
        rule, f1 = nested_preds(full, people)
        c_in = rule_cut(full, "F")  # same E9b rule as production, in-sample on the arm
        sim = full.rename(columns={"y": "y_f"}).assign(p_f=full["raw"], p_s=0.0)
        ops = E.metrics(sim, E.simulate_joint(sim, c_in, np.inf), "F")
        keep = np.array([k in common for k in map(tuple, full[KEY].to_numpy())])
        ev = full[keep].merge(ref, on=KEY, how="left", validate="one_to_one").reset_index(drop=True)
        assert not ev["y_ref"].isna().any()
        rule, f1 = rule[keep], f1[keep]
        y, users = ev["y"].to_numpy().astype(bool), ev["user_id"].to_numpy()
        rk = ranking(y.astype(int), ev["raw"].to_numpy(), users)
        tabs = {"rule": per_person(ev, "y", rule), "best_f1": per_person(ev, "y", f1),
                "rule_vs_all_days_labels": per_person(ev, "y_ref", rule),
                "persistence": per_person(ev, "y", ev["persist"].to_numpy().astype(bool))}
        pooled = prf(y, rule)
        summary[d] = {
            "people": len(people), "points": int(len(ev)), "examples_full": int(len(full)),
            "prevalence": float(y.mean()),
            **{k: v for k, v in rk.items() if not k.endswith("per_person")},
            **{f"{name}_{m}_median": float(t[m].astype(float).median()) for name, t in tabs.items()
               for m in ("f1", "precision", "recall")},
            "rule_f1_pooled": pooled["f1"], "rule_precision_pooled": pooled["precision"],
            "rule_recall_pooled": pooled["recall"],
            "cutoff_in_sample": c_in, "in_sample_precision": ops["precision"],
            "in_sample_episode_sensitivity": ops["episode_sensitivity"], "per_day_mean": ops["per_day_mean"]}
        persons[d] = {"f1": tabs["rule"]["f1"].astype(float), "auc": pd.Series(rk["auc_per_person"]),
                      "pr_auc": pd.Series(rk["pr_auc_per_person"]),
                      "y": ev.set_index(KEY)["y"]}
    y3 = persons[3]["y"]
    for d in DAYS:
        yd = persons[d]["y"].reindex(y3.index)
        summary[d]["kappa_vs_d3"] = float(cohen_kappa_score(y3, yd))
    rng = np.random.default_rng(0)
    for d in DAYS[1:]:
        for m in ("f1", "auc", "pr_auc"):
            diff = (persons[d][m] - persons[3][m]).dropna().to_numpy()
            boot = [np.median(rng.choice(diff, size=len(diff))) for _ in range(2000)]
            summary[d][f"paired_{m}_diff_median"] = float(np.median(diff))
            summary[d][f"paired_{m}_diff_ci95"] = [float(x) for x in np.percentile(boot, [2.5, 97.5])]
    per = {d: {m: persons[d][m].to_dict() for m in ("f1", "auc", "pr_auc")} for d in DAYS}
    return summary, per


def decide(summary: dict) -> int:
    def delta(d, base, m):
        return {p: summary[p][d][m] - summary[p][base][m] for p in SPEC}

    def passes(d):
        f1, ref, auc = (delta(d, 3, m) for m in ("rule_f1_median", "rule_vs_all_days_labels_f1_median", "auc_median"))
        no_drop = all(f1[p] >= -0.01 and ref[p] >= -0.01 and auc[p] >= -0.01 for p in SPEC)
        return no_drop and any(f1[p] >= 0.02 for p in SPEC)

    if passes(4) and passes(5):
        return 5 if any(v >= 0.02 for v in delta(5, 4, "rule_f1_median").values()) else 4
    return 4 if passes(4) else 5 if passes(5) else 3


def main() -> None:
    train_missing()
    summary, per = {}, {}
    for pat in SPEC:
        summary[pat], per[pat] = evaluate_pattern(pat)
    pd.set_option("display.width", 220)
    pd.set_option("display.max_columns", 30)
    pd.set_option("display.max_rows", 80)
    for pat in SPEC:
        print(f"\n{pat}")
        print(pd.DataFrame(summary[pat]).drop(index=[k for k in summary[pat][4] if k.endswith("ci95")]).round(3))
    d = decide(summary)
    print("\nchosen d:", d)
    OUT.write_text(json.dumps({"summary": summary, "per_person": per, "chosen_d": d}, indent=2, default=float))
    print("->", OUT)


if __name__ == "__main__":
    main()
