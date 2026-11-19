"""When do consecutive use markers happen in the real data?

Input: data/processed/real_preprocess_1.csv.

A repeat is a use epoch followed directly by another use epoch of the same
person (no non-use in between). Merging them in phase 2 would turn the pair
into one use epoch, so the gap between the two starts is how much "use" the
merge adds. For context, ordinary use epochs (followed by non-use) are drawn
behind: their duration is the time until the next marker.

Figure: local start hour of the first epoch vs time until the next marker
(log scale). Printed: every repeat, with both app counts.

    python eda/real_repeat_markers.py
"""

from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
import pandas as pd

IN_CSV = Path("data/processed/real_preprocess_1.csv")
FIGURE = Path("data/reports/real_repeat_markers.png")

REPEAT, ORDINARY = "#eb6834", "#c3c2b7"
INK, MUTED, GRID = "#1a1a19", "#6b6a63", "#e4e3dd"


def use_epochs() -> pd.DataFrame:
    """Use epochs with the time to the next marker and whether that marker is also use."""
    epochs = pd.read_csv(IN_CSV, parse_dates=["start_utc", "start_local"])
    by_user = epochs.groupby("user_id")
    epochs["to_next_h"] = (by_user["start_utc"].shift(-1) - epochs["start_utc"]).dt.total_seconds() / 3600
    epochs["next_is_use"] = by_user["is_use"].shift(-1)
    epochs["next_apps"] = by_user["distinct_app_count"].shift(-1)
    use = epochs[epochs["is_use"] & epochs["to_next_h"].notna()].copy()
    use["repeat"] = use["next_is_use"].astype(bool)
    local = use["start_local"]
    use["hour"] = local.dt.hour + local.dt.minute / 60
    return use


def plot(use: pd.DataFrame) -> None:
    fig, ax = plt.subplots(figsize=(10, 5.5), constrained_layout=True)
    ordinary = use[~use["repeat"]]
    repeat = use[use["repeat"]]
    ax.scatter(ordinary["hour"], ordinary["to_next_h"] * 60, s=6, color=ORDINARY, alpha=0.35,
               linewidths=0, label=f"Uso seguido de no-uso ({len(ordinary)})")
    ax.scatter(repeat["hour"], repeat["to_next_h"] * 60, s=48, color=REPEAT,
               edgecolors="white", linewidths=1.5, label=f"Uso seguido de uso ({len(repeat)})")
    ax.axhline(60, color=MUTED, linewidth=1, linestyle="--")
    ax.text(0.2, 66, "1 h", color=MUTED, fontsize=9)
    ax.set_yscale("log")
    ax.set_yticks([1 / 60, 1, 10, 60, 600])
    ax.set_yticklabels(["1 s", "1 min", "10 min", "1 h", "10 h"])
    ax.set_xticks(range(0, 25, 3))
    ax.set_xticklabels([f"{h:02d}" for h in range(0, 25, 3)])
    ax.set_xlim(0, 24)
    ax.set_xlabel("hora local de inicio de la época de uso", color=MUTED)
    ax.set_ylabel("tiempo hasta el siguiente marcador", color=MUTED)
    ax.set_title("Datos reales: épocas de uso seguidas de otra época de uso",
                 loc="left", fontsize=12, color=INK)
    ax.grid(color=GRID, linewidth=0.8)
    ax.set_axisbelow(True)
    for side in ("top", "right"):
        ax.spines[side].set_visible(False)
    for side in ("left", "bottom"):
        ax.spines[side].set_color(GRID)
    ax.tick_params(colors=MUTED)
    ax.legend(frameon=False, labelcolor=INK, loc="upper left")
    FIGURE.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(FIGURE, dpi=150, facecolor="white")


def main() -> None:
    use = use_epochs()
    repeat = use[use["repeat"]].sort_values("to_next_h")
    print(f"use epochs with a known end: {len(use)}; followed by another use epoch: {len(repeat)} "
          f"in {repeat['user_id'].nunique()} users")
    print(f"ordinary use epochs, time to next marker: median {use.loc[~use['repeat'], 'to_next_h'].median() * 60:.1f} min, "
          f"p99 {use.loc[~use['repeat'], 'to_next_h'].quantile(0.99) * 60:.1f} min")
    table = repeat.assign(
        user=repeat["user_id"].str[:8],
        start_local=repeat["start_local"].dt.strftime("%m-%d %H:%M"),
        gap=repeat["to_next_h"].map(lambda h: f"{h * 60:.1f} min" if h < 1 else f"{h:.2f} h"),
        apps=repeat["distinct_app_count"],
        next_apps=repeat["next_apps"].astype(int),
    )[["user", "start_local", "gap", "apps", "next_apps"]]
    print(table.to_string(index=False))
    plot(use)
    print(f"figure -> {FIGURE}")


if __name__ == "__main__":
    main()
