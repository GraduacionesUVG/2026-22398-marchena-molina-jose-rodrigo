"""Event plots around every use -> use repeat in the real data.

Input: data/processed/real_preprocess_1.csv.

For each repeat (a use epoch followed directly by another use epoch of the
same person), draws the person's epochs from 12 h before to 12 h after the
first epoch of the pair. Use epochs are bars; non-use is empty space; the
pair is highlighted with both app counts. Time outside the person's capture
is shaded.

Each repeat is also placed within the person's capture: hours since the
first marker, hours until the last one, and the position as a percentage.

Output: one figure per person in data/reports/real_repeats/<user>.png and a
printed table.

    python eda/real_repeat_eventplots.py
"""

from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
import pandas as pd

IN_CSV = Path("data/processed/real_preprocess_1.csv")
OUT_DIR = Path("data/reports/real_repeats")
WINDOW = pd.Timedelta(hours=12)

USE, PAIR, OUTSIDE = "#2a78d6", "#eb6834", "#ecebe6"
INK, MUTED, GRID = "#1a1a19", "#6b6a63", "#e4e3dd"


def load() -> pd.DataFrame:
    epochs = pd.read_csv(IN_CSV, parse_dates=["start_utc", "start_local"])
    by_user = epochs.groupby("user_id")
    epochs["end_local"] = by_user["start_local"].shift(-1)
    epochs["next_is_use"] = by_user["is_use"].shift(-1)
    epochs["capture_start"] = by_user["start_local"].transform("min")
    epochs["capture_end"] = by_user["start_local"].transform("max")
    return epochs


def repeats(epochs: pd.DataFrame) -> pd.DataFrame:
    pairs = epochs[epochs["is_use"] & (epochs["next_is_use"] == True)].copy()  # noqa: E712
    span = pairs["capture_end"] - pairs["capture_start"]
    pairs["gap_h"] = (pairs["end_local"] - pairs["start_local"]).dt.total_seconds() / 3600
    pairs["since_start_h"] = (pairs["start_local"] - pairs["capture_start"]).dt.total_seconds() / 3600
    pairs["until_end_h"] = (pairs["capture_end"] - pairs["start_local"]).dt.total_seconds() / 3600
    pairs["capture_days"] = span.dt.total_seconds() / 86400
    pairs["position"] = (pairs["start_local"] - pairs["capture_start"]) / span
    return pairs


def draw_row(ax, user_epochs: pd.DataFrame, pair_index: int, pair: pd.Series) -> None:
    t0 = pair["start_local"]
    lo, hi = t0 - WINDOW, t0 + WINDOW
    hours = lambda t: (t - t0).total_seconds() / 3600  # noqa: E731

    if pair["capture_start"] > lo:
        ax.axvspan(-12, hours(pair["capture_start"]), color=OUTSIDE, linewidth=0)
    if pair["capture_end"] < hi:
        ax.axvspan(hours(pair["capture_end"]), 12, color=OUTSIDE, linewidth=0)

    position = user_epochs.index.get_loc(pair_index)
    pair_rows = {user_epochs.index[position], user_epochs.index[position + 1]}
    shown = user_epochs[user_epochs["is_use"] & user_epochs["end_local"].notna()
                        & (user_epochs["end_local"] > lo) & (user_epochs["start_local"] < hi)]
    for idx, epoch in shown.iterrows():
        start, end = max(hours(epoch["start_local"]), -12), min(hours(epoch["end_local"]), 12)
        width = max(end - start, 0.02)  # keep very short epochs visible
        color = PAIR if idx in pair_rows else USE
        ax.broken_barh([(start, width)], (0.2, 0.6), facecolors=color, linewidth=0)
        if idx in pair_rows:
            second = idx == max(pair_rows)  # stacked so short pairs don't overlap
            ax.text(start, 1.02 if second else 0.84, f"{epoch['distinct_app_count']} apps",
                    fontsize=7, color=INK, va="bottom")

    for day in pd.date_range(lo.normalize(), hi, freq="D"):
        if lo <= day <= hi:
            ax.axvline(hours(day), color=MUTED, linewidth=0.8, linestyle=":")
            ax.text(hours(day) + 0.1, 0.02, "00:00", fontsize=7, color=MUTED)

    ax.set_xlim(-12, 12)
    ax.set_ylim(0, 1.3)
    ax.set_yticks([])
    for side in ("top", "right", "left"):
        ax.spines[side].set_visible(False)
    ax.spines["bottom"].set_color(GRID)
    ax.tick_params(colors=MUTED, labelsize=8)
    gap = f"{pair['gap_h'] * 60:.1f} min" if pair["gap_h"] < 1 else f"{pair['gap_h']:.2f} h"
    ax.set_title(
        f"{pair['start_local']:%m-%d %H:%M} · hueco {gap} · "
        f"{pair['since_start_h']:.0f} h desde el inicio, {pair['until_end_h']:.0f} h hasta el final "
        f"({pair['position']:.0%} de {pair['capture_days']:.1f} días)",
        loc="left", fontsize=9, color=INK,
    )


def plot_user(user: str, user_epochs: pd.DataFrame, user_pairs: pd.DataFrame) -> Path:
    n = len(user_pairs)
    fig, axes = plt.subplots(n, 1, figsize=(12, 1.35 * n + 0.9), squeeze=False, constrained_layout=True)
    for ax, (idx, pair) in zip(axes[:, 0], user_pairs.iterrows()):
        draw_row(ax, user_epochs, idx, pair)
    axes[-1, 0].set_xlabel("horas desde el inicio de la primera época del par", color=MUTED)
    fig.suptitle(f"Persona {user[:8]}: uso seguido de uso (naranja), resto del uso (azul), "
                 f"fuera de la captura (gris)", x=0.01, ha="left", fontsize=11, color=INK)
    path = OUT_DIR / f"{user[:8]}.png"
    fig.savefig(path, dpi=130, facecolor="white")
    plt.close(fig)
    return path


def main() -> None:
    epochs = load()
    pairs = repeats(epochs)
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    for user, user_pairs in pairs.groupby("user_id"):
        user_epochs = epochs[epochs["user_id"] == user]
        print(f"figure -> {plot_user(user, user_epochs, user_pairs.sort_values('start_local'))}")

    table = pairs.sort_values(["user_id", "start_local"]).assign(
        user=lambda d: d["user_id"].str[:8],
        start=lambda d: d["start_local"].dt.strftime("%m-%d %H:%M"),
        gap_h=lambda d: d["gap_h"].round(2),
        since_start_h=lambda d: d["since_start_h"].round(1),
        until_end_h=lambda d: d["until_end_h"].round(1),
        capture_days=lambda d: d["capture_days"].round(1),
        position=lambda d: (d["position"] * 100).round(0),
    )[["user", "start", "gap_h", "since_start_h", "until_end_h", "capture_days", "position"]]
    print(table.to_string(index=False))


if __name__ == "__main__":
    main()
