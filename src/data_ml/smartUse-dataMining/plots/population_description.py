"""Description of the population: participants, participation length, age, major, gender and
SAS-SV score.

Inputs: data/deployment_data/users.csv, data/usage/real_data.csv,
        data/deployment_data/final_answers_inference.csv, final_metrics_inference.csv
Usage:  python plots/population_description.py
Output: figures/population/{participacion,demografia}_{recoleccion,despliegue}.png, sas_sv.png

Open-text fields (major, gender) were coded by reading every answer; the coding is the
explicit MAJOR_CODES / GENDER_CODES tables below, so it can be reviewed.

Duration:
  - Recolección (control): signup_ts -> last record in real_data.csv (no final form).
  - Despliegue (inference): signup_ts -> received_at of the final form, capped at the end of
    the deployment (DEPLOYMENT_END, end of 2026-11-04 local): the final form could be sent
    later than the last day of participation. Only participants who sent it (the others have
    no end date).
Dates in the subtitles are local (UTC-6).
"""
from __future__ import annotations

import re
import unicodedata

import matplotlib.pyplot as plt
import numpy as np
import pandas as pd

from plot_style import (BLUE, DATA, FIGURES, ORANGE, ROOT, annotate_n, new_single, save, set_title,
                        style_bar_axes, suptitle)
from population import counts, draw_participants

REAL = ROOT / "data" / "usage" / "real_data.csv"
FINAL = DATA / "final_answers_inference.csv"
OUT = FIGURES / "population"
UTC_OFFSET = pd.Timedelta(hours=6)

GROUPS = {"control": ("Recolección (control)", BLUE), "inference": ("Despliegue (inferencia)", ORANGE)}

CS = "Ciencias de la Computación\n(CCTI)"
OTHER = "Otra"
# normalized answer (lowercase, no accents, single spaces) -> category. Every answer names
# Ciencia(s) de la Computación (y TI) or its acronym. "ciencias de la comunicacion y
# tecnologias de la informacion" is read as a typo of CCTI: no such major exists and the
# rest of the name matches.
MAJOR_CODES = {
    "ccti": CS, "cc ti": CS, "ingenieria ccti": CS, "computacion": CS,
    "ingenieria en computacion": CS,
    "ciencias de la comunicacion y tecnologias de la informacion": CS,
}
MAJOR_CS_PATTERN = re.compile(r"ciencias? de (la )?computacion")

MALE, FEMALE = "Masculino", "Femenino"
GENDER_CODES = {"masculino": MALE, "hombre": MALE, "m": MALE, "femenino": FEMALE}

# Deployment window, local dates converted to UTC. Two inference signups fall on the evening
# of 2026-10-21 local (registration error): their start is moved to the start of 2026-10-22.
DEPLOYMENT_START = pd.Timestamp("2026-10-22 00:00") + UTC_OFFSET
DEPLOYMENT_END = pd.Timestamp("2026-11-04 23:59:59") + UTC_OFFSET

# SAS-SV: one general cutoff
SAS_CUTOFF = 36


def normalize(text: str) -> str:
    text = unicodedata.normalize("NFKD", str(text)).encode("ascii", "ignore").decode()
    return " ".join(text.lower().split())


def code_major(text: str) -> str:
    t = normalize(text)
    if t in MAJOR_CODES:
        return MAJOR_CODES[t]
    return CS if MAJOR_CS_PATTERN.search(t) else OTHER


def code_gender(text: str) -> str:
    return GENDER_CODES[normalize(text)]   # KeyError = an answer not coded yet


def load_users() -> pd.DataFrame:
    users = pd.read_csv(DATA / "users.csv", encoding="utf-8-sig", parse_dates=["signup_ts"])
    users["major"] = users["university_major"].map(code_major)
    users["gender_c"] = users["gender"].map(code_gender)
    return users


FILE = {"control": "recoleccion", "inference": "despliegue"}


def category_bars(ax, values: pd.Series, categories: list[str], color: str) -> None:
    counts = values.value_counts()
    vals = [int(counts.get(c, 0)) for c in categories]
    ax.bar(np.arange(len(categories)), vals, 0.6, color=color, zorder=2)
    for p, v in enumerate(vals):
        ax.text(p, v + 0.3, str(v), ha="center", va="bottom", fontsize=10)
    ax.set_xticks(np.arange(len(categories)), categories)
    ax.set_xlim(-0.7, len(categories) - 0.3)


def demographics_figure(users: pd.DataFrame) -> None:
    """Per group: major, gender and age at signup side by side, each panel on its own y scale."""
    ages = users["age_at_signup"]
    age_bins = np.arange(ages.min() - 0.5, ages.max() + 1.5)
    for g, (label, color) in GROUPS.items():
        u = users[users.experiment_group == g]
        a = u["age_at_signup"]
        print(f"age {g}: n={len(a)} min={a.min()} max={a.max()} median={a.median()} mean={a.mean():.1f}")
        fig, axes = plt.subplots(1, 3, figsize=(12, 4), layout="constrained",
                                 gridspec_kw={"width_ratios": [1, 1, 1.6]})
        category_bars(axes[0], u["major"], [CS, OTHER], color)
        axes[0].set_title("Carrera\n", fontsize=11)
        category_bars(axes[1], u["gender_c"], [MALE, FEMALE], color)
        axes[1].set_title("Género\n", fontsize=11)
        axes[2].hist(a, bins=age_bins, color=color, edgecolor="white", zorder=2)
        axes[2].set_xticks(np.arange(ages.min(), ages.max() + 1))
        axes[2].set_xlabel("Edad (años)")
        axes[2].set_title("Edad\n", fontsize=11)
        annotate_n(axes[2], f"mediana = {a.median():g} años")
        for ax in axes:
            style_bar_axes(ax)
            ax.set_ylim(0, ax.get_ylim()[1] * 1.1)   # headroom for the bar labels
            ax.yaxis.get_major_locator().set_params(integer=True)
            ax.set_ylabel("Participantes")
        suptitle(fig, f"Carrera, género y edad: {label} (n = {len(u)})")
        save(fig, OUT / f"demografia_{FILE[g]}.png")


def durations(users: pd.DataFrame) -> dict[str, pd.DataFrame]:
    """Per group: user_id, start, end (UTC) and days."""
    signup = users.set_index("user_id")["signup_ts"]
    real = pd.read_csv(REAL, usecols=["user_id", "start_ts"], parse_dates=["start_ts"])
    control_ids = users.loc[users.experiment_group == "control", "user_id"]
    ctrl_end = real[real.user_id.isin(control_ids)].groupby("user_id")["start_ts"].max()
    final = pd.read_csv(FINAL, usecols=["user_id", "received_at"], parse_dates=["received_at"])
    inf_end = final.groupby("user_id")["received_at"].max().clip(upper=DEPLOYMENT_END)
    out = {}
    for g, end in (("control", ctrl_end), ("inference", inf_end)):
        start = signup.reindex(end.index)
        if g == "inference":
            start = start.clip(lower=DEPLOYMENT_START)
        df = pd.DataFrame({"start": start, "end": end})
        df["days"] = (df["end"] - df["start"]).dt.total_seconds() / 86400
        # local calendar days touched, first and last included (22/10 - 04/11 = 14)
        df["calendar_days"] = ((df["end"] - UTC_OFFSET).dt.normalize()
                               - (df["start"] - UTC_OFFSET).dt.normalize()).dt.days + 1
        out[g] = df
    return out


# groups whose duration histogram counts local calendar days (one bar per day) instead of
# elapsed time in 2-day bins
CALENDAR_DAY_GROUPS = {"inference"}


def fmt_date(ts: pd.Timestamp) -> str:
    return (ts - UTC_OFFSET).strftime("%d/%m/%Y")


def participation_figure(users: pd.DataFrame) -> None:
    """Per group: registered vs. usable participants and participation length, own y scales."""
    n = counts()
    dur = durations(users)
    hi = int(np.ceil(max(df.days.max() for df in dur.values()) / 2) * 2)
    bins = np.arange(0, hi + 2, 2)
    for g, (label, color) in GROUPS.items():
        reg = n[g][0][1]
        df = dur[g]
        print(f"{g}: registered={reg}; duration n={len(df)} "
              f"days min={df.days.min():.1f} max={df.days.max():.1f}")
        fig, (left, right) = plt.subplots(1, 2, figsize=(10, 4), layout="constrained",
                                          gridspec_kw={"width_ratios": [1, 1.4]})
        draw_participants(left, g, n[g])
        left.set_title("Participantes\n", fontsize=11)
        annotate_n(left, f"n = {reg}")
        if g in CALENDAR_DAY_GROUPS:
            print(f"{g}: calendar days {sorted(df.calendar_days.tolist())}")
            cal_hi = int(df.calendar_days.max())
            right.hist(df.calendar_days, bins=np.arange(0.5, cal_hi + 1.5), color=color,
                       edgecolor="white", zorder=2)
            right.set_xlabel("Días calendario de participación")
            right.set_xticks(range(0, cal_hi + 2, 2))
            right.set_xlim(0, cal_hi + 1)
        else:
            right.hist(df.days, bins=bins, color=color, edgecolor="white", zorder=2)
            right.set_xlabel("Días de participación")
            right.set_xticks(bins[::2])
        right.set_ylabel("Participantes")
        style_bar_axes(right)
        right.yaxis.get_major_locator().set_params(integer=True)
        right.set_title("Duración\n", fontsize=11)
        annotate_n(right, f"n = {len(df)}; {fmt_date(df.start.min())} – {fmt_date(df.end.max())}")
        suptitle(fig, f"Participantes y duración de la participación: {label}")
        save(fig, OUT / f"participacion_{FILE[g]}.png")


def sas_figure(users: pd.DataFrame) -> None:
    inf = users[users.experiment_group == "inference"]
    scores = inf["test_score"]
    print(f"SAS-SV: n={len(scores)} min={scores.min()} max={scores.max()} median={scores.median()}")
    fig, ax = new_single()
    bins = np.arange(scores.min() // 2 * 2, scores.max() + 3, 2)   # 2-point bins on even edges
    counts, _, _ = ax.hist(scores, bins=bins, color=BLUE, edgecolor="white", zorder=2)
    median = scores.median()
    ax.axvline(SAS_CUTOFF, color="0.3", ls="--", lw=1.2, zorder=3)
    ax.axvline(median, color="0.1", ls=":", lw=1.6, zorder=3)
    lo = min(bins[0], SAS_CUTOFF) - 1
    ax.set_xlim(lo, bins[-1] + 1)
    ax.set_xticks(np.arange(lo + 1 + (lo + 1) % 2, bins[-1] + 1, 2))   # even = bin edges
    ax.set_ylim(0, counts.max() * 1.3)   # room for the legend
    ax.set_xlabel("Puntaje SAS-SV (10–60)")
    ax.set_ylabel("Participantes")
    ax.yaxis.get_major_locator().set_params(integer=True)
    style_bar_axes(ax)
    ax.legend(handles=[plt.Line2D([], [], color="0.3", ls="--", label=f"Punto de corte: {SAS_CUTOFF}"),
                       plt.Line2D([], [], color="0.1", ls=":", lw=1.6, label=f"Mediana: {median:g}")],
              loc="upper right", fontsize=9, frameon=False)
    set_title(ax, "Puntaje SAS-SV del grupo de despliegue", len(scores))
    save(fig, OUT / "sas_sv.png")


def main() -> None:
    users = load_users()
    for col in ("major", "gender_c"):
        print(users.groupby(["experiment_group", col]).size().to_string(), "\n")
    demographics_figure(users)
    participation_figure(users)
    sas_figure(users)


if __name__ == "__main__":
    main()
