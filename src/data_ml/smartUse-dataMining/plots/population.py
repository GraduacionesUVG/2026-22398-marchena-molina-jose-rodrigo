"""Participants per group: registered, usable and (control) used in training, against the
planned recruitment range.

Inputs: data/deployment_data/users.csv, data/usage/real_data.csv,
        data/deployment_data/final_metrics_inference.csv, data/processed/real_features.csv
Usage:  python plots/population.py
Output: none; prints the counts. The panel is drawn by draw_participants(), used in
        population_description.py (participacion_*.png).

Bars:
  - Recolección (control): registered; at least MIN_VALID_DAYS valid local days, where a valid
    day has at least MIN_DAY_HOURS distinct clock hours with an epoch start
    (smartuse/coverage.py, the same day rule as the window pipeline); used in training = people
    in real_features.csv (the pipeline keeps people with fewer than MIN_VALID_DAYS valid days,
    so this count can be higher than the previous bar).
  - Despliegue (inference): registered; usable = at least one record in final_metrics_inference.csv.
"""
from __future__ import annotations

import sys

import pandas as pd
from matplotlib.patches import Patch

from plot_style import BLUE, DATA, GREEN, ORANGE, ROOT, style_bar_axes

sys.path.insert(0, str(ROOT))
from smartuse.coverage import day_coverage  # noqa: E402

REAL = ROOT / "data" / "usage" / "real_data.csv"
METRICS = DATA / "final_metrics_inference.csv"
TRAINING = ROOT / "data" / "processed" / "real_features.csv"

UTC_OFFSET_H = 6       # Guatemala, no DST (same as pipelines/real/real_preprocess_1.py)
MIN_DAY_HOURS = 6
MIN_VALID_DAYS = 10

# group key -> (label, planned recruitment range)
GROUPS = {
    "control": ("Recolección\n(control)", (15, 20)),
    "inference": ("Despliegue\n(inferencia)", (10, 15)),
}
RANGE_COLOR = "0.85"
BAR_COLORS = [BLUE, ORANGE, GREEN]


def counts() -> dict[str, list[tuple[str, int]]]:
    """Per group, the bars of the participants panel as (label, count), left to right."""
    users = pd.read_csv(DATA / "users.csv", encoding="utf-8-sig")
    registered = users.groupby("experiment_group").size()

    real = pd.read_csv(REAL, usecols=["user_id", "start_ts"], parse_dates=["start_ts"])
    real["start_local"] = real["start_ts"] - pd.Timedelta(hours=UTC_OFFSET_H)
    valid_days = day_coverage(real, MIN_DAY_HOURS).groupby("user_id")["day_ok"].sum()
    control_ids = set(users.loc[users.experiment_group == "control", "user_id"])
    control_10d = int((valid_days[valid_days.index.isin(control_ids)] >= MIN_VALID_DAYS).sum())
    control_train = len(set(pd.read_csv(TRAINING, usecols=["user_id"]).user_id) & control_ids)

    inference_ids = set(users.loc[users.experiment_group == "inference", "user_id"])
    inference_ok = len(set(pd.read_csv(METRICS, usecols=["user_id"]).user_id) & inference_ids)

    return {
        "control": [("Registrados", int(registered["control"])),
                    (f"≥ {MIN_VALID_DAYS} días\nválidos", control_10d),
                    ("Usados en el\nentrenamiento", control_train)],
        "inference": [("Registrados", int(registered["inference"])),
                      ("Útiles\n(con métricas)", inference_ok)],
    }


def draw_participants(ax, g: str, bars: list[tuple[str, int]]) -> None:
    """Participant count bars for one group, over the planned recruitment range."""
    lo, hi = GROUPS[g][1]
    n = len(bars)
    ax.axhspan(lo, hi, color=RANGE_COLOR, zorder=0, lw=0)
    ax.text(n - 0.55, (lo + hi) / 2, f"{lo}–{hi}", ha="left", va="center", fontsize=9, color="0.4")
    for x, ((_, val), color) in enumerate(zip(bars, BAR_COLORS)):
        ax.bar(x, val, 0.6, color=color, zorder=2)
        ax.text(x, val + 0.3, str(val), ha="center", va="bottom", fontsize=10)
    ax.set_xticks(range(n), [label for label, _ in bars])
    ax.set_xlim(-0.6, n - 0.05)
    ax.set_ylabel("Participantes")
    ax.set_ylim(0, max(hi, bars[0][1]) * 1.15)
    style_bar_axes(ax)
    ax.yaxis.get_major_locator().set_params(integer=True)
    ax.legend(handles=[Patch(color=RANGE_COLOR, label="Rango planificado")],
              loc="upper right", fontsize=8, frameon=False)


def main() -> None:
    for g, bars in counts().items():
        print(g, "; ".join(f"{label.replace(chr(10), ' ').replace('≥', '>=')}={v}" for label, v in bars))


if __name__ == "__main__":
    main()
