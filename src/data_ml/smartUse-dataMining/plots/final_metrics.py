"""Deployment performance metrics (inference group only).

Input:  data/deployment_data/final_metrics_inference.csv  (plots/clean_final_answers.py)
Usage:  python plots/final_metrics.py
Output: figures/final_metrics/*.png
"""
from __future__ import annotations

import hashlib
from pathlib import Path

import matplotlib.pyplot as plt
import numpy as np
import pandas as pd
from matplotlib.cbook import boxplot_stats
from matplotlib.patches import Patch
from matplotlib.ticker import FuncFormatter, LogLocator

from plot_style import (BLUE, DATA, DPI, FIGURES, ORANGE, SINGLE_SIZE, TITLE_WRAP, set_title, suptitle,
                        style_bar_axes)

METRICS = DATA / "final_metrics_inference.csv"
OUT = FIGURES / "final_metrics"

# decision_point flag -> (legend label, color); order = order within each participant
FLAGS = {"active_use": ("Uso continuo", BLUE), "screen_on": ("Inicio de sesión", ORANGE)}


def participant_order(df: pd.DataFrame) -> list[str]:
    """Participants ordered by signup time, so P1..Pn is stable and anonymous."""
    users = pd.read_csv(DATA / "users.csv", encoding="utf-8-sig", parse_dates=["signup_ts"])
    signup = users.set_index("user_id").signup_ts
    return sorted(df.user_id.unique(), key=lambda u: signup.get(u, pd.Timestamp.max))


FIG_SIZE = (8, 4.6)     # ~1:2 (height:width), a bit taller
BOX_WIDTH = 0.4


def box_stats(values: pd.Series, log: bool) -> dict:
    """Tukey box stats (whiskers at 1.5 IQR). With log=True they are computed on log10 of the
    values and mapped back, so whiskers/outliers match what a log axis shows."""
    v = np.asarray(values, dtype=float)
    if not log:
        return boxplot_stats(v)[0]
    s = boxplot_stats(np.log10(v))[0]
    for k in ("mean", "med", "q1", "q3", "whislo", "whishi", "cilo", "cihi"):
        s[k] = 10 ** s[k]
    s["fliers"] = 10 ** np.asarray(s["fliers"])
    return s


def participant_boxes(df: pd.DataFrame, column: str, title: str, ylabel: str, path: Path,
                      log: bool = False, ymax: float | None = None,
                      ref_lines: list[tuple[float, str]] = (),
                      overflow_fmt=lambda v: f"{v:.0f}", yformatter=None) -> None:
    """Box plots of `column` per participant, one box per decision-point flag.

    Values above `ymax` can't be drawn; they get an arrow at the top edge labelled with
    overflow_fmt(value). Reference lines are drawn only if some record reaches them.
    """
    users = participant_order(df)
    fig, ax = plt.subplots(figsize=FIG_SIZE, layout="constrained")
    for j, (flag, (label, color)) in enumerate(FLAGS.items()):
        data = [df.loc[(df.user_id == u) & (df.decision_point == flag), column].dropna()
                for u in users]
        pos = np.arange(len(users)) + (j - 0.5) * BOX_WIDTH * 1.08
        if ymax is not None:
            for p, d in zip(pos, data):
                for v in d[d > ymax]:
                    ax.annotate(overflow_fmt(v), xy=(p, ymax), xytext=(0, -14),
                                textcoords="offset points", ha="center", va="top", fontsize=9,
                                color="0.3", arrowprops=dict(arrowstyle="->", color=color, lw=1.2))
        keep = [k for k, d in enumerate(data) if len(d)]
        stats = [box_stats(data[k], log) for k in keep]
        ax.bxp(stats, positions=pos[keep], widths=BOX_WIDTH, patch_artist=True,
               boxprops=dict(facecolor=color, edgecolor="0.25", alpha=0.85),
               medianprops=dict(color="black", linewidth=1.5),
               whiskerprops=dict(color="0.25"), capprops=dict(color="0.25"),
               flierprops=dict(marker="o", markersize=2.5, markerfacecolor=color,
                               markeredgecolor="none", alpha=0.4))

    if log:
        ax.set_yscale("log")
        ax.yaxis.set_major_locator(LogLocator(base=10, numticks=30))  # every decade
    if yformatter is not None:
        ax.yaxis.set_major_formatter(FuncFormatter(yformatter))
    top = df[column].max()
    for y, text in ref_lines:
        if y <= top:
            ax.axhline(y, color="0.3", linestyle="--", linewidth=1, zorder=0)
            ax.annotate(text, xy=(1, y), xycoords=("axes fraction", "data"),
                        xytext=(4, 0), textcoords="offset points", va="center", fontsize=10,
                        color="0.3")
    ax.set_xticks(np.arange(len(users)), [f"P{i + 1}" for i in range(len(users))])
    ax.tick_params(axis="x", labelsize=10)
    ax.set_xlabel("Participante")
    ax.set_ylabel(ylabel + ("\ncuartiles y bigotes (1.5·RIC) sobre log₁₀" if log else ""))
    ax.legend(handles=[Patch(facecolor=c, edgecolor="0.25", label=l) for l, c in FLAGS.values()],
              frameon=False, loc="upper right", ncols=2)
    if ymax is None:  # headroom for the legend
        ymax = top * (10 ** 1.3 if log else 1.2)
    ax.set_ylim(top=ymax)
    style_bar_axes(ax)
    set_title(ax, title, int(df[column].notna().sum()),
              width=int(TITLE_WRAP * FIG_SIZE[0] / SINGLE_SIZE[0] * 1.15))
    path.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(path, dpi=DPI)
    plt.close(fig)


def r1_durations(df: pd.DataFrame) -> None:
    participant_boxes(df, "duration_ms",
                      "(R1) Duración de los puntos de decisión por participante",
                      "Duración (ms, escala log)", OUT / "R1.png", log=True, ymax=1e5,
                      ref_lines=[(1_000, "1 s"), (10_000, "10 s")],
                      overflow_fmt=lambda v: f"{v / 1000:.0f} s")


def r2_memory(df: pd.DataFrame) -> None:
    df = df.assign(peak_rss_mb=df.peak_rss_kb / 1024)
    participant_boxes(df, "peak_rss_mb",
                      "(R2) Memoria máxima utilizada por participante",
                      "Memoria máxima (MB)", OUT / "R2.png")
    print("peak MB:", df.groupby("decision_point").peak_rss_mb.describe().round(1).to_string())


SI_PREFIX = {0: "", -3: "m", -6: "µ", -9: "n", -12: "p", 3: "k"}
SUPERSCRIPT = str.maketrans("-0123456789", "⁻⁰¹²³⁴⁵⁶⁷⁸⁹")


def si_decade_label(unit: str):
    """Formatter: '10⁻⁶ (µ<unit>)' on decades with an SI prefix; other decades unlabelled."""
    def fmt(y: float, _pos) -> str:
        e = int(round(np.log10(y)))
        return f"10{str(e).translate(SUPERSCRIPT)} ({SI_PREFIX[e]}{unit})" if e in SI_PREFIX else ""
    return fmt


# --- current_mean units -------------------------------------------------------------------
# Android's BatteryManager CURRENT_NOW is documented in µA, but some vendors report mA.
# The devices split into two groups ~1000x apart (see figures/final_metrics/explore/):
# a device whose median |current| (discharging rows) is >= UNIT_THRESHOLD reports µA,
# below it mA. The groups sit at ~10^5-10^6.5 vs ~10^1-10^3.4, so 10^4 has wide margins.
UNIT_THRESHOLD = 1e4
UNIT_COLORS = {"µA": "#6a3d9a", "mA": "#33a02c"}          # purple / green
UNIT_SHADE = {"mA": ("#33a02c", (0, 4)), "µA": ("#6a3d9a", (4, 8.5))}  # log10 spans


def device_units(df: pd.DataFrame) -> dict[str, str]:
    """user_id -> 'µA' or 'mA', from the median |current_mean| of discharging rows."""
    med = df[df.current_mean < 0].groupby("user_id").current_mean.median().abs()
    return {u: ("µA" if m >= UNIT_THRESHOLD else "mA") for u, m in med.items()}


def current_ma(df: pd.DataFrame) -> pd.Series:
    """current_mean normalised to mA (µA devices / 1000)."""
    units = device_units(df)
    scale = df.user_id.map({u: (1e-3 if unit == "µA" else 1.0) for u, unit in units.items()})
    return df.current_mean * scale


def shade_unit_zones(ax) -> None:
    for unit, (color, (lo, hi)) in UNIT_SHADE.items():
        ax.axvspan(lo, hi, color=color, alpha=0.08, zorder=0)
        ax.annotate(f"zona {unit}", xy=((lo + hi) / 2, 1), xycoords=("data", "axes fraction"),
                    xytext=(0, -4), textcoords="offset points", ha="center", va="top",
                    fontsize=10, color=color, fontweight="bold")


def explore_current(df: pd.DataFrame) -> None:
    """Exploration figures for the current_mean unit issue (not thesis figures)."""
    df = df[df.current_mean.notna() & (df.current_mean != 0)]
    users = participant_order(df)
    units = device_units(df)
    logabs = np.log10(df.current_mean.abs())
    bins = np.arange(0, 8.01, 0.1)
    out = OUT / "explore"
    out.mkdir(parents=True, exist_ok=True)
    xlabel = "log₁₀ |current_mean| (valor crudo)"

    # 1) per participant, by sign
    fig, axes = plt.subplots(4, 2, figsize=(12, 12), layout="constrained", sharex=True)
    for i, (ax, u) in enumerate(zip(axes.flat, users)):
        m = df.user_id == u
        c = df.current_mean[m]
        shade_unit_zones(ax)
        ax.hist(logabs[m & (df.current_mean < 0)], bins=bins, color=BLUE, alpha=0.85,
                label=f"negativo (n={int((c < 0).sum())})")
        ax.hist(logabs[m & (df.current_mean > 0)], bins=bins, color=ORANGE, alpha=0.9,
                label=f"positivo (n={int((c > 0).sum())})")
        ax.set_title(f"P{i + 1}  (se presume {units[u]})", fontsize=12)
        ax.legend(frameon=False, fontsize=9, loc="center left")
        ax.spines[["top", "right"]].set_visible(False)
        ax.set_ylim(top=ax.get_ylim()[1] * 1.12)
    for ax in axes[-1]:
        ax.set_xlabel(xlabel)
    for ax in axes[:, 0]:
        ax.set_ylabel("Registros")
    suptitle(fig, "Distribución de current_mean por participante", fontsize=14)
    fig.savefig(out / "current_mean_hist.png", dpi=DPI // 2)
    plt.close(fig)

    # 2) all data, colored by presumed unit
    group = df.user_id.map(units)
    fig, ax = plt.subplots(figsize=(8, 4.6), layout="constrained")
    shade_unit_zones(ax)
    for unit, color in UNIT_COLORS.items():
        sel = group == unit
        who = ", ".join(f"P{users.index(u) + 1}" for u in users if units[u] == unit)
        ax.hist(logabs[sel], bins=bins, color=color, alpha=0.85,
                label=f"se presume {unit}: {who} (n={int(sel.sum())})")
    ax.axvline(np.log10(UNIT_THRESHOLD), color="0.3", ls="--", lw=1)
    ax.set_xlabel(xlabel)
    ax.set_ylabel("Registros")
    ax.set_ylim(top=ax.get_ylim()[1] * 1.25)
    ax.legend(frameon=False, loc="upper left", bbox_to_anchor=(0, 0.92), fontsize=10)
    style_bar_axes(ax)
    set_title(ax, "Distribución de current_mean, todos los participantes", len(df), width=70)
    fig.savefig(out / "current_mean_hist_all.png", dpi=DPI)
    plt.close(fig)


def r4_charge(df: pd.DataFrame) -> None:
    """R4: battery charge drawn per decision point, in Ah.

    current is first normalised to mA per device (current_ma), then
    charge_Ah = |current [mA]| * 1e-3 * duration_ms / 3_600_000.
    Only discharging rows (current_mean < 0) are kept; positive values mean the phone
    was charging, and zero / missing currents can't go on a log axis.
    """
    neg = df[df.current_mean < 0]
    neg = neg.assign(charge_ah=-current_ma(neg) * 1e-3 * neg.duration_ms / 3_600_000)
    participant_boxes(neg, "charge_ah",
                      "(R4) Carga consumida por punto de decisión por participante",
                      "Carga consumida (Ah, escala log)", OUT / "R4.png", log=True,
                      yformatter=si_decade_label("Ah"))
    print(f"R4: kept {len(neg)} of {len(df)} rows (current_mean < 0); "
          f"median Ah by flag: {neg.groupby('decision_point').charge_ah.median().to_dict()}")


def r3_current_sign(df: pd.DataFrame) -> None:
    """R3: per participant, share of records with negative (discharging) vs positive
    (charging) current_mean, as 100 % stacked bars. Zero / missing currents are left out."""
    df = df[df.current_mean.notna() & (df.current_mean != 0)]
    users = participant_order(df)
    total = df.groupby("user_id").size().reindex(users)
    groups = [("Corriente negativa", df.current_mean < 0, BLUE, "white"),
              ("Corriente positiva", df.current_mean > 0, ORANGE, "black")]
    x = np.arange(len(users))
    bottom = np.zeros(len(users))
    fig, ax = plt.subplots(figsize=FIG_SIZE, layout="constrained")
    for label, mask, color, text_color in groups:
        pct = (df[mask].groupby("user_id").size().reindex(users, fill_value=0) / total * 100).values
        rects = ax.bar(x, pct, bottom=bottom, width=0.6, color=color, label=label,
                       edgecolor="white", linewidth=1)
        # label inside the segment when it is tall enough, otherwise just above the bar
        inside = [f"{p:.1f}%" if p >= 6 else "" for p in pct]
        ax.bar_label(rects, labels=inside, label_type="center", fontsize=9, color=text_color)
        for xi, p, b in zip(x, pct, bottom):
            if 0 < p < 6:
                ax.annotate(f"{p:.1f}%", xy=(xi, b + p), xytext=(0, 2), textcoords="offset points",
                            ha="center", va="bottom", fontsize=9)
        bottom += pct
    ax.set_xticks(x, [f"P{i + 1}\n(n={total[u]})" for i, u in enumerate(users)])
    ax.tick_params(axis="x", labelsize=10)
    ax.set_xlabel("Participante")
    ax.set_ylabel("Registros (%)")
    ax.set_ylim(0, 118)
    ax.set_yticks(range(0, 101, 20))
    ax.legend(frameon=False, loc="upper right", ncols=2)
    style_bar_axes(ax)
    set_title(ax, "(R3) Signo de la corriente media por participante", len(df),
              width=int(TITLE_WRAP * FIG_SIZE[0] / SINGLE_SIZE[0] * 1.15))
    fig.savefig(OUT / "R3.png", dpi=DPI)
    plt.close(fig)


LOCAL_TZ = "America/Guatemala"  # timestamps in the exports are UTC
ERR_DAYS = 3                    # error bar: rate with D-1 .. D-3 days as denominator
SHORT_STAY_DAYS = ERR_DAYS + 1  # with fewer days the D-3 bound is meaningless -> no error bar
# Manual corrections to the computed day count (pseudonym -> days; see pseudonym()).
# P2 sent the final form 2 days late (local dates give 17); the study period was 15 days.
DAY_OVERRIDES = {"p82ff4020": 15}


def pseudonym(user_id: str) -> str:
    """Public alias of a participant id, as in experiments/common.py."""
    return "p" + hashlib.sha256(str(user_id).encode()).hexdigest()[:8]


def study_days() -> pd.Series:
    """user_id -> whole days in the study: local date of the final form send minus local
    date of signup. A count of 15 is taken as 14 (the send lands on the day after the
    last full day)."""
    users = pd.read_csv(DATA / "users.csv", encoding="utf-8-sig", parse_dates=["signup_ts"])
    sends = pd.read_csv(DATA / "final_answers_inference.csv", parse_dates=["received_at"])
    start = users.set_index("user_id").signup_ts
    end = sends.groupby("user_id").received_at.first()

    def local_day(t: pd.Series) -> pd.Series:
        return t.dt.tz_localize("UTC").dt.tz_convert(LOCAL_TZ).dt.normalize()

    days = (local_day(end) - local_day(start.reindex(end.index))).dt.days
    days = days.where(days != 15, 14)
    alias = days.index.map(pseudonym)
    for key, d in DAY_OVERRIDES.items():
        days[alias == key] = d
    return days


SIGNS = {"neg": ("Sin carga (corriente negativa)", BLUE),
         "pos": ("En carga (corriente positiva)", ORANGE)}


def mark_dropouts(ax, x: np.ndarray, heights: np.ndarray, width: float) -> None:
    """Early dropouts: hatched overlay + dashed outline (no legend entry)."""
    for xi, h in zip(x, heights):
        ax.bar(xi, h, width=width, fill=False, hatch="///", edgecolor="0.15", linewidth=0)
        ax.bar(xi, h, width=width, fill=False, edgecolor="0.15", linewidth=1.3, linestyle="--")


def day_labels(days: pd.Series, short: np.ndarray) -> list[str]:
    return [f"P{i + 1}{'*' if s else ''}\n({d} días)" for i, (d, s) in enumerate(zip(days, short))]


def sign_counts(df: pd.DataFrame, users: list[str]) -> dict[str, np.ndarray]:
    """Records per participant with negative / positive current (zero & missing excluded)."""
    def per_user(mask):
        return df[mask].groupby("user_id").size().reindex(users, fill_value=0).values
    return {"neg": per_user(df.current_mean < 0), "pos": per_user(df.current_mean > 0)}


def r6_rate(df: pd.DataFrame) -> None:
    """R6: decision points per day per participant, stacked by current sign
    (negative = not charging, positive = charging; zero / missing current excluded).

    rate = entries / D. Error bar on the total spans the rate with D-1 .. D-3 days (upper
    end N/(D-3)), covering partial first/last days. Participants with < SHORT_STAY_DAYS
    days get no error bar, an asterisk and a hatched bar."""
    users = participant_order(df)
    days = study_days().reindex(users)
    counts = sign_counts(df, users)
    x = np.arange(len(users))
    bottom = np.zeros(len(users))
    fig, ax = plt.subplots(figsize=FIG_SIZE, layout="constrained")
    for key, (label, color) in SIGNS.items():
        rate = counts[key] / days.values
        ax.bar(x, rate, bottom=bottom, width=0.6, color=color, label=label,
               edgecolor="white", linewidth=1)
        bottom += rate
    total_n = counts["neg"] + counts["pos"]
    short = (days < SHORT_STAY_DAYS).values
    mark_dropouts(ax, x[short], bottom[short], 0.6)
    upper = np.where(short, 0, total_n / (days.values - ERR_DAYS).clip(1) - bottom)
    ax.errorbar(x[~short], bottom[~short], yerr=[np.zeros((~short).sum()), upper[~short]],
                fmt="none", ecolor="0.2", elinewidth=1.2, capsize=4)
    for xi, b, up, s in zip(x, bottom, upper, short):
        ax.annotate(f"{b:.1f}" + ("\n(retiro anticipado)" if s else ""), xy=(xi, b + up),
                    xytext=(0, 3), textcoords="offset points", ha="center", va="bottom",
                    fontsize=9, fontstyle="italic" if s else "normal")
    ax.set_xticks(x, day_labels(days, short))
    ax.tick_params(axis="x", labelsize=10)
    ax.set_xlabel("Participante")
    ax.set_ylabel("Puntos de decisión por día")
    ax.set_ylim(0, (bottom + upper).max() * 1.2)
    ax.legend(frameon=False, loc="upper right", ncols=2, fontsize=10)
    style_bar_axes(ax)
    set_title(ax, "(R6) Puntos de decisión por día por participante", int(total_n.sum()),
              width=int(TITLE_WRAP * FIG_SIZE[0] / SINGLE_SIZE[0] * 1.15))
    fig.savefig(OUT / "R6.png", dpi=DPI)
    plt.close(fig)
    print("R6 days:", {f"P{i + 1}": int(d) for i, d in enumerate(days)},
          "rate:", {f"P{i + 1}": round(b, 1) for i, b in enumerate(bottom)})


BATTERY_MAH = (3300, 5000)  # reference batteries (100 %): left axis, right axis
N_BOOT = 2000               # bootstrap resamples for the CI of the median charge
R7_COLOR = BLUE
R7_VALUE_LABELS = False  # True: "5000–3300 mAh" range above each bar


def median_ci(values: np.ndarray, rng: np.random.Generator) -> tuple[float, float, float]:
    """Median and 95 % bootstrap percentile CI of the median."""
    boots = np.median(rng.choice(values, size=(N_BOOT, len(values)), replace=True), axis=1)
    return float(np.median(values)), *np.percentile(boots, [2.5, 97.5])


def r7_daily_battery(df: pd.DataFrame) -> None:
    """R7: estimated battery used per day by the app, as % of a reference battery.

    pct/day = median charge per decision point [Ah] (R4: both types, current < 0)
              x decision points per day, all records (R6 total, both signs)
              / battery [Ah] * 100.
    Left axis: 3300 mAh battery; right axis: the same bars against 5000 mAh.

    Error bar (upward only, like R6): relative errors in quadrature,
    df/f = sqrt((da/a)^2 + (db/b)^2), with
      da = upper 95 % bootstrap CI of the median - median,
      db = N/(D-3) - N/D.
    Early dropouts (< SHORT_STAY_DAYS days) get no error bar.
    """
    rng = np.random.default_rng(0)
    users = participant_order(df)
    days = study_days().reindex(users)
    short = (days < SHORT_STAY_DAYS).values
    counts = sign_counts(df, users)
    n = counts["neg"] + counts["pos"]

    neg = df[df.current_mean < 0]
    neg = neg.assign(charge_ah=-current_ma(neg) * 1e-3 * neg.duration_ms / 3_600_000)
    med, _, hi = np.array([median_ci(neg.loc[neg.user_id == u, "charge_ah"].to_numpy(), rng)
                           for u in users]).T

    rate = n / days.values
    rate_up = n / (days.values - ERR_DAYS).clip(1) - rate
    small, big = BATTERY_MAH
    pct = med * rate / (small / 1000) * 100                  # % of the 3300 mAh battery
    up = np.where(short, 0, pct * np.sqrt(((hi - med) / med) ** 2 + (rate_up / rate) ** 2))

    x = np.arange(len(users))
    fig, ax = plt.subplots(figsize=FIG_SIZE, layout="constrained")
    ax.bar(x, pct, width=0.6, color=R7_COLOR, edgecolor="white", linewidth=1)
    mark_dropouts(ax, x[short], pct[short], 0.6)
    ax.errorbar(x[~short], pct[~short], yerr=[np.zeros((~short).sum()), up[~short]],
                fmt="none", ecolor="0.2", elinewidth=1.2, capsize=4)
    for xi, p, u, s in zip(x, pct, up, short):
        if R7_VALUE_LABELS:
            # ranges that collapse at 2 decimals (both ends <= 0.01 %) are shown as "~0.01%"
            rng_txt = "~0.01%" if round(p, 2) <= 0.01 else f"{p * small / big:.2f}–{p:.2f}%"
            label = rng_txt + ("\n(retiro anticipado)" if s else "")
        elif s:
            label = "(retiro anticipado)"
        else:
            continue
        ax.annotate(label, xy=(xi, p + u), xytext=(0, 3), textcoords="offset points",
                    ha="center", va="bottom", fontsize=8.5,
                    fontstyle="italic" if s else "normal")
    ax.set_xticks(x, day_labels(days, short))
    ax.tick_params(axis="x", labelsize=10)
    ax.set_xlabel("Participante")
    ax.set_ylabel(f"Batería por día (% de {small} mAh)")
    ax.set_ylim(0, (pct + up).max() * 1.2)
    right = ax.secondary_yaxis("right", functions=(lambda y: y * small / big,
                                                   lambda y: y * big / small))
    right.set_ylabel(f"Batería por día (% de {big} mAh)")
    style_bar_axes(ax)
    ax.spines["right"].set_visible(True)
    set_title(ax, "(R7) Consumo diario estimado de batería por participante", int(n.sum()),
              width=int(TITLE_WRAP * FIG_SIZE[0] / SINGLE_SIZE[0] * 1.15))
    fig.savefig(OUT / "R7.png", dpi=DPI)
    plt.close(fig)
    for i in range(len(users)):
        print(f"R7 P{i + 1}: {pct[i] * small / big:.3f}-{pct[i]:.3f} %/d  (+{up[i]:.3f} on 3300)")


def r5_current(df: pd.DataFrame) -> None:
    """R5: mean discharge current per decision point, in A (unit-normalised, current_mean < 0)."""
    neg = df[df.current_mean < 0]
    neg = neg.assign(current_a=-current_ma(neg) * 1e-3)
    participant_boxes(neg, "current_a",
                      "(R5) Corriente media por punto de decisión por participante",
                      "Corriente media (A, escala log)", OUT / "R5.png", log=True,
                      yformatter=si_decade_label("A"))
    print(f"R5: median A by flag: {neg.groupby('decision_point').current_a.median().round(3).to_dict()}")


def summary_table(df: pd.DataFrame) -> None:
    """CSV with one row per participant and decision-point type (plus 'todos'):
    performance, memory, current, charge and the R6 / R7 daily estimates.

    Same rules as the figures: current normalised to mA per device and only discharging
    records (current < 0) for current and charge; rate = records with current != 0 / D
    (R6); daily battery = median charge x rate / battery (R7), upper error from the
    bootstrap CI of the median and N/(D-3) in quadrature, none for early dropouts.
    Durations are raw (the 477 s record of P2 is included)."""
    rng = np.random.default_rng(0)
    users = participant_order(df)
    days = study_days()
    neg_all = df[df.current_mean < 0]
    neg_all = neg_all.assign(current_ma=-current_ma(neg_all))
    neg_all = neg_all.assign(charge_mah=neg_all.current_ma * neg_all.duration_ms / 3_600_000)
    small, big = BATTERY_MAH
    groups = [(flag, label) for flag, (label, _) in FLAGS.items()] + [(None, "Todos")]
    rows = []
    for i, u in enumerate(users):
        d = int(days[u])
        short = d < SHORT_STAY_DAYS
        for flag, label in groups:
            sel = (df.user_id == u) & (df.decision_point == flag if flag else True)
            g = df[sel]
            neg = neg_all[(neg_all.user_id == u) &
                          ((neg_all.decision_point == flag) if flag else True)]
            n_rate = int((g.current_mean.notna() & (g.current_mean != 0)).sum())
            mem = g.peak_rss_kb / 1024
            med, _, hi = median_ci(neg.charge_mah.to_numpy(), rng) if len(neg) else (np.nan,) * 3
            rate = n_rate / d
            rate_up = n_rate / max(d - ERR_DAYS, 1) - rate
            rel_up = np.sqrt(((hi - med) / med) ** 2 + (rate_up / rate) ** 2) if rate else np.nan
            pct = {b: med * rate / b * 100 for b in BATTERY_MAH}
            rows.append({
                "participante": f"P{i + 1}",
                "tipo_punto_decision": label,
                "dias_estudio": d,
                "retiro_anticipado": short,
                "n_registros": len(g),
                "duracion_mediana_ms": g.duration_ms.median(),
                "duracion_p90_ms": g.duration_ms.quantile(0.9),
                "duracion_max_ms": g.duration_ms.max(),
                "prop_menos_1s": (g.duration_ms < 1000).mean(),
                "memoria_mediana_mb": mem.median(),
                "memoria_p90_mb": mem.quantile(0.9),
                "memoria_p90_sobre_mediana": mem.quantile(0.9) / mem.median(),
                "n_registros_descarga": len(neg),
                "corriente_mediana_ma": neg.current_ma.median(),
                "carga_mediana_mah": med,
                f"carga_mediana_pct_{small}mah": med / small * 100,
                f"carga_mediana_pct_{big}mah": med / big * 100,
                "puntos_decision_por_dia": rate,
                f"bateria_dia_pct_{small}mah": pct[small],
                f"bateria_dia_pct_{small}mah_con_error": np.nan if short else pct[small] * (1 + rel_up),
                f"bateria_dia_pct_{big}mah": pct[big],
                f"bateria_dia_pct_{big}mah_con_error": np.nan if short else pct[big] * (1 + rel_up),
            })
    out = pd.DataFrame(rows)
    path = OUT / "resumen_por_participante.csv"
    out.to_csv(path, index=False, float_format="%.6g", encoding="utf-8")
    print(f"summary: {len(out)} rows -> {path}")


def main() -> None:
    df = pd.read_csv(METRICS)
    df = df[df.decision_point.isin(FLAGS)]
    r1_durations(df)
    r2_memory(df)
    r3_current_sign(df)
    r4_charge(df)
    r5_current(df)
    r6_rate(df)
    r7_daily_battery(df)
    summary_table(df)
    explore_current(df)
    print("units:", {f"P{i + 1}": device_units(df)[u] for i, u in enumerate(participant_order(df))})
    for flag, g in df.groupby("decision_point"):
        print(f"{flag}: n={len(g)}  >1s={int((g.duration_ms > 1000).sum())}  "
              f">10s={int((g.duration_ms > 10000).sum())}")


if __name__ == "__main__":
    main()
