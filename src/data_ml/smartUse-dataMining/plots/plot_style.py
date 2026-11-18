"""Shared matplotlib style for the deployment figures (sizes, fonts, colors, title)."""
from __future__ import annotations

import os
import sys
import textwrap
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / "data" / "deployment_data"
FIGURES = ROOT / "figures"

BLUE = "#1f77b4"        # matplotlib default C0
ORANGE = "#ff7f0e"      # matplotlib default C1
GREEN = "#2ca02c"       # matplotlib default C2
LIGHT_RED = "#f4a6a6"
SINGLE_SIZE = (6, 4)    # width:height = 3:2; small canvas -> fonts read large
DPI = 200
TITLE_WRAP = 44
TICK_WRAP = 11

plt.rcParams.update({
    "font.size": 11,
    "axes.titlesize": 13,
    "axes.labelsize": 11,
    "xtick.labelsize": 9,
    "ytick.labelsize": 10,
})
SUBTITLE_OFFSET = 6     # pt between the axes top and the "(n = ..)" subtitle
TITLE_PAD = 26          # pt between the axes top and the title (clears the subtitle)
SCALE_LABEL_OFFSET = 22 # pt from the x axis down to the answer meaning (below the code)

# Figure titles are kept in the code but drawn only on request: the thesis puts the title
# in the caption. Include them with `--titles` on any plots/*.py script, or PLOT_TITLES=1.
# Subtitles ("(n = ..)") and panel titles are always drawn.
SHOW_TITLES = "--titles" in sys.argv or os.environ.get("PLOT_TITLES") == "1"


def wrap(text: str, width: int = TITLE_WRAP) -> str:
    return textwrap.fill(text, width, break_long_words=False)


def annotate_n(ax, n: int | str) -> None:
    """Subtitle '(n = ..)' centered between the title and the plot. A string is used as is."""
    text = n if isinstance(n, str) else f"(n = {n:,})"
    ax.annotate(text, xy=(0.5, 1), xycoords="axes fraction",
                xytext=(0, SUBTITLE_OFFSET), textcoords="offset points",
                ha="center", va="bottom", fontsize=11, color="0.35")


def set_title(ax, title: str, n: int | str, width: int = TITLE_WRAP) -> None:
    """Figure title (only if SHOW_TITLES) plus the '(n = ..)' subtitle (always)."""
    if SHOW_TITLES:
        ax.set_title(wrap(title, width), loc="center", pad=TITLE_PAD)
    annotate_n(ax, n)


def suptitle(fig, title: str, **kwargs) -> None:
    """Multi-panel figure title, only if SHOW_TITLES."""
    if SHOW_TITLES:
        fig.suptitle(title, **kwargs)


def style_bar_axes(ax) -> None:
    ax.spines[["top", "right"]].set_visible(False)
    ax.grid(axis="y", alpha=0.3)
    ax.set_axisbelow(True)


def new_single():
    return plt.subplots(figsize=SINGLE_SIZE, layout="constrained")


def save(fig, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(path, dpi=DPI)
    plt.close(fig)
