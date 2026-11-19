"""Pre-EDA: evidence behind the label design (person's own 80th percentile).

P1: ICC(1) of F and S, real data vs MPUD, with a person-level bootstrap 95 % CI and
    Siebers et al. (2024) as reference (0.16 F, 0.27 S). Low ICC = most variance is
    within the person -> a population cutoff would mostly sort people, not moments.
    Two window populations per dataset:
      - decision points: the windows the profile and the label are computed on;
      - Siebers-like sampling: per person and usable local day, SIEBERS_N random
        non-overlapping 60-min windows inside [SIEBERS_FROM_H, SIEBERS_TO_H) local time,
        mimicking the 6 daily ESM prompts and their 60-min recall window.
    In both, F only counts windows where it is defined (>= 2 sessions; F = 0 below that)
    and S only windows with at least one session (no use = no S).
P2: per-person distributions of F and S (real data), sorted by the person's 80th
    percentile, with the population 80th percentile for contrast. Same rule: F without
    the windows with < 2 sessions (F = 0), S without the windows with no session.

People: only those kept by the feature pipeline (in *_features.csv: >= d + 2 usable days),
18 real and 196 MPUD, in every population and figure.

Inputs: data/processed/*_windows_{points,grid}.csv; for the sampling, the sessions
(*_preprocess_3.csv) and epochs (*_preprocess_2.csv), with the same masks as the window
pipeline (no_close sessions, missing-data intervals, days with enough data).
The usable 60-min grid windows are reported too (JSON + console) as a robustness check.

Usage:  python plots/pre_eda.py
Output: figures/pre_eda/P1.png, P2.png; data/reports/pre_eda_icc.json
"""
from __future__ import annotations

import json
import sys

import matplotlib.pyplot as plt
import numpy as np
import pandas as pd
from matplotlib.patches import Patch

from plot_style import BLUE, DPI, FIGURES, ORANGE, ROOT, set_title, style_bar_axes

sys.path.insert(0, str(ROOT))
from pipelines.labels.build_windows import EPOCH, seconds  # noqa: E402
from smartuse.coverage import day_coverage, missing_intervals  # noqa: E402
from smartuse.profile import usable  # noqa: E402
from smartuse.windows import person_windows  # noqa: E402

PROCESSED = ROOT / "data" / "processed"
OUT = FIGURES / "pre_eda"
REPORT = ROOT / "data" / "reports" / "pre_eda_icc.json"
DATASETS = {"real": ("Grupo de recolección", BLUE), "mpud": ("MPUD", ORANGE)}
MODES = {"points": "puntos de decisión", "siebers": "muestreo tipo Siebers"}
SIEBERS = {"f": 0.16, "s": 0.27}
SIEBERS_LABEL = "Siebers et al. (2024)"
VARS = {"f": "F (uso fragmentado)", "s": "S (uso sticky)"}
P = 0.80
N_BOOT = 2000
SEED = 0

# Siebers-like sampling and the window pipeline's coverage settings (build_windows defaults)
W_S = 3600.0
SIEBERS_N = 6
SIEBERS_FROM_H, SIEBERS_TO_H = 7, 22
MISSING_H = 10
MIN_DAY_HOURS = 6


def defined(df: pd.DataFrame, v: str) -> pd.DataFrame:
    """Windows where the metric exists: F needs >= 2 sessions, S at least one."""
    return df[df["n_sessions"] >= (2 if v == "f" else 1)]


def people(key: str) -> set:
    """The people kept by the feature pipeline (>= d + 2 usable days): 18 real, 196 MPUD."""
    return set(pd.read_csv(PROCESSED / f"{key}_features.csv", usecols=["user_id"]).user_id)


def load(key: str, mode: str) -> pd.DataFrame:
    w = pd.read_csv(PROCESSED / f"{key}_windows_{mode}.csv",
                    usecols=["user_id", "n_sessions", "f", "s", "masked", "censored", "day_ok"])
    return w[usable(w) & w["user_id"].isin(people(key))]


def sample_starts(day_local_midnight: float, rng: np.random.Generator) -> np.ndarray:
    """SIEBERS_N non-overlapping W windows, uniformly placed inside the day's allowed span:
    sorted uniform draws over the slack, then each window pushed by the ones before it."""
    lo = day_local_midnight + SIEBERS_FROM_H * 3600
    slack = (SIEBERS_TO_H - SIEBERS_FROM_H) * 3600 - SIEBERS_N * W_S
    return lo + np.sort(rng.uniform(0, slack, SIEBERS_N)) + np.arange(SIEBERS_N) * W_S


def load_siebers(key: str, rng: np.random.Generator) -> pd.DataFrame:
    sessions = pd.read_csv(PROCESSED / f"{key}_preprocess_3.csv",
                           usecols=["user_id", "start_utc", "start_local", "end_utc", "no_close"],
                           parse_dates=["start_utc", "start_local", "end_utc"])
    epochs = pd.read_csv(PROCESSED / f"{key}_preprocess_2.csv",
                         usecols=["user_id", "start_utc", "start_local", "is_use", "duration_s"],
                         parse_dates=["start_utc", "start_local"])
    missing = missing_intervals(epochs, MISSING_H * 3600)
    days = day_coverage(epochs, MIN_DAY_HOURS)
    ok_days = days[days["day_ok"]].groupby("user_id")["day"].apply(sorted)
    keep = people(key)
    parts = []
    for user_id, s in sessions.groupby("user_id", sort=True):
        if user_id not in keep or user_id not in ok_days.index:
            continue
        starts, ends = seconds(s["start_utc"]), seconds(s["end_utc"])
        offset_s = float((s["start_local"].iloc[0] - s["start_utc"].iloc[0]).total_seconds())
        midnights = [(pd.Timestamp(d) - EPOCH).total_seconds() - offset_s for d in ok_days[user_id]]
        times = np.concatenate([sample_starts(m, rng) for m in midnights])
        m = missing[missing["user_id"] == user_id]
        out = person_windows(starts, ends, s["no_close"].to_numpy(), float(starts.min()),
                             float(ends.max()), times, W_S,
                             missing_starts=seconds(m["start_utc"]), missing_ends=seconds(m["end_utc"]))
        out["user_id"] = user_id
        parts.append(out[~out["masked"] & ~out["censored"]])
    return pd.concat(parts, ignore_index=True)


def icc1(values: np.ndarray, groups: np.ndarray) -> float:
    """One-way random-effects ICC(1) for unbalanced groups (ANOVA estimator, n0 correction):
    (MSB - MSW) / (MSB + (n0 - 1) MSW)."""
    _, idx, n = np.unique(groups, return_inverse=True, return_counts=True)
    k, N = len(n), len(values)
    means = np.bincount(idx, weights=values) / n
    grand = values.mean()
    msb = (n * (means - grand) ** 2).sum() / (k - 1)
    msw = ((values - means[idx]) ** 2).sum() / (N - k)
    n0 = (N - (n ** 2).sum() / N) / (k - 1)
    return float((msb - msw) / (msb + (n0 - 1) * msw))


def boot_ci(df: pd.DataFrame, col: str, rng: np.random.Generator) -> tuple[float, float]:
    """Percentile 95 % CI, resampling people (with replacement) and keeping all their windows.
    A person drawn twice enters as two groups."""
    by_user = [g[col].to_numpy() for _, g in df.groupby("user_id")]
    stats = []
    for _ in range(N_BOOT):
        pick = rng.integers(0, len(by_user), len(by_user))
        vals = np.concatenate([by_user[i] for i in pick])
        grp = np.concatenate([np.full(len(by_user[i]), j) for j, i in enumerate(pick)])
        stats.append(icc1(vals, grp))
    lo, hi = np.percentile(stats, [2.5, 97.5])
    return float(lo), float(hi)


def compute() -> dict:
    rng = np.random.default_rng(SEED)
    sample_rng = np.random.default_rng(SEED + 1)
    out = {}
    for key in DATASETS:
        out[key] = {}
        for mode in ("points", "siebers", "grid"):
            df = load_siebers(key, sample_rng) if mode == "siebers" else load(key, mode)
            res = {"n_windows_all": len(df), "n_people": int(df.user_id.nunique())}
            for v in VARS:
                d = defined(df, v)
                lo, hi = boot_ci(d, v, rng)
                res[v] = {"icc": icc1(d[v].to_numpy(), d.user_id.to_numpy()), "ci95": [lo, hi],
                          "n_windows": len(d), "n_people": int(d.user_id.nunique())}
            out[key][mode] = res
            print(f"{key:4s} {mode:7s} all={res['n_windows_all']:>6} people={res['n_people']:>3}  "
                  + "  ".join(f"ICC {v.upper()} = {res[v]['icc']:.3f} "
                              f"[{res[v]['ci95'][0]:.3f}, {res[v]['ci95'][1]:.3f}] n={res[v]['n_windows']}"
                              for v in VARS))
    return out


def p1_icc(res: dict) -> None:
    fig, ax = plt.subplots(figsize=(9, 5), layout="constrained")
    x = np.arange(len(VARS))
    bars = [(key, mode) for mode in MODES for key in DATASETS]   # points: real, MPUD; then sampling
    width = 0.19
    for i, (key, mode) in enumerate(bars):
        color = DATASETS[key][1]
        r = res[key][mode]
        icc = np.array([r[v]["icc"] for v in VARS])
        ci = np.array([r[v]["ci95"] for v in VARS])
        pos = x + (i - (len(bars) - 1) / 2) * width
        sampled = mode == "siebers"
        ax.bar(pos, icc, width=width * 0.92, color=color, alpha=0.45 if sampled else 1.0,
               hatch="//" if sampled else None, edgecolor=color if sampled else None, linewidth=0)
        ax.errorbar(pos, icc, yerr=[icc - ci[:, 0], ci[:, 1] - icc], fmt="none",
                    ecolor="0.2", elinewidth=1.2, capsize=3)
        for p_, v_, top in zip(pos, icc, ci[:, 1]):
            ax.annotate(f"{v_:.2f}", xy=(p_, top), xytext=(0, 3), textcoords="offset points",
                        ha="center", va="bottom", fontsize=9, zorder=4,
                        bbox=dict(boxstyle="square,pad=0.1", facecolor="white", edgecolor="none"))
    for j, v in enumerate(VARS):
        ax.hlines(SIEBERS[v], j - 0.45, j + 0.45, color="0.25", linestyle="--", linewidth=1.3)
    handles = [plt.Line2D([], [], color="0.25", linestyle="--", linewidth=1.3, label=SIEBERS_LABEL)]
    for key, mode in bars:
        color = DATASETS[key][1]
        sampled = mode == "siebers"
        handles.append(Patch(facecolor=color, alpha=0.45 if sampled else 1.0,
                             hatch="//" if sampled else None, edgecolor=color if sampled else None,
                             linewidth=0, label=f"{DATASETS[key][0]}, {MODES[mode]}"))
    ax.set_xticks(x, [t.replace(" (", "\n(") for t in VARS.values()], fontsize=11)
    ax.set_xlim(-0.6, len(VARS) - 0.4)
    ax.set_ylabel("ICC(1)")
    ax.set_ylim(0, 1)
    ax.legend(handles=handles, frameon=False, loc="upper right", fontsize=9.5)
    style_bar_axes(ax)
    n_real = res["real"]["points"]["n_people"]
    sub = (f"({DATASETS['real'][0]}: {n_real} participantes; F en ventanas con ≥ 2 sesiones, "
           f"S en ventanas con ≥ 1 sesión)\n"
           f"(muestreo tipo Siebers: {SIEBERS_N} ventanas de 60 min al día entre las "
           f"{SIEBERS_FROM_H}:00 y las {SIEBERS_TO_H}:00; IC 95 % bootstrap por participante)")
    set_title(ax, "(P1) Correlación intraclase de F y S", sub, width=70)
    fig.savefig(OUT / "P1.png", dpi=DPI)
    plt.close(fig)


def person_panel(ax, df: pd.DataFrame, v: str, color: str) -> None:
    df = defined(df, v)
    q = df.groupby("user_id")[v].quantile(P).sort_values()
    data = [df.loc[df.user_id == u, v].to_numpy() for u in q.index]
    pos = np.arange(len(q))
    ax.boxplot(data, positions=pos, widths=0.6, patch_artist=True, showfliers=False,
               boxprops=dict(facecolor=color, edgecolor="0.3", alpha=0.45),
               medianprops=dict(color="0.15", linewidth=1.2),
               whiskerprops=dict(color="0.4"), capprops=dict(color="0.4"))
    ax.scatter(pos, q.values, marker="D", s=22, color="black", zorder=3,
               label="Percentil 80 del participante")
    ax.axhline(df[v].quantile(P), color="#d62728", linestyle="--", linewidth=1.4,
               label="Percentil 80 poblacional")
    ax.set_xticks(pos, [])
    ax.set_xlabel("Participante (ordenado por su percentil 80)")
    ax.set_ylabel(VARS[v])
    ax.set_title(VARS[v], fontsize=12)
    style_bar_axes(ax)


def p2_people(res: dict) -> None:
    df = load("real", "points")
    fig, axes = plt.subplots(1, 2, figsize=(13, 5), layout="constrained")
    for ax, v in zip(axes, VARS):
        person_panel(ax, df, v, BLUE)
        ax.set_title(f"{VARS[v]}, ICC = {res['real']['points'][v]['icc']:.2f}", fontsize=12)
    handles, labels = axes[0].get_legend_handles_labels()
    fig.legend(handles, labels, loc="outside lower center", ncols=2, frameon=False)
    n_f, n_s = len(defined(df, "f")), len(defined(df, "s"))
    fig.suptitle(f"(Grupo de recolección: {df.user_id.nunique()} participantes; puntos de decisión con F definida "
                 f"(≥ 2 sesiones): {n_f:,}, con S definida (≥ 1 sesión): {n_s:,}; "
                 "cajas sin valores atípicos)", fontsize=11, color="0.35")
    fig.savefig(OUT / "P2.png", dpi=DPI)
    plt.close(fig)


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    res = compute()
    res["siebers_2024"] = SIEBERS
    res["method"] = ("ICC(1), one-way random effects, ANOVA estimator with n0 for unbalanced "
                     f"groups; 95 % CI from {N_BOOT} person-level bootstrap resamples (seed {SEED}). "
                     "F on windows with >= 2 sessions, S on windows with >= 1 session. "
                     f"siebers: {SIEBERS_N} random non-overlapping 60-min windows per person and "
                     f"usable local day within {SIEBERS_FROM_H}:00-{SIEBERS_TO_H}:00 (seed {SEED + 1}), "
                     "without masked or censored windows")
    REPORT.parent.mkdir(parents=True, exist_ok=True)
    REPORT.write_text(json.dumps(res, indent=2))
    p1_icc(res)
    p2_people(res)


if __name__ == "__main__":
    main()
