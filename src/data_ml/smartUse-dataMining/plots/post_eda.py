"""Post-EDA: figures on the processed datasets (real data vs MPUD).

Inputs: data/processed/{real,mpud}_preprocess_3.csv  (sessions); E6-E8 (collection group
        only) also real_windows_points.csv and real_features.csv (the 18 people kept)
Usage:  python plots/post_eda.py
Output: figures/post_eda/*.png
"""
from __future__ import annotations

import matplotlib.pyplot as plt
import numpy as np
import pandas as pd

from plot_style import (BLUE, DPI, FIGURES, ORANGE, ROOT, SHOW_TITLES, new_single, set_title,
                        style_bar_axes)

PROCESSED = ROOT / "data" / "processed"
OUT = FIGURES / "post_eda"
FIG_SIZE = (8, 4.6)
E1_SIZE = (13, 5)

# dataset key -> (legend label, color)
DATASETS = {"real": ("Grupo de recolección", BLUE), "mpud": ("MPUD", ORANGE)}
# legend / axis name of each dataset (same as DATASETS; kept for the call sites)
GROUP_NAMES = {k: name for k, (name, _) in DATASETS.items()}
DROP_NO_CLOSE = True  # sessions > T_MAX_H (3 h): a screen-off that was never logged


def load_sessions() -> dict[str, pd.Series]:
    """Session durations in seconds per dataset (after preprocess_3)."""
    out = {}
    for key in DATASETS:
        s = pd.read_csv(PROCESSED / f"{key}_preprocess_3.csv",
                        usecols=["duration_s", "no_close"])
        if DROP_NO_CLOSE:
            s = s[~s.no_close]
        out[key] = s.duration_s[s.duration_s > 0]
    return out


def subtitle(data: dict[str, pd.Series]) -> str:
    return "(" + ", ".join(f"{GROUP_NAMES[k]}: n = {len(v):,}" for k, v in data.items()) + ")"


def seconds_axis(ax) -> None:
    """Log x axis in seconds with readable ticks (1 s .. 3 h)."""
    ticks = [1, 10, 60, 600, 3600, 10800]
    ax.set_xscale("log")
    ax.set_xticks(ticks, ["1 s", "10 s", "1 min", "10 min", "1 h", "3 h"])
    ax.set_xlim(0.5, 10800 * 1.3)  # a handful of MPUD sessions < 0.5 s fall off the left
    ax.set_xlabel("Duración de la sesión (escala log)")


def with_median(handles: list, labels: list) -> tuple[list, list]:
    """Legend entries plus one for the dashed median lines."""
    line = plt.Line2D([], [], color="0.3", linestyle="--", linewidth=1)
    return handles + [line], labels + ["Mediana"]


def density_panel(ax, data: dict[str, pd.Series]) -> None:
    """Density of session duration, log-spaced bins; density is per log10 unit so the
    areas are comparable on the log axis. Dashed lines: medians."""
    lo = min(v.min() for v in data.values())
    hi = max(v.max() for v in data.values())
    bins = np.logspace(np.log10(lo), np.log10(hi), 60)
    for key, v in data.items():
        color = DATASETS[key][1]; label = GROUP_NAMES[key]
        dens, _ = np.histogram(np.log10(v), bins=np.log10(bins), density=True)
        ax.stairs(dens, bins, fill=True, color=color, alpha=0.35)
        ax.stairs(dens, bins, color=color, linewidth=1.8, label=label)
        ax.axvline(v.median(), color=color, linestyle="--", linewidth=1)
    seconds_axis(ax)
    ax.set_ylabel("Densidad (por unidad de log₁₀ s)")
    ax.set_ylim(0, ax.get_ylim()[1] * 1.1)
    ax.set_title("Densidad", fontsize=12)
    style_bar_axes(ax)


def ecdf_panel(ax, data: dict[str, pd.Series]) -> None:
    for key, v in data.items():
        color = DATASETS[key][1]; label = GROUP_NAMES[key]
        x = np.sort(v.to_numpy())
        ax.step(x, np.arange(1, len(x) + 1) / len(x), where="post", color=color,
                linewidth=1.8, label=label)
    for q in (0.25, 0.5, 0.75):
        ax.axhline(q, color="0.6", linestyle=":", linewidth=0.8, zorder=0)
    seconds_axis(ax)
    ax.set_ylabel("Proporción acumulada de sesiones")
    ax.set_ylim(0, 1.02)
    ax.set_title("Distribución acumulada (ECDF)", fontsize=12)
    style_bar_axes(ax)


def e1_session_duration(data: dict[str, pd.Series]) -> None:
    """E1: session duration, real data vs MPUD: density (left) and ECDF (right)."""
    fig, (left, right) = plt.subplots(1, 2, figsize=E1_SIZE, layout="constrained")
    density_panel(left, data)
    ecdf_panel(right, data)
    handles, labels = left.get_legend_handles_labels()
    fig.legend(*with_median(handles, labels), loc="outside lower center", ncols=len(labels) + 1,
               frameon=False)
    fig_title(fig, "(E1) Duración de sesión: grupo de recolección vs MPUD", subtitle(data))
    fig.savefig(OUT / "E1.png", dpi=DPI)
    plt.close(fig)


def fig_title(fig, title: str, sub: str) -> None:
    """Figure title with a grey subtitle line under it (multi-panel figures). Without
    SHOW_TITLES only the subtitle is drawn, in the title's place."""
    if not SHOW_TITLES:
        fig.suptitle(sub, fontsize=11, color="0.35")
        return
    fig.suptitle(title + "\n ", fontsize=14)  # 2nd line reserves room for the subtitle
    fig.canvas.draw()  # resolve the layout, then pin the subtitle under the title line
    sup = fig._suptitle.get_window_extent()
    y = (sup.y0 + sup.height * 0.02) / fig.bbox.height
    fig.text(0.5, y, sub, ha="center", va="bottom", fontsize=11,
             color="0.35").set_in_layout(False)


def usable_days(key: str) -> pd.MultiIndex:
    """(user_id, local day) pairs that count as usable days, the same rule in every figure:
    a day with usable decision points (not masked, not censored, day_ok = >= 6 active hours,
    the window pipeline's rule; smartuse/coverage.py) of a person kept by the feature pipeline
    (in *_features.csv). Real data: 18 people, 218 days (of 228 calendar days with data)."""
    keep = set(pd.read_csv(PROCESSED / f"{key}_features.csv", usecols=["user_id"]).user_id)
    p = pd.read_csv(PROCESSED / f"{key}_windows_points.csv",
                    usecols=["user_id", "t_local", "masked", "censored", "day_ok"],
                    parse_dates=["t_local"])
    p = p[~p.masked & ~p.censored & p.day_ok & p.user_id.isin(keep)]
    return pd.MultiIndex.from_frame(
        pd.DataFrame({"user_id": p.user_id, "day": p.t_local.dt.normalize()}).drop_duplicates())


def daily_totals(key: str) -> pd.DataFrame:
    """Per person and local calendar day (00:00-23:59): number of sessions (by start day)
    and total session time in hours. A session crossing midnight has its time split
    between the two days."""
    s = pd.read_csv(PROCESSED / f"{key}_preprocess_3.csv",
                    usecols=["user_id", "start_local", "duration_s", "no_close"],
                    parse_dates=["start_local"])
    if DROP_NO_CLOSE:
        s = s[~s.no_close]
    start = s.start_local
    end = start + pd.to_timedelta(s.duration_s, unit="s")
    midnight = start.dt.normalize() + pd.Timedelta(days=1)
    first_part = (np.minimum(end, midnight) - start).dt.total_seconds()
    spill = (end - midnight).dt.total_seconds().clip(lower=0)

    time = pd.concat([
        pd.DataFrame({"user_id": s.user_id, "day": start.dt.normalize(), "sec": first_part}),
        pd.DataFrame({"user_id": s.user_id, "day": midnight, "sec": spill})[spill > 0],
    ]).groupby(["user_id", "day"]).sec.sum()
    count = s.groupby([s.user_id, start.dt.normalize().rename("day")]).size()
    daily = pd.DataFrame({"sessions": count, "hours": time / 3600}).fillna(0).reset_index()
    # a day counts only if a session starts on it: a day reached only by a session that
    # spilled over midnight is not a recorded day (same days as E5)
    daily = daily[daily.sessions > 0]

    # only usable days (partial days, e.g. a person's first and last, fail day_ok)
    daily = daily[pd.MultiIndex.from_frame(daily[["user_id", "day"]]).isin(usable_days(key))]
    return daily


def daily_box_panel(ax, daily: dict[str, pd.DataFrame], column: str, ylabel: str,
                    title: str) -> None:
    keys = list(DATASETS)
    data = [daily[k][column].to_numpy() for k in keys]
    for i, (k, d) in enumerate(zip(keys, data)):
        _, color = DATASETS[k]
        ax.boxplot([d], positions=[i], widths=0.5, patch_artist=True,
                   boxprops=dict(facecolor=color, edgecolor="0.25", alpha=0.85),
                   medianprops=dict(color="black", linewidth=1.5),
                   whiskerprops=dict(color="0.25"), capprops=dict(color="0.25"),
                   flierprops=dict(marker="o", markersize=2.5, markerfacecolor=color,
                                   markeredgecolor="none", alpha=0.35))
        med = np.median(d)
        ax.annotate(f"mediana: {med:.1f}", xy=(i + 0.27, med), xytext=(4, 0),
                    textcoords="offset points", va="center", fontsize=9)
    ax.set_xticks(range(len(keys)), [f"{GROUP_NAMES[k]}\n(n = {len(d):,} días)"
                                     for k, d in zip(keys, data)])
    ax.set_xlim(-0.6, len(keys) - 0.1)
    ax.set_ylabel(ylabel)
    ax.set_title(title, fontsize=12)
    style_bar_axes(ax)


def e2_daily_use() -> None:
    """E2: per person-day, sessions per day (left) and total session time per day (right)."""
    daily = {k: daily_totals(k) for k in DATASETS}
    fig, (left, right) = plt.subplots(1, 2, figsize=E1_SIZE, layout="constrained")
    daily_box_panel(left, daily, "sessions", "Sesiones por día", "Sesiones por día")
    daily_box_panel(right, daily, "hours", "Tiempo total en sesión por día (h)",
                    "Tiempo total en sesión por día")
    people = ", ".join(f"{GROUP_NAMES[k]}: {daily[k].user_id.nunique()} participantes, "
                       f"{len(daily[k]):,} días" for k in DATASETS)
    fig_title(fig, "(E2) Uso diario: grupo de recolección vs MPUD", f"({people})")
    fig.savefig(OUT / "E2.png", dpi=DPI)
    plt.close(fig)
    for k, d in daily.items():
        print(f"E2 {k}: days={len(d)} people={d.user_id.nunique()}  "
              f"sessions/day p25/50/75={d.sessions.quantile([.25, .5, .75]).round(1).tolist()}  "
              f"hours/day p25/50/75={d.hours.quantile([.25, .5, .75]).round(2).tolist()}")


MAX_APPS = 0  # "sin uso de apps": screen-on epochs with no counted app (fail the >= 1 app rule)


def e3_active_use() -> None:
    """E3: share of screen-on epochs without app use (<= MAX_APPS counted apps), i.e. the
    ones the >= 1 app session rule leaves out (lock screen / notification shade glances).
    Real data: apps = distinct ACTIVITY_RESUMED packages (launcher included).
    MPUD: same, after removing the logger, systemui, keyguard/lockscreens and keyboards
    (mpud_preprocess_2)."""
    shares, ns = [], []
    for key in DATASETS:
        e = pd.read_csv(PROCESSED / f"{key}_preprocess_2.csv", usecols=["is_use", "distinct_app_count"])
        on = e[e.is_use]
        shares.append((on.distinct_app_count <= MAX_APPS).mean() * 100)
        ns.append(len(on))
    x = np.arange(len(DATASETS))
    fig, ax = new_single()
    rects = ax.bar(x, shares, width=0.55, color=[c for _, c in DATASETS.values()])
    ax.bar_label(rects, labels=[f"{s:.1f}%" for s in shares], padding=2, fontsize=11)
    ax.set_xticks(x, [f"{DATASETS[k][0]}\n(n = {n:,} encendidos)" for k, n in zip(DATASETS, ns)])
    ax.tick_params(axis="x", labelsize=10)
    ax.set_ylabel("Encendidos sin uso de apps (%)")
    ax.set_ylim(0, 100)
    style_bar_axes(ax)
    set_title(ax, "(E3) Encendidos de pantalla sin uso de apps (0 apps)",
              f"(n = {sum(ns):,} encendidos de pantalla)")
    fig.savefig(OUT / "E3.png", dpi=DPI)
    plt.close(fig)
    print("E3:", {k: f"{s:.1f}% of {n}" for k, s, n in zip(DATASETS, shares, ns)})


def valid_grid_windows(key: str) -> pd.DataFrame:
    """60-min, non-overlapping windows aligned to the local clock
    (data/processed/*_windows_grid.csv, pipelines/labels/build_windows.py --mode grid),
    keeping only usable ones: not censored (before the first / after the last session, or
    overlapping missing data), not masked (overlapping a session > 3 h) and on a day with
    enough data (day_ok)."""
    g = pd.read_csv(PROCESSED / f"{key}_windows_grid.csv",
                    usecols=["n_sessions", "f", "s", "masked", "censored", "day_ok"])
    return g[~g.masked & ~g.censored & g.day_ok]


def density_hist(ax, values: dict[str, np.ndarray], bins: np.ndarray) -> None:
    for key, v in values.items():
        label, color = DATASETS[key]
        dens, _ = np.histogram(v, bins=bins, density=True)
        ax.stairs(dens, bins, fill=True, color=color, alpha=0.35)
        ax.stairs(dens, bins, color=color, linewidth=1.8, label=label)
        ax.axvline(np.median(v), color=color, linestyle="--", linewidth=1)
    ax.set_ylabel("Densidad")
    ax.set_ylim(0, ax.get_ylim()[1] * 1.12)
    style_bar_axes(ax)


def panel_note(ax, text: str, offset: float = 4) -> None:
    """Grey note line just under a panel title (offset in pt above the axes top)."""
    ax.annotate(text, xy=(0.5, 1), xycoords="axes fraction", xytext=(0, offset),
                textcoords="offset points", ha="center", va="bottom", fontsize=10, color="0.35")


def e4_f_s() -> None:
    """E4: distributions of F (left, windows with F > 0) and S (right, windows with at
    least one session), real data vs MPUD, over the usable 60-min grid windows."""
    win = {k: valid_grid_windows(k) for k in DATASETS}
    f = {k: w.f[w.f > 0].to_numpy() for k, w in win.items()}
    s = {k: w.s[w.n_sessions > 0].to_numpy() for k, w in win.items()}
    f_zero = {k: (w.f == 0).mean() * 100 for k, w in win.items()}
    s_empty = {k: (w.n_sessions == 0).mean() * 100 for k, w in win.items()}

    fig, (left, right) = plt.subplots(1, 2, figsize=E1_SIZE, layout="constrained")
    f_max = max(v.max() for v in f.values())
    density_hist(left, f, np.arange(0, f_max + 0.5, 0.5))
    left.set_xlabel("F (uso fragmentado)")
    left.set_xlim(0, f_max)
    left.set_title("F, ventanas con F > 0", fontsize=12, pad=38)
    panel_note(left, "(" + ", ".join(f"{DATASETS[k][0]}: {p:.1f}% con F = 0"
                                 for k, p in f_zero.items()) + ")",
               offset=22)

    s_max = np.log(3600)  # S is capped at ln(W)
    density_hist(right, s, np.linspace(0, s_max, 42))
    right.set_xlabel("S (uso prolongado)")
    right.set_xlim(0, s_max + 0.05)
    top = right.secondary_xaxis("top", functions=(np.exp, lambda x: np.log(np.maximum(x, 1e-9))))
    top.set_xticks([1, 10, 60, 600, 3600], ["1 s", "10 s", "1 min", "10 min", "60 min"])
    top.tick_params(labelsize=9)
    right.set_title("S, ventanas con al menos una sesión", fontsize=12, pad=38)
    panel_note(right, "(" + ", ".join(f"{DATASETS[k][0]}: {p:.1f}% sin sesiones"
                                  for k, p in s_empty.items()) + ")", offset=22)

    handles, labels = left.get_legend_handles_labels()
    fig.legend(*with_median(handles, labels), loc="outside lower center", ncols=len(labels) + 1,
               frameon=False)
    counts = ", ".join(f"{DATASETS[k][0]}: {len(w):,} ventanas" for k, w in win.items())
    fig_title(fig, "(E4) Distribución de F y S en ventanas de 60 min: grupo de recolección vs MPUD",
              f"({counts})")
    fig.savefig(OUT / "E4.png", dpi=DPI)
    plt.close(fig)
    for k in DATASETS:
        print(f"E4 {k}: windows={len(win[k])}  F=0 {f_zero[k]:.1f}%  median F>0 {np.median(f[k]):.2f}  "
              f"empty {s_empty[k]:.1f}%  median S {np.median(s[k]):.2f}")


def hourly_profile(key: str) -> tuple[pd.Series, int, int]:
    """Share of a day's sessions that start in each local hour (0-23).

    Per person-day: sessions starting in hour h / sessions that day (00:00-23:59 local).
    Then the mean over the person's days, then the mean over people, so every person
    weighs the same regardless of how many days or sessions they have. Same exclusions
    as E2 (sessions > 3 h; only usable days, see usable_days)."""
    s = pd.read_csv(PROCESSED / f"{key}_preprocess_3.csv",
                    usecols=["user_id", "start_local", "no_close"], parse_dates=["start_local"])
    if DROP_NO_CLOSE:
        s = s[~s.no_close]
    s = s.assign(day=s.start_local.dt.normalize(), hour=s.start_local.dt.hour)
    s = s[pd.MultiIndex.from_frame(s[["user_id", "day"]]).isin(usable_days(key))]
    per_day = (s.groupby(["user_id", "day", "hour"]).size()
               .unstack("hour", fill_value=0).reindex(columns=range(24), fill_value=0))
    per_day = per_day.div(per_day.sum(axis=1), axis=0)
    per_user = per_day.groupby("user_id").mean()
    return per_user.mean() * 100, len(per_user), len(per_day)


def e5_hourly() -> None:
    """E5: mean share of daily sessions starting in each local hour, real data vs MPUD."""
    fig, ax = plt.subplots(figsize=FIG_SIZE, layout="constrained")
    notes = []
    for key, (_, color) in DATASETS.items():
        label = GROUP_NAMES[key]
        prof, n_people, n_days = hourly_profile(key)
        ax.plot(prof.index, prof.values, color=color, linewidth=2, marker="o", markersize=5,
                label=label)
        notes.append(f"{label}: {n_people} participantes, {n_days:,} días")
        print(f"E5 {key}: people={n_people} days={n_days} peak hour={prof.idxmax()} "
              f"({prof.max():.1f}%), min hour={prof.idxmin()} ({prof.min():.1f}%)")
    ax.set_xticks(range(24), [f"{h}" for h in range(24)])
    ax.set_xlim(-0.5, 23.5)
    ax.set_xlabel("Hora local de inicio de la sesión")
    ax.set_ylabel("Sesiones del día que inician en la hora (%)")
    ax.set_ylim(0, ax.get_ylim()[1] * 1.15)
    ax.legend(frameon=False, loc="upper left")
    style_bar_axes(ax)
    set_title(ax, "(E5) Distribución horaria del inicio de sesiones", "(" + ", ".join(notes) + ")",
              width=70)
    fig.savefig(OUT / "E5.png", dpi=DPI)
    plt.close(fig)


LIGHT_BLUE = "#9ecae1"
MIN_PEOPLE_MEDIAN = 5
DAILY = {"f": ("Mediana diaria de F", "F (uso fragmentado)"),
         "s": ("Mediana diaria de S", "S (uso sticky)"),
         "sessions": ("Sesiones por día", "Sesiones por día")}


def usable_points() -> pd.DataFrame:
    """Collection group: usable decision points (not masked, not censored, day_ok) of the
    people kept by the feature pipeline, the same points the profile and the label use."""
    keep = set(pd.read_csv(PROCESSED / "real_features.csv", usecols=["user_id"]).user_id)
    p = pd.read_csv(PROCESSED / "real_windows_points.csv",
                    usecols=["user_id", "t_local", "is_unlock", "n_sessions", "f", "s",
                             "masked", "censored", "day_ok"], parse_dates=["t_local"])
    return p[~p.masked & ~p.censored & p.day_ok & p.user_id.isin(keep)]


def daily_metrics() -> pd.DataFrame:
    """Collection group, per person and local day: median F (points with >= 2 sessions),
    median S (points with >= 1 session, i.e. all) and sessions starting that day (sessions
    > 3 h left out). Usable day = a day with usable decision points (day_ok: >= 6 active
    hours, the label pipeline's rule; see usable_days). Every usable day is in every panel
    except F, which needs a point with >= 2 sessions (the subtitles give its count). participation_day = 1 on the person's first recorded local day."""
    p = usable_points().assign(day=lambda d: d.t_local.dt.normalize())
    f = p[p.n_sessions >= 2].groupby(["user_id", "day"]).f.median()
    s = p.groupby(["user_id", "day"]).s.median()
    ses = pd.read_csv(PROCESSED / "real_preprocess_3.csv", usecols=["user_id", "start_local", "no_close"],
                      parse_dates=["start_local"])
    first = ses.groupby("user_id").start_local.min().dt.normalize()
    if DROP_NO_CLOSE:
        ses = ses[~ses.no_close]
    sess = ses.groupby([ses.user_id, ses.start_local.dt.normalize().rename("day")]).size()
    # the usable days: those with usable points; sessions are counted on the same days
    d = pd.concat({"f": f, "s": s}, axis=1).reindex(p.groupby(["user_id", "day"]).size().index)
    n_usable = len(d)
    d["sessions"] = sess.reindex(d.index)
    # every usable day is kept; F is NaN on a day with no point with >= 2 sessions, and only
    # the F panels drop it
    d = d.dropna(subset=["s", "sessions"]).reset_index()
    print(f"daily_metrics: {n_usable} usable person-days, {len(d)} kept, {d.f.notna().sum()} with F")
    d["participation_day"] = (d.day - d.user_id.map(first)).dt.days + 1
    d["weekend"] = d.day.dt.dayofweek >= 5
    return d


def e6_daily_trend(d: pd.DataFrame) -> None:
    """E6: F, S and sessions per day by participation day; one line per person + median."""
    fig, axes = plt.subplots(1, 3, figsize=(15, 4.8), layout="constrained")
    for ax, (col, (ylabel, title)) in zip(axes, DAILY.items()):
        v = d.dropna(subset=[col])
        for _, g in v.groupby("user_id"):
            g = g.sort_values("participation_day")
            ax.plot(g.participation_day, g[col], color=LIGHT_BLUE, lw=0.9, alpha=0.8)
        med = v.groupby("participation_day")[col].agg(["median", "size"])
        med = med[med["size"] >= MIN_PEOPLE_MEDIAN]   # late days have few people
        ax.plot(med.index, med["median"], color=BLUE, lw=2.4, marker="o", ms=4)
        ax.set_xlabel("Día de participación")
        ax.set_xticks(range(1, int(v.participation_day.max()) + 1, 2))
        ax.set_ylabel(ylabel)
        ax.set_title(title, fontsize=12)
        style_bar_axes(ax)
    handles = [plt.Line2D([], [], color=LIGHT_BLUE, lw=0.9, label="Un participante"),
               plt.Line2D([], [], color=BLUE, lw=2.4, marker="o", ms=4,
                          label=f"Mediana entre participantes (días con ≥ {MIN_PEOPLE_MEDIAN} participantes)")]
    fig.legend(handles=handles, loc="outside lower center", ncols=2, frameon=False)
    fig_title(fig, "(E6) Tendencia por día de participación (grupo de recolección)",
              f"({d.user_id.nunique()} participantes, {len(d):,} días utilizables, {d.f.notna().sum()} con F "
              "definida; F y S: mediana diaria en los puntos de decisión utilizables)")
    fig.savefig(OUT / "E6.png", dpi=DPI)
    plt.close(fig)
    for col in DAILY:
        v = d.dropna(subset=[col])
        early = v[v.participation_day <= 4][col].median()
        late = v[v.participation_day > 4][col].median()
        print(f"E6 {col}: median days 1-4 = {early:.2f}, days 5+ = {late:.2f}")


def e7_weekday_weekend(d: pd.DataFrame) -> None:
    """E7: per person-day F, S and sessions, weekdays vs weekend (collection group)."""
    kinds = {False: ("Entre semana", BLUE), True: ("Fin de semana", LIGHT_BLUE)}
    fig, axes = plt.subplots(1, 3, figsize=(13, 4.8), layout="constrained")
    for ax, (col, (ylabel, title)) in zip(axes, DAILY.items()):
        v = d.dropna(subset=[col])
        labels = []
        for i, (wk, (name, color)) in enumerate(kinds.items()):
            x = v[v.weekend == wk][col].to_numpy()
            ax.boxplot([x], positions=[i * 1.3], widths=0.4, patch_artist=True,
                       boxprops=dict(facecolor=color, edgecolor="0.25"),
                       medianprops=dict(color="black", linewidth=1.5),
                       whiskerprops=dict(color="0.25"), capprops=dict(color="0.25"),
                       flierprops=dict(marker="o", markersize=2.5, markerfacecolor=color,
                                       markeredgecolor="none", alpha=0.6))
            ax.annotate(f"mediana: {np.median(x):.1f}", xy=(i * 1.3 + 0.21, np.median(x)), xytext=(3, 0),
                        textcoords="offset points", va="center", fontsize=9)
            labels.append(f"{name}\n(n = {len(x)} días)")
            print(f"E7 {col} {name}: days={len(x)} median={np.median(x):.2f}")
        ax.set_xticks([0, 1.3], labels)
        ax.set_xlim(-0.5, 2.2)
        ax.set_ylabel(ylabel)
        ax.set_title(title, fontsize=12)
        style_bar_axes(ax)
    fig_title(fig, "(E7) Días entre semana frente a fin de semana (grupo de recolección)",
              f"({d.user_id.nunique()} participantes, {len(d)} días utilizables, {d.f.notna().sum()} con F "
              "definida; un punto por participante y día)")
    fig.savefig(OUT / "E7.png", dpi=DPI)
    plt.close(fig)


def e8_point_composition() -> None:
    """E8: usable decision points per person, session start (unlock) vs continuous use."""
    p = usable_points()
    c = (p.groupby(["user_id", "is_unlock"]).size().unstack(fill_value=0)
         .rename(columns={True: "unlock", False: "cont"}))
    c = c.assign(total=c.unlock + c.cont).sort_values("total", ascending=False)
    x = np.arange(len(c))
    fig, ax = plt.subplots(figsize=(10, 4.8), layout="constrained")
    ax.bar(x, c.unlock, 0.7, color=BLUE, label="Inicio de sesión")
    ax.bar(x, c.cont, 0.7, bottom=c.unlock, color=ORANGE, label="Uso continuo (cada 10 min)")
    for xi, (u, t) in enumerate(zip(c.unlock, c.total)):
        ax.text(xi, t + 15, f"{u / t:.0%}", ha="center", va="bottom", fontsize=8.5, color="0.3")
    ax.set_xticks(x, [f"R{i + 1}" for i in x], fontsize=9)   # R, not P: P names the pre-EDA
    ax.set_xlabel("Participante (ordenado por número de puntos; % = inicio de sesión)")
    ax.set_ylabel("Puntos de decisión")
    ax.set_ylim(0, c.total.max() * 1.1)
    ax.legend(frameon=False, loc="upper right")
    style_bar_axes(ax)
    share = c.unlock.sum() / c.total.sum()
    set_title(ax, "(E8) Composición de los puntos de decisión por participante",
              f"({len(c)} participantes, {c.total.sum():,} puntos; {share:.0%} inicio de sesión)", width=70)
    fig.savefig(OUT / "E8.png", dpi=DPI)
    plt.close(fig)
    print(f"E8: people={len(c)} points={c.total.sum()} unlock share={share:.3f} "
          f"per person {(c.unlock / c.total).min():.2f}-{(c.unlock / c.total).max():.2f}")


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    data = load_sessions()
    for k, v in data.items():
        q = v.quantile([0.25, 0.5, 0.75]).round(1).tolist()
        print(f"{k}: n={len(v)}  p25/p50/p75 = {q} s")
    for old in ("duracion_sesion_pdf.png", "duracion_sesion_ecdf.png"):
        (OUT / old).unlink(missing_ok=True)
    e1_session_duration(data)
    e2_daily_use()
    e3_active_use()
    e4_f_s()
    e5_hourly()
    d = daily_metrics()
    e6_daily_trend(d)
    e7_weekday_weekend(d)
    e8_point_composition()


if __name__ == "__main__":
    main()
