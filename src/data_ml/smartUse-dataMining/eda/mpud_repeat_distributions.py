"""MPUD repeated markers against their normal counterparts.

Input: data/processed/mpud_preprocess_2.csv.

Screen markers become epoch boundaries: a use start is an On, or an Unlocked
right after an Off (the decided rule); an Off is a non-use start; any other
Unlocked is inside a use epoch and is not a boundary. Each interval between
consecutive boundaries of a person is one of:

  use -> non-use      normal use epoch
  non-use -> use      normal non-use epoch
  use -> use          repeat: a screen-on with no screen-off before the next
                      one (On -> On, or Unlocked -> On with no Off between)
  non-use -> non-use  repeat: two screen-offs with no screen-on between

For every interval: duration; max_gap_s, the longest stretch with no App
event (counting the stretch from the start to the first App event and from
the last one to the end; no App events: the whole interval); and activity
rates per minute:

  apps_per_min        distinct app packages (prefix stripped, `null` excluded)
  switches_per_min    App events (foreground app changes)
  posted_per_min      notifications posted
  dismissed_per_min   notifications dismissed by the person

Use epochs are split by category: active (the epoch has an Unlocked, inside
it or as its start) and glance or notification-triggered (no Unlocked: lock
screen only). Repeats are merged: each chain of consecutive use starts with no
Off between them becomes one epoch, from the first start to the closing Off
(chains with no closing Off are dropped); normal use is the use epochs that
are not part of any chain. Three use figures, two series each:

  active vs glance   all use epochs (normal and merged together), by category
  active             normal active vs merged active
  glance             normal glance vs merged glance

Each series is the share of intervals in each bin; rates are on a log scale, so intervals with a rate
of 0 are left out of each rate panel and their share is given in its title.

Output: data/reports/mpud_use_active_vs_glance.png,
data/reports/mpud_use_active_normal_vs_merged.png,
data/reports/mpud_use_glance_normal_vs_merged.png,
data/reports/mpud_repeat_nonuse.png (non-use -> non-use vs normal non-use)
and a printed summary.

    python eda/mpud_repeat_distributions.py
"""

from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np
import pandas as pd

IN_CSV = Path("data/processed/mpud_preprocess_2.csv")
CATEGORY_FIGURE = Path("data/reports/mpud_use_active_vs_glance.png")
ACTIVE_FIGURE = Path("data/reports/mpud_use_active_normal_vs_merged.png")
GLANCE_FIGURE = Path("data/reports/mpud_use_glance_normal_vs_merged.png")
NONUSE_FIGURE = Path("data/reports/mpud_repeat_nonuse.png")
APPS_CAP, NOTIF_CAP = 15, 10

NORMAL, REPEAT, GLANCE = "#2a78d6", "#eb6834", "#1baf7a"
INK, MUTED, GRID = "#1a1a19", "#6b6a63", "#e4e3dd"
TIME_TICKS = ([1, 10, 60, 600, 3600, 36000], ["1 s", "10 s", "1 min", "10 min", "1 h", "10 h"])
RATE_TICKS = ([0.001, 0.01, 0.1, 1, 10, 100], ["0.001", "0.01", "0.1", "1", "10", "100"])
RATES = {
    "apps_per_min": "Apps distintas/min",
    "switches_per_min": "Cambios de app/min",
    "posted_per_min": "Notif. publicadas/min",
    "dismissed_per_min": "Notif. descartadas/min",
}


def intervals(events: pd.DataFrame) -> pd.DataFrame:
    """One row per interval between consecutive epoch boundaries, with activity inside it."""
    screen = events[events["sensor_id"] == "Screen"].copy()
    prev_screen = screen.groupby("uuid")["value"].shift()
    starts_use = (screen["value"] == "On") | ((screen["value"] == "Unlocked") & (prev_screen == "Off"))
    is_boundary = starts_use | (screen["value"] == "Off")
    bounds = screen[is_boundary].assign(
        kind=np.where(starts_use[is_boundary], "use", "non"),
    )[["uuid", "ts_utc", "kind"]].reset_index(drop=True)
    by_user = bounds.groupby("uuid")
    bounds["prev_kind"] = by_user["kind"].shift()
    bounds["next_kind"] = by_user["kind"].shift(-1)
    bounds["end"] = by_user["ts_utc"].shift(-1)
    bounds = bounds[bounds["end"].notna()].copy()
    bounds["type"] = bounds["kind"] + "->" + bounds["next_kind"]
    return measure(events, bounds)


def measure(events: pd.DataFrame, bounds: pd.DataFrame) -> pd.DataFrame:
    """Add duration, activity counts and rates, and max_gap_s to [ts_utc, end) intervals."""
    bounds = bounds.copy()
    bounds["duration_s"] = (bounds["end"] - bounds["ts_utc"]).dt.total_seconds()
    bounds["interval"] = bounds.index

    is_activity = events["sensor_id"].isin(["App", "Notif", "NotifDismissed"])         | ((events["sensor_id"] == "Screen") & (events["value"] == "Unlocked"))
    activity = events[is_activity][["uuid", "ts_utc", "sensor_id", "value"]]
    activity = pd.merge_asof(activity.sort_values("ts_utc"),
                             bounds[["uuid", "ts_utc", "interval"]].sort_values("ts_utc"),
                             on="ts_utc", by="uuid", direction="backward").dropna(subset=["interval"])
    activity["interval"] = activity["interval"].astype(int)
    activity = activity[activity["ts_utc"] < bounds["end"].reindex(activity["interval"]).to_numpy()]

    def count(mask: pd.Series) -> pd.Series:
        return activity[mask].groupby("interval").size().reindex(bounds.index, fill_value=0)

    app_events = activity[activity["sensor_id"] == "App"]
    named = app_events[app_events["value"] != "null"]
    bounds["apps"] = named.assign(pkg=named["value"].str.lstrip("+-=")).groupby("interval")["pkg"].nunique() \
        .reindex(bounds.index, fill_value=0)
    bounds["switches"] = count(activity["sensor_id"] == "App")
    bounds["posted"] = count((activity["sensor_id"] == "Notif") & (activity["value"] == "posted"))
    bounds["dismissed"] = count(activity["sensor_id"] == "NotifDismissed")
    bounds["unlocks"] = count(activity["sensor_id"] == "Screen")
    bounds["max_gap_s"] = max_app_gap(app_events, bounds)

    minutes = bounds["duration_s"] / 60
    for column in ("apps", "switches", "posted", "dismissed"):
        bounds[f"{column}_per_min"] = bounds[column] / minutes
    return bounds


def merged_view(events: pd.DataFrame, data: pd.DataFrame) -> tuple[pd.DataFrame, pd.DataFrame]:
    """Normal use outside chains, and chains of use starts merged into one epoch each."""
    use = data[data["kind"] == "use"].sort_values(["uuid", "ts_utc"])
    chain = (use["prev_kind"] != "use").cumsum()
    chains = use.groupby(chain).agg(uuid=("uuid", "first"), ts_utc=("ts_utc", "first"), end=("end", "last"),
                                    starts=("ts_utc", "size"), last_type=("type", "last"))
    closed = chains[(chains["starts"] > 1) & (chains["last_type"] == "use->non")]
    merged = measure(events, closed[["uuid", "ts_utc", "end", "starts"]].reset_index(drop=True))
    normal = data[(data["type"] == "use->non") & (data["prev_kind"] != "use")]
    return normal, merged


def split(frame: pd.DataFrame) -> tuple[pd.DataFrame, pd.DataFrame]:
    """Active (with an Unlocked) and glance (without) epochs."""
    return frame[frame["unlocks"] > 0], frame[frame["unlocks"] == 0]


def max_app_gap(app_events: pd.DataFrame, bounds: pd.DataFrame) -> pd.Series:
    """Longest stretch without App events in each interval, edges included."""
    apps = app_events[["interval", "ts_utc"]].sort_values(["interval", "ts_utc"])
    start = bounds["ts_utc"].reindex(apps["interval"]).to_numpy()
    prev = apps.groupby("interval")["ts_utc"].shift()
    gap_before = (apps["ts_utc"] - prev.fillna(pd.Series(start, index=apps.index))).dt.total_seconds()
    inner = gap_before.groupby(apps["interval"]).max()
    last = apps.groupby("interval")["ts_utc"].max()
    tail = (bounds["end"].reindex(last.index) - last).dt.total_seconds()
    gap = pd.concat([inner, tail], axis=1).max(axis=1)
    return gap.reindex(bounds.index).fillna(bounds["duration_s"])


def share(values: pd.Series, cap: int) -> pd.Series:
    return values.clip(upper=cap).value_counts(normalize=True).reindex(range(cap + 1), fill_value=0) * 100


def style(ax, title: str, xlabel: str) -> None:
    ax.set_title(title, loc="left", fontsize=10, color=INK)
    ax.set_xlabel(xlabel, color=MUTED)
    ax.set_ylabel("% de los intervalos", color=MUTED)
    ax.grid(axis="y", color=GRID, linewidth=0.8)
    ax.set_axisbelow(True)
    for side in ("top", "right"):
        ax.spines[side].set_visible(False)
    for side in ("left", "bottom"):
        ax.spines[side].set_color(GRID)
    ax.tick_params(colors=MUTED)


def log_steps(ax, groups, column: str, bins: np.ndarray) -> None:
    for group, color, label in groups:
        values = group[column][group[column] > 0]
        counts, _ = np.histogram(values, bins=bins)
        ax.step(bins[:-1], counts / len(group) * 100, where="post", color=color, linewidth=2, label=label)
    ax.set_xscale("log")


def count_bars(ax, groups, column: str, cap: int) -> None:
    x = np.arange(cap + 1)
    width = 0.8 / len(groups)
    for k, (group, color, _) in enumerate(groups):
        ax.bar(x + (k - (len(groups) - 1) / 2) * width, share(group[column], cap), width=width * 0.95, color=color)
    ax.set_xticks(x)
    ax.set_xticklabels([str(i) for i in x[:-1]] + [f"{cap}+"])


def groups_for(data: pd.DataFrame, repeat_type: str, normal_type: str, name: str):
    repeat, normal = data[data["type"] == repeat_type], data[data["type"] == normal_type]
    return [(normal, NORMAL, f"{name} normal ({len(normal)})"),
            (repeat, REPEAT, f"{name} → {name}, repetido ({len(repeat)})")]


def plot_use(groups, title: str, path: Path) -> None:
    fig, axes = plt.subplots(2, 3, figsize=(16, 8.5), constrained_layout=True)
    time_bins = np.logspace(-1, np.log10(max(g["duration_s"].max() for g, _, _ in groups)), 50)

    ax = axes[0, 0]
    log_steps(ax, groups, "duration_s", time_bins)
    ax.set_xticks(*TIME_TICKS)
    style(ax, "Duración del intervalo", "duración")
    ax.legend(frameon=False, labelcolor=INK, fontsize=8, loc="upper left")

    ax = axes[1, 0]
    log_steps(ax, groups, "max_gap_s", time_bins)
    ax.set_xticks(*TIME_TICKS)
    style(ax, "Mayor tramo sin cambio de app en primer plano", "mayor tramo sin evento App (bordes incluidos)")

    rate_bins = np.logspace(-3, 2, 50)
    for ax, (column, panel) in zip((axes[0, 1], axes[0, 2], axes[1, 1], axes[1, 2]), RATES.items()):
        log_steps(ax, groups, column, rate_bins)
        ax.set_xticks(*RATE_TICKS)
        zeros = ", ".join(f"{(g[column] == 0).mean():.0%}" for g, _, _ in groups)
        style(ax, f"{panel} (en 0: {zeros})", "por minuto")

    fig.suptitle(title, x=0.01, ha="left", fontsize=13, color=INK)
    path.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(path, dpi=140, facecolor="white")
    plt.close(fig)


def plot_nonuse(data: pd.DataFrame) -> None:
    groups = groups_for(data, "non->non", "non->use", "no-uso")
    fig, axes = plt.subplots(1, 3, figsize=(16, 4.6), constrained_layout=True)
    log_steps(axes[0], groups, "duration_s", np.logspace(-1, np.log10(data["duration_s"].max()), 50))
    axes[0].set_xticks(*TIME_TICKS)
    style(axes[0], "Duración del intervalo", "duración")
    axes[0].legend(frameon=False, labelcolor=INK, fontsize=8, loc="upper left")
    count_bars(axes[1], groups, "apps", APPS_CAP)
    style(axes[1], "Apps distintas dentro del intervalo", "apps distintas")
    count_bars(axes[2], groups, "posted", NOTIF_CAP)
    style(axes[2], "Notificaciones publicadas dentro del intervalo", "notificaciones publicadas")
    fig.suptitle("MPUD: no-uso → no-uso (naranja) frente al no-uso normal (azul)",
                 x=0.01, ha="left", fontsize=13, color=INK)
    fig.savefig(NONUSE_FIGURE, dpi=140, facecolor="white")
    plt.close(fig)


def main() -> None:
    events = pd.read_csv(IN_CSV, usecols=["uuid", "ts_utc", "sensor_id", "value"], dtype={"value": str},
                         keep_default_na=False, parse_dates=["ts_utc"])
    data = intervals(events)
    normal, merged = merged_view(events, data)
    active, glance = split(pd.concat([normal, merged], ignore_index=True))
    normal_active, normal_glance = split(normal)
    merged_active, merged_glance = split(merged)
    figures = {
        CATEGORY_FIGURE: ("MPUD: uso activo frente a vistazo (todas las épocas de uso, con las fusionadas)",
                          [(active, NORMAL, f"activo ({len(active)})"), (glance, GLANCE, f"vistazo ({len(glance)})")]),
        ACTIVE_FIGURE: ("MPUD, uso activo: épocas normales frente a cadenas fusionadas",
                        [(normal_active, NORMAL, f"activo normal ({len(normal_active)})"),
                         (merged_active, REPEAT, f"activo fusionado ({len(merged_active)})")]),
        GLANCE_FIGURE: ("MPUD, vistazos: épocas normales frente a cadenas fusionadas",
                        [(normal_glance, NORMAL, f"vistazo normal ({len(normal_glance)})"),
                         (merged_glance, REPEAT, f"vistazo fusionado ({len(merged_glance)})")]),
    }
    rows = [g.assign(type=label.split(" (")[0]) for _, groups in figures.values() for g, _, label in groups]
    summary = pd.concat(rows).groupby("type", sort=False).agg(
        n=("duration_s", "size"), users=("uuid", "nunique"),
        median_min=("duration_s", lambda d: round(d.median() / 60, 2)),
        median_max_gap_min=("max_gap_s", lambda g: round(g.median() / 60, 2)),
        **{f"median_{c}": (c, lambda r: round(r[r > 0].median(), 3)) for c in RATES},
        **{f"zero_{c.split('_')[0]}": (c, lambda r: round((r == 0).mean(), 2)) for c in RATES},
    )
    print(summary.T.to_string())
    print(f"merged chains: {len(merged)}; use starts per chain: {merged['starts'].value_counts().sort_index().to_dict()}")
    for path, (title, groups) in figures.items():
        plot_use(groups, title, path)
    plot_nonuse(data)
    print(f"figures -> {', '.join(str(p) for p in figures)}, {NONUSE_FIGURE}")


if __name__ == "__main__":
    main()
