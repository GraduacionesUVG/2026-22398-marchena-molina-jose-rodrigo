"""Final usability form: one plot per closed question + one stacked grid.

Likert (scales A-E) -> bar histogram over codes 1..5 (labels from the legend).
Yes/No (scale F)    -> pie chart (Sí = blue, No = light red).
Open questions are skipped.

Question texts and scales are parsed from
data/deployment_data/legend_final_answers.md so they stay in sync with it.

Usage:  python plots/final_answers.py
Output: figures/final_answers/D<d>-<q>.png  and  figures/final_answers/part_<k>.png
"""
from __future__ import annotations

import math
import re
import textwrap
from pathlib import Path

import matplotlib.pyplot as plt
import pandas as pd

from plot_style import (BLUE, DATA, DPI, FIGURES, LIGHT_RED, SCALE_LABEL_OFFSET,
                        SINGLE_SIZE, TICK_WRAP, set_title, style_bar_axes)

LEGEND = DATA / "legend_final_answers.md"
ANSWERS = DATA / "final_answers_inference.csv"  # from plots/clean_final_answers.py
OUT = FIGURES / "final_answers"
GRID_COLS, GRID_ROWS = 2, 3   # questions per "part_<k>.png" overview figure

SCALES = {
    "A": ["Totalmente en desacuerdo", "En desacuerdo", "Ni de acuerdo ni en desacuerdo",
          "De acuerdo", "Totalmente de acuerdo"],
    "B": ["Nunca", "Rara vez", "A veces", "Frecuentemente", "Siempre"],
    "C": ["Muy poco frecuente", "Poco frecuente", "Frecuencia adecuada", "Frecuente",
          "Muy frecuente"],
    "D": ["Muy corta", "Corta", "Adecuada", "Larga", "Muy larga"],
    "E": ["Nada útil", "Poco útil", "Útil", "Moderadamente útil", "Muy útil"],
}

ROW_RE = re.compile(r"^\|\s*`d(\d+)_(\d+)`\s*\|\s*(.+?)\s*\|\s*(.+?)\s*\|\s*$")


def parse_legend(path: Path) -> list[dict]:
    """Return closed questions in dimension-question order."""
    questions = []
    for line in path.read_text(encoding="utf-8").splitlines():
        m = ROW_RE.match(line)
        if not m:
            continue
        dim, num, text, kind = int(m[1]), int(m[2]), m[3], m[4]
        if kind.startswith("Abierta"):
            continue
        scale = kind[0]
        if scale not in SCALES and scale != "F":
            raise ValueError(f"Unknown scale {kind!r} for d{dim}_{num}")
        questions.append({"id": f"d{dim}_{num}", "dim": dim, "num": num,
                          "text": text, "scale": scale})
    return sorted(questions, key=lambda q: (q["dim"], q["num"]))




def plot_likert(ax, q: dict, values: pd.Series) -> None:
    labels = SCALES[q["scale"]]
    codes = range(1, len(labels) + 1)
    counts = values.value_counts().reindex(codes, fill_value=0)
    bars = ax.bar(list(codes), counts.values, color=BLUE, width=0.75)
    ax.bar_label(bars, labels=[str(c) if c else "" for c in counts.values], padding=2,
                 fontsize=11)
    ax.set_xticks(list(codes))  # tick labels = the codes; meanings go below with a gap
    for c, l in zip(codes, labels):
        ax.annotate(textwrap.fill(l, TICK_WRAP, break_long_words=False),
                    xy=(c, 0), xycoords=("data", "axes fraction"),
                    xytext=(0, -SCALE_LABEL_OFFSET), textcoords="offset points",
                    ha="center", va="top", fontsize=plt.rcParams["xtick.labelsize"])
    ax.set_ylabel("Respuestas")
    ax.set_ylim(0, max(counts.max(), 1) * 1.15)
    ax.yaxis.get_major_locator().set_params(integer=True)
    style_bar_axes(ax)


def plot_yes_no(ax, q: dict, values: pd.Series) -> None:
    yes, no = int((values == 1).sum()), int((values == 0).sum())
    slices = [(c, l, col) for c, l, col in [(yes, "Sí", BLUE), (no, "No", LIGHT_RED)] if c]
    counts, labels, colors = zip(*slices)
    ax.pie(counts, labels=[f"{l} ({c})" for l, c in zip(labels, counts)], colors=colors,
           autopct="%1.1f%%", startangle=90, counterclock=False,
           wedgeprops=dict(edgecolor="white", linewidth=2),
           textprops=dict(fontsize=12))
    ax.axis("equal")


def draw(ax, q: dict, values: pd.Series) -> None:
    (plot_yes_no if q["scale"] == "F" else plot_likert)(ax, q, values)
    set_title(ax, f"(D{q['dim']}-{q['num']}) {q['text']}", len(values))


def main() -> None:
    questions = parse_legend(LEGEND)
    df = pd.read_csv(ANSWERS, encoding="utf-8-sig")
    df = df[(df.form_id == "usability") & (df.form_version == 2) & df.value_int.notna()]
    by_q = {qid: g.value_int.astype(int) for qid, g in df.groupby("question_id")}

    OUT.mkdir(parents=True, exist_ok=True)
    plotted = []
    for q in questions:
        values = by_q.get(q["id"], pd.Series(dtype=int))
        if values.empty:
            print(f"skip {q['id']}: no answers")
            continue
        fig, ax = plt.subplots(figsize=SINGLE_SIZE, layout="constrained")
        draw(ax, q, values)
        fig.savefig(OUT / f"D{q['dim']}-{q['num']}.png", dpi=DPI)
        plt.close(fig)
        plotted.append((q, values))

    per_part = GRID_COLS * GRID_ROWS
    for old in OUT.glob("part_*.png"):
        old.unlink()
    n_parts = math.ceil(len(plotted) / per_part)
    for k in range(n_parts):
        chunk = plotted[k * per_part:(k + 1) * per_part]
        fig, axes = plt.subplots(GRID_ROWS, GRID_COLS, layout="constrained",
                                 figsize=(SINGLE_SIZE[0] * GRID_COLS,
                                          SINGLE_SIZE[1] * GRID_ROWS * 1.2))
        fig.get_layout_engine().set(h_pad=0.3, w_pad=0.2)  # inches between panels
        for ax, (q, values) in zip(axes.flat, chunk):
            draw(ax, q, values)
        for ax in list(axes.flat)[len(chunk):]:
            ax.set_visible(False)
        fig.savefig(OUT / f"part_{k + 1}.png", dpi=DPI)
        plt.close(fig)
    print(f"{len(plotted)} questions plotted, {n_parts} parts -> {OUT}")


if __name__ == "__main__":
    main()
