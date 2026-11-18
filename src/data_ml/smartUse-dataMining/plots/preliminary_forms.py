"""Preliminary (recruitment) form figures.

Usage:  python plots/preliminary_forms.py
Output: figures/preliminary_forms/*.png
"""
from __future__ import annotations

import numpy as np
import pandas as pd

from plot_style import (BLUE, DATA, FIGURES, ORANGE, new_single, save, set_title,
                        style_bar_axes, wrap)

FORMS = DATA / "preliminary_forms.csv"
OUT = FIGURES / "preliminary_forms"

# Column prefixes (the raw headers are long Google Forms questions).
Q_OS = "¿Qué sistema operativo usa tu teléfono?"
Q_WILLING = "¿Estarias dispuest@ a participar en este experimento?"
Q_ANDROID_VERSION = "¿Qué versión de Android tienes instalada?"
Q_DURATION ="¿Cuál sería el período de tiempo máximo"
Q_DURATION_RECONSIDER = "Si decidieras reconsiderar tu participación"
Q_INCENTIVE ="Si hubiera un incentivo de horas becas"
Q_HOURS ="¿Qué cantidad de horas beca considerarías suficiente"


def col(df: pd.DataFrame, prefix: str) -> pd.Series:
    matches = [c for c in df.columns if c.startswith(prefix)]
    if len(matches) != 1:
        raise KeyError(f"{len(matches)} columns start with {prefix!r}")
    return df[matches[0]]


def eligibility_masks(df: pd.DataFrame) -> tuple[pd.Series, pd.Series, pd.Series]:
    """(android, sin_incentivo, con_incentivo) boolean masks.

    'Sin incentivo': Android & answered Sí to participating.
    'Con incentivo': Android & (Sí OR answered the hours-beca amount question), i.e. the
    previous group plus those who would join for enough scholarship hours — excluding
    anyone who answered "No, independientemente del incentivo" to the incentive question.
    'Con incentivo' is what the other figures call "Elegibles".
    """
    android = col(df, Q_OS).str.startswith("Android")
    willing = col(df, Q_WILLING).eq("Sí")
    refuses = col(df, Q_INCENTIVE).str.startswith("No", na=False)
    hours = col(df, Q_HOURS).notna() & ~refuses
    return android, android & willing, android & (willing | hours)


def eligibility_incentive(df: pd.DataFrame) -> None:
    """Eligible counts with/without incentive; percentages over all Android respondents."""
    android, sin, con = eligibility_masks(df)
    total = int(android.sum())
    bars = [("Sin incentivo", int(sin.sum()), ORANGE),
            ("Con incentivo", int(con.sum()), BLUE)]

    fig, ax = new_single()
    labels, counts, colors = zip(*bars)
    rects = ax.bar(labels, counts, color=colors, width=0.55)
    ax.bar_label(rects, labels=[f"{c / total:.1%}" for c in counts], padding=2,
                 fontsize=11)
    ax.set_ylim(0, total * 1.12)
    ax.set_yticks(range(0, total + 1, 3))
    ax.tick_params(axis="x", labelsize=11)
    ax.set_ylabel("Participantes elegibles")
    style_bar_axes(ax)
    set_title(ax, "Estimación de cantidad de participantes elegibles con y sin "
                  "incentivo de horas beca", total)  # n = Android pool
    save(fig, OUT / "elegibles_incentivo.png")
    print(f"elegibles: android={total} " + " ".join(f"{l}={c}" for l, c in zip(labels, counts)))


DURATION_ORDER = ["Menos de 1 semana", "1-2 semanas", "2-3 semanas", "Más de 3 semanas"]


def study_duration(df: pd.DataFrame) -> None:
    """Max acceptable install period over the eligible pool ('Con incentivo', 17).

    Split: 'Sin incentivo' = would participate anyway (answered Sí); 'Solo con
    incentivo' = said No but would join for enough horas beca (changed their minds).
    Willing respondents answered Q_DURATION; the rest answered the "si decidieras
    reconsiderar" version (Q_DURATION_RECONSIDER). Nobody answered both, so they are
    merged into one answer per person. Respondents with neither are dropped.
    """
    _, sin, con = eligibility_masks(df)
    answer = col(df, Q_DURATION).fillna(col(df, Q_DURATION_RECONSIDER))
    answer = answer.str.strip().str.capitalize()
    unknown = set(answer[con].dropna()) - set(DURATION_ORDER)
    if unknown:
        raise ValueError(f"Unexpected duration answers: {unknown}")

    groups = [("Sin incentivo", answer[sin].dropna(), ORANGE),
              ("Solo con incentivo", answer[con & ~sin].dropna(), BLUE)]
    x = np.arange(len(DURATION_ORDER))
    width = 0.38

    fig, ax = new_single()
    for i, (label, values, color) in enumerate(groups):
        counts = values.value_counts().reindex(DURATION_ORDER, fill_value=0)
        rects = ax.bar(x + (i - 0.5) * width, counts.values, width=width, color=color,
                       label=f"{label} (n = {len(values)})")
        ax.bar_label(rects, labels=[str(c) if c else "" for c in counts.values],
                     padding=2, fontsize=10)
        print(f"duración {label}: {counts.to_dict()}")
    ax.set_xticks(x, [wrap(o, 12) for o in DURATION_ORDER])
    ax.tick_params(axis="x", labelsize=10)
    ax.set_ylabel("Respuestas")
    ax.yaxis.get_major_locator().set_params(integer=True)
    top = max(len(v) and v.value_counts().max() for _, v, _ in groups)
    ax.set_ylim(0, top * 1.5)  # headroom so the legend clears the bar labels
    ax.legend(frameon=False, loc="upper right")
    style_bar_axes(ax)
    n = sum(len(v) for _, v, _ in groups)
    set_title(ax, "Duración aceptable del periodo de estudio", n)
    save(fig, OUT / "duracion_aceptable.png")


ANDROID_ORDER = ["Android 8 o inferior", "Android 9-10", "Android 11-12", "Android 13-14",
                 "Android 15 en adelante", "No lo sé"]  # all form options, in form order


def android_version(df: pd.DataFrame) -> None:
    """Installed Android version, over the Android respondents."""
    android, _, _ = eligibility_masks(df)
    values = col(df, Q_ANDROID_VERSION)[android].dropna().str.strip()
    unknown = set(values) - set(ANDROID_ORDER)
    if unknown:
        raise ValueError(f"Unexpected Android version answers: {unknown}")
    counts = values.value_counts().reindex(ANDROID_ORDER, fill_value=0)

    fig, ax = new_single()
    rects = ax.bar([wrap(o, 10) for o in ANDROID_ORDER], counts.values, color=BLUE, width=0.7)
    ax.bar_label(rects, labels=[str(c) if c else "" for c in counts.values], padding=2,
                 fontsize=11)
    ax.tick_params(axis="x", labelsize=10)
    ax.set_ylabel("Respuestas")
    ax.yaxis.get_major_locator().set_params(integer=True)
    ax.set_ylim(0, max(counts.max(), 1) * 1.15)
    style_bar_axes(ax)
    set_title(ax, "Versión de Android instalada", len(values))
    save(fig, OUT / "version_android.png")
    print(f"android version: {counts.to_dict()}")


def main() -> None:
    df = pd.read_csv(FORMS, encoding="utf-8-sig")
    eligibility_incentive(df)
    study_duration(df)
    android_version(df)


if __name__ == "__main__":
    main()
