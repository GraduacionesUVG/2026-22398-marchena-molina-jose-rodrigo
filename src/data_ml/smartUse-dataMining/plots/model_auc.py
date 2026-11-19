"""AUC per participant of the production F and S models, leave-one-person-out.

Each person is scored by models that never saw them: the out-of-fold scores cached by
E8d (F: y_f, b = 0, pruned + all dynamics) and E4b (S: y_s, b = 30, pruned), the same
training as experiments/train_final.py, with the 5 seeds merged as in production
(experiments/f1_loso.load). AUC is rank-based, so calibration does not change it.
Baseline: persistence of the same pattern (the past hour's F or S above the profile).
People with a single class (no positives or no negatives) have no AUC and are listed apart.

Usage:  python plots/model_auc.py
Output: figures/models/auc_por_participante.png, data/reports/auc_per_person.csv
"""
from __future__ import annotations

import os
import sys

import matplotlib.pyplot as plt
import numpy as np
import pandas as pd
from sklearn.metrics import roc_auc_score

from plot_style import BLUE, DPI, FIGURES, ORANGE, ROOT, annotate_n, style_bar_axes, suptitle

sys.path.insert(0, str(ROOT))
os.chdir(ROOT)   # f1_loso reads data/ with relative paths
from experiments.f1_loso import load  # noqa: E402

OUT = FIGURES / "models"
TABLE = ROOT / "data" / "reports" / "auc_per_person.csv"
PATTERNS = {"F": ("F (uso fragmentado)", BLUE), "S": ("S (uso sticky, b = 30)", ORANGE)}


def per_person(pat: str) -> pd.DataFrame:
    df = load(pat)
    rows = []
    for u, g in df.groupby("user_id"):
        two = g["y"].nunique() == 2
        rows.append({"pattern": pat, "user_id": u, "n_points": len(g), "prevalence": g["y"].mean(),
                     "auc": roc_auc_score(g["y"], g["raw"]) if two else np.nan,
                     "auc_persistence": roc_auc_score(g["y"], g["persist"]) if two else np.nan})
    return pd.DataFrame(rows)


def summary(t: pd.DataFrame) -> pd.DataFrame:
    rows = []
    for pat, g in t.groupby("pattern"):
        a, b = g["auc"].dropna(), g["auc_persistence"].dropna()
        rows.append({"Modelo": pat, "Personas con AUC": len(a), "Sin AUC (una sola clase)": g["auc"].isna().sum(),
                     "Mediana": a.median(), "Q1": a.quantile(0.25), "Q3": a.quantile(0.75),
                     "Mín.": a.min(), "Máx.": a.max(), "Personas con AUC > 0.5": int((a > 0.5).sum()),
                     "Mediana persistencia": b.median(),
                     "Personas sobre persistencia": int((g["auc"] > g["auc_persistence"]).sum())})
    return pd.DataFrame(rows).set_index("Modelo")


def figure(t: pd.DataFrame) -> None:
    fig, axes = plt.subplots(1, 2, figsize=(11, 5), layout="constrained", sharey=True)
    for ax, (pat, (label, color)) in zip(axes, PATTERNS.items()):
        g = t[(t.pattern == pat) & t.auc.notna()].sort_values("auc").reset_index(drop=True)
        x = np.arange(len(g))
        ax.vlines(x, g.auc_persistence, g.auc, color="0.75", lw=1, zorder=1)
        ax.scatter(x, g.auc_persistence, s=30, facecolor="white", edgecolor="0.4", zorder=2,
                   label="Persistencia")
        ax.scatter(x, g.auc, s=42, color=color, zorder=3, label="Modelo")
        ax.axhline(0.5, color="0.3", ls=":", lw=1.1, label="Azar (0.5)")
        ax.axhline(g.auc.median(), color=color, ls="--", lw=1.3, label=f"Mediana = {g.auc.median():.2f}")
        ax.set_xticks(x, [])
        ax.set_xlabel("Participante (ordenado por AUC del modelo)")
        ax.set_title(f"{label}\n", fontsize=11)
        n_out = int(((t.pattern == pat) & t.auc.isna()).sum())
        sub = f"n = {len(g)}; rango {g.auc.min():.2f}–{g.auc.max():.2f}"
        annotate_n(ax, sub + (f"; {n_out} sin AUC" if n_out else ""))
        style_bar_axes(ax)
        ax.legend(loc="lower right", fontsize=8.5, frameon=False)
    axes[0].set_ylabel("AUC por participante (dejando una persona fuera)")
    axes[0].set_ylim(0, 1.02)
    suptitle(fig, "AUC por participante de los modelos finales F y S")
    OUT.mkdir(parents=True, exist_ok=True)
    fig.savefig(OUT / "auc_por_participante.png", dpi=DPI)
    plt.close(fig)


def main() -> None:
    t = pd.concat([per_person(p) for p in PATTERNS], ignore_index=True)
    t.to_csv(TABLE, index=False)
    with pd.option_context("display.float_format", "{:.3f}".format, "display.width", 200):
        print(summary(t).T.to_string())
        print(t[t.auc.isna()][["pattern", "user_id", "n_points", "prevalence"]].to_string())
    figure(t)


if __name__ == "__main__":
    main()
