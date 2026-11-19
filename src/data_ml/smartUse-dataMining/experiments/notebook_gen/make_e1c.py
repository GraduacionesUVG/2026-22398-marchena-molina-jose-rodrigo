"""Generate notebooks/e1c_profile_days_auc.ipynb (run from the repo root)."""
import nbformat as nbf

md, code = nbf.v4.new_markdown_cell, nbf.v4.new_code_cell
nb = nbf.v4.new_notebook()
nb.cells = [
    md("# E1c: días de perfil con AUC, con los modelos finales\n\n"
       "Criterio fijado antes (`README.md`, E1c): perfil de 3 días frente a perfil con todos los días, "
       "en los mismos ejemplos; ensamble fusionado de 5 semillas, dejando una persona fuera. *d* = 3 se confirma si su "
       "AUC queda a menos de 0.01 del de todos los días, en cada modelo."),
    code('''import os, json
from pathlib import Path
if Path.cwd().name == "notebooks":
    os.chdir("..")
import numpy as np
import pandas as pd
from experiments import calibration as C
from experiments import variants as V
from experiments.modeling import evaluate, feature_cols, persistence_score
from smartuse.features_dynamics import GROUPS

SEEDS = range(5)
pruned = V.pruned(feature_cols(pd.read_csv("data/processed/real_features.csv", nrows=1)))
SPEC = {"F": {"label": "y_f", "d3": "real_features", "all": "real_features_profall", "mpud": "mpud_features",
              "dyn": "", "cols": pruned + [c for cols in GROUPS.values() for c in cols]},
        "S": {"label": "y_s", "d3": "real_features_b30", "all": "real_features_b30_profall", "mpud": "mpud_features_b30",
              "dyn": None, "cols": pruned}}

def load(name, dyn_suffix):
    base = pd.read_csv(f"data/processed/{name}.csv", parse_dates=["day"])
    if dyn_suffix is None:
        return base
    dyn = pd.read_csv(f"data/processed/real_features_dynamics{dyn_suffix}.csv" if name.startswith("real")
                      else f"data/processed/mpud_features_dynamics{dyn_suffix}.csv")
    assert (base["t_utc"].values == dyn["t_utc"].values).all()
    return pd.concat([base, dyn.drop(columns=["user_id", "t_utc"])], axis=1)

cached = {"F": pd.read_parquet("data/reports/e8d_scores.parquet"), "S": pd.read_parquet("data/reports/e4b_scores_b30.parquet")}
cache_key = {"F": "y_f|+ all dynamics|{s}", "S": "y_s|{s}"}'''),
    md("## Brazo de todos los días (5 semillas por modelo)\n\nEl brazo de 3 días reutiliza los scores ya guardados (E8d y E4b)."),
    code('''results, per_person = {}, {}
for pat, sp in SPEC.items():
    d3, alld = load(sp["d3"], sp["dyn"]), load(sp["all"], sp["dyn"])
    mpud = load(sp["mpud"], sp["dyn"])
    lab = sp["label"]
    s_d3 = C.merge_probs({s: cached[pat][cache_key[pat].format(s=s)].to_numpy() for s in SEEDS})
    runs = {}
    for seed in SEEDS:
        V.set_seed(seed)
        runs[seed] = V.scores_for(alld, mpud, sp["cols"], lab)
    s_all = C.merge_probs(runs)
    ev = lambda frame, y, s: evaluate(frame[y].astype(int), s, frame["user_id"], persistence_score(frame), n_boot=500)
    r_d3, r_all, r_cross = ev(d3, lab, s_d3), ev(alld, lab, s_all), ev(alld, lab, s_d3)
    results[pat] = {"AUC d=3 (own labels)": r_d3["auc_median"], "CI d=3": "%.3f-%.3f" % tuple(r_d3["auc_median_ci95"]),
                    "AUC all days (own labels)": r_all["auc_median"], "CI all": "%.3f-%.3f" % tuple(r_all["auc_median_ci95"]),
                    "difference d=3 - all": r_d3["auc_median"] - r_all["auc_median"],
                    "cross: d=3 scores vs all-days labels": r_cross["auc_median"],
                    "rate d=3": d3[lab].mean(), "rate all": alld[lab].mean()}
    per_person[pat] = pd.DataFrame({"d3": pd.Series(r_d3["auc_per_person"]), "all": pd.Series(r_all["auc_per_person"])})
pd.DataFrame(results).T'''),
    md("## Diferencia por persona\n\nAUC de *d* = 3 menos AUC de todos los días, por persona, con un intervalo bootstrap por personas de la mediana."),
    code('''paired = {}
rng = np.random.default_rng(0)
for pat, tab in per_person.items():
    diff = (tab["d3"] - tab["all"]).dropna()
    boot = [np.median(rng.choice(diff.to_numpy(), size=len(diff))) for _ in range(2000)]
    paired[pat] = {"people": len(diff), "median difference": diff.median(),
                   "CI95": "%.3f to %.3f" % tuple(np.percentile(boot, [2.5, 97.5])),
                   "people where d=3 is worse by > 0.01": int((diff < -0.01).sum())}
decision = {pat: abs(min(0.0, results[pat]["difference d=3 - all"])) < 0.01 for pat in SPEC}
print("d = 3 confirmed:", decision)
pd.DataFrame(paired).T'''),
    md("## Guardar"),
    code('''out = {"results": results, "paired": paired, "confirmed": decision,
       "per_person": {p: t.to_dict() for p, t in per_person.items()}}
Path("data/reports/e1c_profile_days_auc.json").write_text(json.dumps(out, indent=2, default=float))
print("-> data/reports/e1c_profile_days_auc.json")'''),
]
nb.metadata["kernelspec"] = {"name": "python3", "display_name": "Python 3", "language": "python"}
nbf.write(nb, "notebooks/e1c_profile_days_auc.ipynb")
print("ok")
