"""SHAP analysis of the production models (data/models/F, data/models/S).

Each production model is the average of 5 XGBoost boosters (seeds 0-4) in margin
(log-odds) space, followed by a Platt calibrator. The calibrator is a monotone
rescaling of the averaged margin, so it is ignored here: SHAP values are computed in
margin space. TreeSHAP is additive, so the SHAP values of the averaged margin are
exactly the average of each booster's SHAP values (xgboost's built-in exact TreeSHAP,
pred_contribs=True).

Data explained: every real-data decision point in the model's feature table
(the tables the production models were trained/validated on).

Usage:  python plots/shap_models.py
Output: figures/shap_f/*, figures/shap_s/*
"""
from __future__ import annotations

import json
import textwrap

import matplotlib.pyplot as plt
import numpy as np
import pandas as pd
import xgboost as xgb
from matplotlib.cm import ScalarMappable
from matplotlib.colors import LinearSegmentedColormap
from matplotlib.patches import Patch

from feature_glossary import DEFINITIONS as GLOSSARY_DEFINITIONS
from feature_glossary import NAMES as GLOSSARY_NAMES
from plot_style import (BLUE, DPI, FIGURES, ORANGE, ROOT, set_title, style_bar_axes,
                        suptitle)

MODELS = ROOT / "data" / "models"
PROCESSED = ROOT / "data" / "processed"
TOP_N = 10
TOP_SIZE = (8, 5.2)

# Spanish labels for the thesis (definitions: README.md, section 6). Features without
# an entry are shown by their code name.
LABELS = {
    "prof_pf": "Umbral personal de F\n(percentil 80 del perfil)",
    "prof_ps": "Umbral personal de S\n(percentil 80 del perfil)",
    "f60_rank": "Posición de F de la última hora\nen el perfil de la persona",
    "s60_rank": "Posición de S de la última hora\nen el perfil de la persona",
    "n_24h_accel": "Aceleración de sesiones\niniciadas (últimas 24 h)",
    "n_24h_slope": "Pendiente de sesiones iniciadas\npor hora (últimas 24 h)",
    "n_24h_com": "Centroide temporal de sesiones iniciadas\nen las 24 horas previas",
    "cur_logdur": "Duración de la sesión en curso",
    "n_15": "Sesiones en los últimos 15 min",
    "freq_day": "Sesiones en el día hasta ahora",
    "max_f60_day": "F máximo de una hora\ncompleta del día",
    "prof_freq_day": "Sesiones por día típicas\n(perfil)",
}

# Short definitions for the legend file: the full glossary (plots/feature_glossary.py) with
# these reviewed wordings taking precedence.
DEFINITION_OVERRIDES = {
    "prof_pf": "Percentil 80 de $F$ en las ventanas de los puntos de decisión de los días del "
               "perfil del participante; es el umbral que define la etiqueta de $F$.",
    "prof_ps": "Percentil 80 de S (ventanas de 60 min) en los días de perfil de la persona.",
    "f60_rank": "Posición de F de la última hora [t − 60 min, t) dentro de los percentiles "
                "del perfil de la persona (0.05 a 0.95).",
    "s60_rank": "Posición de S de la última hora dentro de los percentiles del perfil "
                "de la persona (0.05 a 0.95).",
    "n_24h_accel": "Pendiente de mínimos cuadrados de las sesiones iniciadas por hora en las "
                   "12 horas más recientes, menos la de las 12 horas anteriores, dentro de la "
                   "ventana móvil de 24 horas previa al punto de decisión; un valor positivo "
                   "indica que la actividad se está acelerando.",
    "n_24h_slope": "Pendiente de mínimos cuadrados de las sesiones iniciadas por hora en las "
                   "24 h previas (24 bins de 1 h).",
    "n_24h_com": "Centroide temporal (0 = hace 24 h, 1 = ahora) de las sesiones iniciadas en las "
                 "24 h previas; 0.5 si no hay sesiones.",
    "cur_logdur": "log1p de los segundos transcurridos en la sesión en curso; 0 si no hay "
                  "sesión abierta (punto de desbloqueo).",
    "n_15": "Número de sesiones en los últimos 15 min.",
    "freq_day": "Número de sesiones del día local, desde la medianoche hasta t.",
    "max_f60_day": "Máximo de F entre las horas de reloj completas del día hasta t; 0 si no hay.",
    "prof_freq_day": "Mediana de sesiones por día en los días de perfil de la persona.",
}
DEFINITIONS = {**GLOSSARY_DEFINITIONS, **DEFINITION_OVERRIDES}


PDP_MODELS = {"F"}  # models that get the PDP / ICE figure


def label_of(feat: str) -> str:
    """Figure label: reviewed LABELS first, else the glossary name wrapped to 2 lines."""
    if feat in LABELS:
        return LABELS[feat]
    return textwrap.fill(GLOSSARY_NAMES.get(feat, feat), 34)


def platt_note(pattern: str) -> str:
    """Axis note on calibration: F has Platt (ignored), S uses the raw score."""
    meta = json.loads((MODELS / pattern / "model.json").read_text())
    return ", antes de la calibración Platt" if meta["calibrator"]["method"] == "platt" else ""


def feature_table(pattern: str) -> pd.DataFrame:
    """Real-data feature rows the production model uses (as in experiments/train_final.py)."""
    if pattern == "F":
        base = pd.read_csv(PROCESSED / "real_features.csv")
        dyn = pd.read_csv(PROCESSED / "real_features_dynamics.csv")
        assert (base["t_utc"].values == dyn["t_utc"].values).all()
        return pd.concat([base, dyn.drop(columns=["user_id", "t_utc"])], axis=1)
    return pd.read_csv(PROCESSED / "real_features_b30.csv")


def shap_values(pattern: str) -> tuple[pd.DataFrame, np.ndarray, pd.DataFrame]:
    """(SHAP values per row and feature, base value per row, feature values), margin
    space, seed-averaged."""
    meta = json.loads((MODELS / pattern / "model.json").read_text())
    features = meta["features"]
    table = feature_table(pattern)[features]
    X = table.to_numpy(dtype=np.float32)
    dm = xgb.DMatrix(X, feature_names=features)
    contribs = []
    for k in range(meta["n_seeds"]):
        b = xgb.Booster()
        b.load_model(MODELS / pattern / f"seed{k}.json")
        contribs.append(b.predict(dm, pred_contribs=True))  # last column = bias
    c = np.mean(contribs, axis=0)
    return pd.DataFrame(c[:, :-1], columns=features), c[:, -1], table.reset_index(drop=True)


def top_mean_abs(pattern: str, shap: pd.DataFrame) -> None:
    out = FIGURES / f"shap_{pattern.lower()}"
    out.mkdir(parents=True, exist_ok=True)
    imp = shap.abs().mean().sort_values(ascending=False)
    imp.rename("mean_abs_shap").to_csv(out / "mean_abs_shap.csv", header=True)
    top = imp.head(TOP_N)[::-1]  # largest at the top of the chart

    fig, ax = plt.subplots(figsize=TOP_SIZE, layout="constrained")
    rects = ax.barh(range(len(top)), top.values, color=BLUE, height=0.7)
    ax.bar_label(rects, labels=[f"{v:.3f}" for v in top.values], padding=3, fontsize=9)
    ax.set_yticks(range(len(top)), [label_of(c) for c in top.index], fontsize=9)
    ax.set_xlabel("Media de |SHAP| (log-odds" + platt_note(pattern) + ")")
    ax.set_xlim(0, top.max() * 1.15)
    style_bar_axes(ax)
    ax.grid(axis="y", visible=False)
    ax.grid(axis="x", alpha=0.3)
    set_title(ax, f"Importancia media |SHAP|, modelo {pattern} (top {TOP_N})",
              f"(n = {len(shap):,} puntos de decisión, {shap.shape[1]} features)", width=70)
    fig.savefig(out / f"top{TOP_N}_mean_abs.png", dpi=DPI)
    plt.close(fig)
    share = top.sum() / imp.sum() * 100
    print(f"{pattern}: top {TOP_N} = {share:.1f}% of total mean|SHAP|")
    print(imp.head(TOP_N).round(4).to_string())


BEESWARM_SIZE = (8, 6)
SWARM_BINS = 120        # x bins used to stack points vertically
SWARM_HALF_HEIGHT = 0.4  # max vertical spread of a row (rows are 1 apart)
SHAP_CMAP = LinearSegmentedColormap.from_list("shap", ["#1f77b4", "#b8b8d6", "#e8475f"])


def swarm_offsets(x: np.ndarray) -> np.ndarray:
    """Vertical offsets that stack points sharing an x bin symmetrically around 0
    (shap-style beeswarm), scaled so the densest bin spans ±SWARM_HALF_HEIGHT."""
    edges = np.linspace(x.min(), x.max() + 1e-12, SWARM_BINS + 1)
    b = np.digitize(x, edges)
    order = np.argsort(b, kind="stable")
    rank = np.zeros(len(x), dtype=int)
    _, starts, counts = np.unique(b[order], return_index=True, return_counts=True)
    for s0, n in zip(starts, counts):
        rank[order[s0:s0 + n]] = np.arange(n)
    off = ((rank + 1) // 2) * np.where(rank % 2, 1, -1)
    return off / max(counts.max() / 2, 1) * SWARM_HALF_HEIGHT


def beeswarm(pattern: str, shap: pd.DataFrame, values: pd.DataFrame) -> None:
    """Beeswarm of the TOP_N features by mean |SHAP|: one dot per decision point, x = SHAP
    value, colour = the feature's value (5th-95th percentile scale per feature)."""
    out = FIGURES / f"shap_{pattern.lower()}"
    top = shap.abs().mean().sort_values(ascending=False).head(TOP_N).index[::-1]
    rng = np.random.default_rng(0)
    order = rng.permutation(len(shap))  # draw in random order so no colour sits on top

    fig, ax = plt.subplots(figsize=BEESWARM_SIZE, layout="constrained")
    ax.axvline(0, color="0.4", linewidth=0.8, zorder=0)
    for row, feat in enumerate(top):
        x = shap[feat].to_numpy()[order]
        v = values[feat].to_numpy(dtype=float)[order]
        lo, hi = np.nanpercentile(v, [5, 95])
        if hi <= lo:
            lo, hi = np.nanmin(v), np.nanmax(v)
        c = np.clip((v - lo) / (hi - lo if hi > lo else 1), 0, 1)
        y = row + swarm_offsets(x)
        ax.scatter(x, y, c=c, cmap=SHAP_CMAP, vmin=0, vmax=1, s=3, alpha=0.6,
                   linewidths=0, rasterized=True)
    ax.set_yticks(range(len(top)), [label_of(f) for f in top], fontsize=9)
    ax.set_ylim(-0.6, len(top) - 0.4)
    ax.set_xlabel(f"Valor SHAP (log-odds; > 0 aumenta la probabilidad predicha de {pattern})")
    style_bar_axes(ax)
    ax.grid(axis="y", visible=False)
    ax.grid(axis="x", alpha=0.3)
    cb = fig.colorbar(ScalarMappable(cmap=SHAP_CMAP), ax=ax, pad=0.02, aspect=40)
    cb.set_ticks([0, 1], labels=["Bajo", "Alto"])
    cb.set_label("Valor de la feature", fontsize=10)
    cb.outline.set_visible(False)
    set_title(ax, f"Valores SHAP por punto de decisión, modelo {pattern} (top {TOP_N})",
              f"(n = {len(shap):,} puntos de decisión)", width=70)
    fig.savefig(out / f"top{TOP_N}_beeswarm.png", dpi=DPI)
    plt.close(fig)


def feature_legend(pattern: str, shap: pd.DataFrame) -> None:
    """Markdown legend (Spanish) for every feature of the model, ranked by mean |SHAP|:
    technical name, Spanish name (the figure label when it has one) and short definition."""
    out = FIGURES / f"shap_{pattern.lower()}"
    imp = shap.abs().mean().sort_values(ascending=False)
    missing = [f for f in imp.index if f not in DEFINITIONS]
    if missing:
        raise KeyError(f"features without a definition: {missing}")
    lines = [f"# Leyenda de features, modelo {pattern}", "",
             f"Las {len(imp)} features del modelo {pattern}, ordenadas por media de |SHAP| "
             f"(log-odds, n = {len(shap):,} puntos de decisión). Las {TOP_N} primeras son las "
             "que aparecen en las figuras. Definiciones completas en "
             "la sección 6 del `README.md`.", "",
             "| # | Nombre técnico | Nombre | Definición | Media de \\|SHAP\\| |",
             "|---|---|---|---|---|"]
    for i, (feat, v) in enumerate(imp.items(), 1):
        name = LABELS.get(feat, GLOSSARY_NAMES.get(feat, feat)).replace("\n", " ")
        lines.append(f"| {i} | `{feat}` | {name} | {DEFINITIONS[feat]} | {v:.3f} |")
    (out / "leyenda_features.md").write_text("\n".join(lines) + "\n", encoding="utf-8")


PDP_TOP = 4             # features with PDP / ICE panels
PDP_GRID = 40           # grid points per feature (quantiles 1-99 %, plus 0 if present)
ICE_LINES = 200         # random decision points drawn as ICE curves
PDP_SIZE = (10, 8.6)    # close to the page's aspect, so it shrinks little when placed
# larger text for the PDP / ICE figures: they are placed at full page width (~16 cm),
# so a 10-in figure prints at ~0.6x and these sizes end up around 8-10 pt on paper
PDP_RC = {"font.size": 15, "axes.labelsize": 15, "xtick.labelsize": 14, "ytick.labelsize": 14,
          "legend.fontsize": 14}
PDP_LABEL_WRAP = 34     # characters per line of an x-axis label
PDP_YLABEL = "Salida (log-odds)"
ICE_ALPHA = 0.18


def readable(fn):
    """Run a PDP / ICE figure function under PDP_RC."""
    def wrapped(*args, **kwargs):
        with plt.rc_context(PDP_RC):
            return fn(*args, **kwargs)
    return wrapped
# features stored as log1p(seconds): label their PDP axis in seconds instead
SECONDS_AXIS = {"cur_logdur": [0, 10, 100, 600, 3600, 10800]}


def seconds_ticks(ax, secs: list[int]) -> None:
    """Tick a log1p(seconds) axis in seconds, keeping only ticks inside the current limits."""
    lo, hi = ax.get_xlim()
    keep = [v for v in secs if lo <= np.log1p(v) <= hi]
    names = {0: "0", 10: "10 s", 100: "100 s", 600: "10 min", 3600: "1 h", 10800: "3 h"}
    ax.set_xticks(np.log1p(keep), [names.get(v, f"{v:,}") for v in keep])
    ax.set_xlim(lo, hi)


def load_boosters(pattern: str) -> list[xgb.Booster]:
    meta = json.loads((MODELS / pattern / "model.json").read_text())
    boosters = []
    for k in range(meta["n_seeds"]):
        b = xgb.Booster()
        b.load_model(MODELS / pattern / f"seed{k}.json")
        boosters.append(b)
    return boosters


def margin(boosters: list[xgb.Booster], X: np.ndarray, features: list[str]) -> np.ndarray:
    """Seed-averaged log-odds (the production model before Platt)."""
    dm = xgb.DMatrix(X.astype(np.float32), feature_names=features)
    return np.mean([b.predict(dm, output_margin=True) for b in boosters], axis=0)


@readable
def pdp_ice(pattern: str, shap: pd.DataFrame, values: pd.DataFrame) -> None:
    """PDP (mean over all decision points) and ICE (ICE_LINES random points) of the
    PDP_TOP features by mean |SHAP|, in log-odds like the SHAP figures."""
    out = FIGURES / f"shap_{pattern.lower()}"
    features = list(values.columns)
    boosters = load_boosters(pattern)
    top = shap.abs().mean().sort_values(ascending=False).head(PDP_TOP).index
    X = values.to_numpy(dtype=np.float32)
    rng = np.random.default_rng(0)
    ice_rows = rng.choice(len(X), size=min(ICE_LINES, len(X)), replace=False)

    fig, axes = plt.subplots(2, 2, figsize=PDP_SIZE, layout="constrained")
    for ax, feat in zip(axes.flat, top):
        j = features.index(feat)
        col = X[:, j]
        grid = np.unique(np.quantile(col, np.linspace(0.01, 0.99, PDP_GRID)))
        if (col == 0).any() and grid[0] > 0:
            grid = np.r_[0.0, grid]
        pd_curve, ice = [], np.empty((len(ice_rows), len(grid)))
        for g_i, g in enumerate(grid):
            Xg = X.copy()
            Xg[:, j] = g
            m = margin(boosters, Xg, features)
            pd_curve.append(m.mean())
            ice[:, g_i] = m[ice_rows]
        for line in ice:
            ax.plot(grid, line, color=BLUE, alpha=ICE_ALPHA, linewidth=0.8)
        ax.plot(grid, pd_curve, color=ORANGE, linewidth=3.4, marker="o", markersize=4.5)
        lo, hi = ax.get_ylim()
        deciles = np.quantile(col, np.linspace(0.1, 0.9, 9))
        ax.vlines(deciles, lo, lo + (hi - lo) * 0.04, color="0.3", linewidth=1)  # rug
        ax.set_ylim(lo, hi)
        label = label_of(feat).replace("\n", " ")
        if feat in SECONDS_AXIS:
            seconds_ticks(ax, SECONDS_AXIS[feat])
            label += " (escala log)"
        ax.set_xlabel(textwrap.fill(label, PDP_LABEL_WRAP))
        ax.set_ylabel(PDP_YLABEL)
        style_bar_axes(ax)
        ax.grid(axis="x", alpha=0.3)
    handles = [plt.Line2D([], [], color=BLUE, alpha=0.6, linewidth=1.5,
                          label=f"ICE ({len(ice_rows)} puntos al azar)"),
               plt.Line2D([], [], color=ORANGE, linewidth=3.4, marker="o", markersize=4.5,
                          label=f"PDP (promedio de {len(X):,} puntos)"),
               plt.Line2D([], [], color="0.3", linewidth=1.5, label="Deciles de la feature")]
    fig.legend(handles=handles, loc="outside lower center", ncols=3, frameon=False,
               handlelength=1.6, columnspacing=1.2)
    suptitle(fig, f"Modelo {pattern}: dependencia parcial (PDP) e ICE (top {PDP_TOP})",
                 fontsize=14)
    fig.savefig(out / f"top{PDP_TOP}_pdp_ice.png", dpi=DPI)
    plt.close(fig)


COND_TOP = {"S": 3}     # models whose PDP / ICE is restricted to observed combinations
COND_MIN_CELL = 5       # cells with fewer rows fall back to the feature's global range
COND_LOW_COVERAGE = 0.2  # shade grid values plausible for fewer than this share of rows
COND_SIZE = (10, 8.6)   # 2 x 2: three panels + the legend in the fourth cell


def cond_bins(col: np.ndarray) -> np.ndarray:
    """Discrete feature -> its own values; continuous -> decile bins."""
    if len(np.unique(col)) <= 25:
        return col
    edges = np.unique(np.quantile(col, np.linspace(0, 1, 11)))
    return np.digitize(col, edges[1:-1])


def plausible_ranges(X: np.ndarray, j: int, others: list[int]) -> tuple[np.ndarray, np.ndarray]:
    """Per row, the [min, max] of feature j among rows that share the other top features'
    values (discrete) or decile bins (continuous): the values of j actually observed
    together with that row's context."""
    key = pd.DataFrame({f"c{k}": cond_bins(X[:, k]) for k in others})
    key["v"] = X[:, j]
    g = key.groupby([f"c{k}" for k in others])["v"]
    lo, hi, n = g.transform("min").to_numpy(), g.transform("max").to_numpy(), g.transform("size").to_numpy()
    glo, ghi = np.quantile(X[:, j], [0.01, 0.99])
    small = n < COND_MIN_CELL
    lo[small], hi[small] = glo, ghi
    return lo, hi


@readable
def pdp_ice_conditional(pattern: str, shap: pd.DataFrame, values: pd.DataFrame) -> None:
    """PDP / ICE of the COND_TOP[pattern] features by mean |SHAP|, restricted to observed
    combinations. The top features constrain each other (e.g. a session already 1 h long
    forces the last hour's S to its maximum), so moving one feature alone can create
    impossible inputs. For each row the feature is only varied inside the range seen
    among rows with the same values of the other top features (plausible_ranges):
      - ICE: each curve is drawn only inside its row's plausible range;
      - PDP: at each grid value, the mean over the rows for which it is plausible;
      - shaded: grid values plausible for < COND_LOW_COVERAGE of the rows, or outside
        the observed range. Dashed lines: observed minimum and maximum."""
    n_top = COND_TOP[pattern]
    out = FIGURES / f"shap_{pattern.lower()}"
    features = list(values.columns)
    boosters = load_boosters(pattern)
    top = list(shap.abs().mean().sort_values(ascending=False).head(n_top).index)
    X = values.to_numpy(dtype=np.float32)
    rng = np.random.default_rng(0)
    ice_rows = rng.choice(len(X), size=min(ICE_LINES, len(X)), replace=False)

    fig, axes = plt.subplots(2, 2, figsize=COND_SIZE, layout="constrained")
    axes = axes.flatten()
    for ax, feat in zip(axes, top):
        j = features.index(feat)
        col = X[:, j]
        others = [features.index(f) for f in top if f != feat]
        lo, hi = plausible_ranges(X, j, others)
        uniq = np.unique(col)
        grid = uniq if len(uniq) <= PDP_GRID else np.unique(
            np.quantile(col, np.linspace(0.01, 0.99, PDP_GRID)))
        pdp, cover = np.full(len(grid), np.nan), np.zeros(len(grid))
        ice = np.full((len(ice_rows), len(grid)), np.nan)
        for g_i, g in enumerate(grid):
            ok = (lo <= g) & (g <= hi)
            cover[g_i] = ok.mean()
            if not ok.any():
                continue
            Xg = X.copy()
            Xg[:, j] = g
            m = margin(boosters, Xg, features)
            pdp[g_i] = m[ok].mean()
            ice[:, g_i] = np.where(ok[ice_rows], m[ice_rows], np.nan)
        for line in ice:  # NaN breaks the curve outside the row's plausible range
            ax.plot(grid, line, color=BLUE, alpha=ICE_ALPHA, linewidth=0.8)
        low = cover < COND_LOW_COVERAGE
        solid = np.where(low, np.nan, pdp)  # dashed where few points support the value
        dashed = np.where(low | np.r_[low[1:], False] | np.r_[False, low[:-1]], pdp, np.nan)
        ax.plot(grid, dashed, color=ORANGE, linewidth=2.6, linestyle="--")
        ax.plot(grid, solid, color=ORANGE, linewidth=3.4, marker="o", markersize=4.5)

        ylo, yhi = ax.get_ylim()
        xmin, xmax = col.min(), col.max()
        pad = (xmax - xmin) * 0.04
        ax.set_xlim(xmin - pad, xmax + pad)
        mids = np.r_[xmin - pad, (grid[1:] + grid[:-1]) / 2, xmax + pad]
        for g_i in np.flatnonzero(low):
            ax.axvspan(mids[g_i], mids[g_i + 1], color="0.85", zorder=0, linewidth=0)
        for x in (xmin, xmax):
            ax.axvline(x, color="0.3", linestyle="--", linewidth=1)
        deciles = np.quantile(col, np.linspace(0.1, 0.9, 9))
        ax.vlines(deciles, ylo, ylo + (yhi - ylo) * 0.04, color="0.3", linewidth=1)
        ax.set_ylim(ylo, yhi)
        label = label_of(feat).replace("\n", " ")
        if feat in SECONDS_AXIS:
            seconds_ticks(ax, SECONDS_AXIS[feat])
            label += " (escala log)"
        ax.set_xlabel(textwrap.fill(label, PDP_LABEL_WRAP))
        ax.set_ylabel(PDP_YLABEL)
        style_bar_axes(ax)
        ax.grid(axis="x", alpha=0.3)
        print(f"{pattern} PDP {feat}: grid={len(grid)} low-coverage points={int(low.sum())} "
              f"coverage min/median={cover.min():.2f}/{np.median(cover):.2f}")
    handles = [plt.Line2D([], [], color=BLUE, alpha=0.6, linewidth=1.5,
                          label=f"ICE ({len(ice_rows)} puntos\nal azar)"),
               plt.Line2D([], [], color=ORANGE, linewidth=3.4, marker="o", markersize=4.5,
                          label="PDP (promedio de los\npuntos donde el valor\nes observable)"),
               plt.Line2D([], [], color=ORANGE, linewidth=2.6, linestyle="--",
                          label="PDP con poco soporte"),
               plt.Line2D([], [], color="0.3", linewidth=1.5, label="Deciles de la feature"),
               plt.Line2D([], [], color="0.3", linewidth=1.5, linestyle="--",
                          label="Mínimo y máximo\nobservados"),
               Patch(facecolor="0.85", label=f"Combinación observada\nen < "
                                             f"{COND_LOW_COVERAGE:.0%} de los puntos")]
    for ax in axes[n_top:]:
        ax.axis("off")
    axes[-1].legend(handles=handles, loc="center", frameon=False, labelspacing=1.0,
                    handlelength=2.2)
    suptitle(fig, f"Modelo {pattern}: dependencia parcial (PDP) e ICE (top {n_top}, "
                 "solo combinaciones observadas)", fontsize=14)
    fig.savefig(out / f"top{n_top}_pdp_ice.png", dpi=DPI)
    plt.close(fig)


def main() -> None:
    for pattern in ["F", "S"]:
        shap, base, values = shap_values(pattern)
        print(f"{pattern}: rows={len(shap)} features={shap.shape[1]} base={base.mean():.3f}")
        top_mean_abs(pattern, shap)
        beeswarm(pattern, shap, values)
        feature_legend(pattern, shap)
        if pattern in PDP_MODELS:
            pdp_ice(pattern, shap, values)
        if pattern in COND_TOP:
            pdp_ice_conditional(pattern, shap, values)


if __name__ == "__main__":
    main()
