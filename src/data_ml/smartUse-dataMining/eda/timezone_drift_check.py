"""Check which timezone the timestamps of each dataset are in.

Claims under test:
  1. In the MPUD raw files, `ts` is the phone's local clock: ts - ts_raw
     (UTC) is a whole-hour timezone offset, +2 h (Spain, CEST) for most
     users. `mpud.csv` keeps both: ts_utc (from ts_raw) and ts_phone (ts).
  2. Real data `start_ts` is UTC, not local.
  3. Moving each dataset to local time (MPUD: ts_utc + the phone's offset,
     ts_phone - ts_utc rounded to 15 min; real: -6 h, America/Guatemala) puts the nightly activity minimum and sleep
     in the night.

Evidence:
  - MPUD raw: ts - ts_raw per row, per user; MPUD's own `HourOfDay` column
    against the hour of `ts`.
  - Both datasets: screen-on starts per hour of day. For every candidate
    offset from -12 h to +12 h, the share of screen-ons that would fall in
    the night (01:00-06:00 local) is computed; the offset that minimizes it
    is the most plausible shift. Sleep timing varies between populations,
    so this only resolves the offset to within +-1 h: it rules out UTC for
    the real data (-6 h) but cannot tell UTC from +2 h for MPUD, where the
    raw ts vs ts_raw comparison is the evidence.
  - Figure: screen-on starts per local hour and Lin's sleep (longest
    non-use starting between 21:00 and 12:00 next day), overlaid for both
    datasets. Before cleanup, MPUD non-use is approximated as the time from
    a screen `Off` to the next screen event.

    python eda/timezone_drift_check.py
"""

from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
import pandas as pd

MPUD_RAW = Path("data/mpud_raw")
MPUD_CSV = Path("data/usage/mpud.csv")
REAL_CSV = Path("data/usage/real_data.csv")
FIGURE = Path("data/reports/timezone_drift_check.png")

NIGHT = range(1, 6)  # 01:00-05:59 local
OFFSETS = range(-12, 13)
EXPECTED = {"MPUD": 2, "real": -6}  # hours from stored UTC to (main) local time
REAL_OFFSET = pd.Timedelta(hours=-6)
SLEEP_FROM_H, SLEEP_TO_H = 21, 36  # 21:00 to 12:00 next day

COLORS = {"MPUD": "#2a78d6", "real": "#eb6834"}
LABELS = {"MPUD": "MPUD (España, 2016)", "real": "Datos reales (Guatemala, 2026)"}
INK, MUTED, GRID = "#1a1a19", "#6b6a63", "#e4e3dd"


def mpud_raw_offsets(raw_dir: Path) -> pd.DataFrame:
    """Per user: offsets ts - ts_raw (rounded to 15 min) and HourOfDay agreement."""
    rows = []
    for path in sorted(raw_dir.glob("*.csv")):
        raw = pd.read_csv(path, usecols=["ts", "ts_raw", "HourOfDay"])
        ts = pd.to_datetime(raw["ts"])
        utc = pd.to_datetime(raw["ts_raw"], unit="ms")
        offset_h = ((ts - utc).dt.total_seconds() / 900).round() / 4
        shares = offset_h.value_counts(normalize=True)
        rows.append(
            {
                "uuid": path.stem,
                "offset_h": shares.index[0],
                "whole_hour": (offset_h % 1 == 0).mean(),
                "offsets": ", ".join(f"{o:+g} h {s:.0%}" for o, s in shares[shares > 0.01].items()),
                "hour_of_day_match": (raw["HourOfDay"] == ts.dt.hour).mean(),
            }
        )
    return pd.DataFrame(rows)


def load() -> dict[str, pd.DataFrame]:
    """Per dataset: user, utc, local, is_use start flag and non-use intervals."""
    mpud = pd.read_csv(MPUD_CSV, usecols=["uuid", "ts_utc", "ts_phone", "sensor_id", "value"])
    mpud = mpud[mpud["sensor_id"] == "Screen"].rename(columns={"uuid": "user"})
    mpud["utc"] = pd.to_datetime(mpud["ts_utc"])
    offset = (pd.to_datetime(mpud["ts_phone"]) - mpud["utc"]).dt.total_seconds() / 900
    mpud["local"] = mpud["utc"] + pd.to_timedelta(offset.round() * 15, unit="min")
    mpud["on_start"] = mpud["value"].isin(["On", "Unlocked"])
    mpud["end"] = mpud.groupby("user")["utc"].shift(-1)
    mpud["non_use"] = mpud["value"] == "Off"

    real = pd.read_csv(REAL_CSV, usecols=["user_id", "start_ts", "is_use"])
    real = real.rename(columns={"user_id": "user"})
    real["utc"] = pd.to_datetime(real["start_ts"], format="ISO8601")
    real = real.sort_values(["user", "utc"], kind="stable")
    real["local"] = real["utc"] + REAL_OFFSET
    real["on_start"] = real["is_use"]
    real["end"] = real.groupby("user")["utc"].shift(-1)  # last epoch: NaT, dropped below
    real["non_use"] = ~real["is_use"]
    return {"MPUD": mpud, "real": real}


def hourly_share(hours: pd.Series) -> pd.Series:
    return hours.value_counts(normalize=True).reindex(range(24), fill_value=0.0)


def night_share_by_offset(utc: pd.Series) -> pd.Series:
    stored = hourly_share(utc.dt.hour)
    shares = {}
    for offset in OFFSETS:
        local = stored.copy()
        local.index = (local.index + offset) % 24
        shares[offset] = local[list(NIGHT)].sum()
    return pd.Series(shares)


def lin_sleep(df: pd.DataFrame) -> pd.DataFrame:
    """Longest non-use per user and night, starting between 21:00 and 12:00 next day."""
    gaps = df[df["non_use"] & df["end"].notna()].copy()
    gaps["hours"] = (gaps["end"] - gaps["utc"]).dt.total_seconds() / 3600
    night_start = gaps["local"] - pd.Timedelta(hours=SLEEP_FROM_H)
    gaps["night"] = night_start.dt.date
    gaps["onset_h"] = SLEEP_FROM_H + (night_start - night_start.dt.normalize()).dt.total_seconds() / 3600
    gaps = gaps[gaps["onset_h"] < SLEEP_TO_H]
    sleep = gaps.loc[gaps.groupby(["user", "night"])["hours"].idxmax()]
    sleep["wake_h"] = sleep["onset_h"] + sleep["hours"]
    return sleep


def style(ax, title: str, xlabel: str) -> None:
    ax.set_title(title, loc="left", fontsize=11, color=INK)
    ax.set_xlabel(xlabel, color=MUTED)
    ax.set_ylabel("% del total", color=MUTED)
    ax.grid(axis="y", color=GRID, linewidth=0.8)
    ax.set_axisbelow(True)
    for side in ("top", "right"):
        ax.spines[side].set_visible(False)
    for side in ("left", "bottom"):
        ax.spines[side].set_color(GRID)
    ax.tick_params(colors=MUTED)


def clock_ticks(ax, start: int, stop: int) -> None:
    ticks = range(start, stop + 1, 3)
    ax.set_xticks(list(ticks))
    ax.set_xticklabels([f"{t % 24:02d}" for t in ticks])
    ax.set_xlim(start, stop)


def plot(data: dict[str, pd.DataFrame], sleeps: dict[str, pd.DataFrame]) -> None:
    fig, axes = plt.subplots(1, 3, figsize=(15, 4.2), constrained_layout=True)
    for name, df in data.items():
        share = hourly_share(df.loc[df["on_start"], "local"].dt.hour) * 100
        axes[0].plot(share.index, share.values, color=COLORS[name], linewidth=2, label=LABELS[name])
        sleep = sleeps[name]
        for ax, column in ((axes[1], "onset_h"), (axes[2], "wake_h")):
            bins = range(18, 43)
            counts = pd.cut(sleep[column], bins, right=False).value_counts(normalize=True, sort=False)
            ax.step(list(bins)[:-1], counts.values * 100, where="post", color=COLORS[name], linewidth=2)
    style(axes[0], "Inicios de pantalla encendida", "hora local")
    clock_ticks(axes[0], 0, 23)
    style(axes[1], "Inicio del sueño (Lin)", "hora local")
    clock_ticks(axes[1], 18, 42)
    style(axes[2], "Fin del sueño (Lin)", "hora local")
    clock_ticks(axes[2], 18, 42)
    fig.legend(*axes[0].get_legend_handles_labels(), frameon=False, labelcolor=INK,
               loc="upper right", ncol=2)
    fig.suptitle("Verificación de zona horaria: ambos conjuntos en hora local",
                 x=0.01, ha="left", fontsize=13, color=INK)
    FIGURE.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(FIGURE, dpi=150, facecolor="white")


def main() -> None:
    results = []

    print("== 1. MPUD raw: ts (phone clock) vs ts_raw (UTC) ==")
    offsets = mpud_raw_offsets(MPUD_RAW)
    print(offsets["offset_h"].value_counts().rename("users by main offset (h)").to_string())
    spain = offsets["offsets"] == "+2 h 100%"
    print(f"users entirely at +2 h (Spain): {spain.sum()} of {len(offsets)}; the rest:")
    print(offsets.loc[~spain, ["uuid", "offsets"]].to_string(index=False))
    results.append(("MPUD raw: ts - ts_raw is a whole-hour offset (ts is the phone's local clock)",
                    offsets["whole_hour"].min() > 0.99))
    results.append(("MPUD raw: > 95 % of users mostly at +2 h (Spain)",
                    (offsets["offset_h"] == 2).mean() > 0.95))
    results.append(("MPUD raw: HourOfDay matches hour(ts)", offsets["hour_of_day_match"].min() > 0.99))

    data = load()
    sleeps = {}
    for name, df in data.items():
        starts = df.loc[df["on_start"]]
        expected = EXPECTED[name]
        print(f"\n== {name}: screen-on starts ({len(starts)}) ==")
        share = hourly_share(starts["utc"].dt.hour)
        print("as stored (UTC), % per hour:")
        print((share * 100).round(1).rename("%").to_frame().T.to_string())
        night = night_share_by_offset(starts["utc"])
        best = int(night.idxmin())
        print(f"night share (01-06 local) by offset: as stored {night[0]:.3f}, "
              f"expected {expected:+d} h -> {night[expected]:.3f}, best {best:+d} h -> {night[best]:.3f}")
        local_share = hourly_share(starts["local"].dt.hour)
        quietest = int(local_share.idxmin())
        print(f"quietest local hour: {quietest:02d}:00")
        results.append((f"{name}: offset {expected:+d} h within 1 h of the best offset", abs(expected - best) <= 1))
        if abs(expected) > 2:  # with +-1 h resolution, a 2 h shift cannot be told from UTC
            results.append((f"{name}: as stored (UTC) is NOT within 1 h of the best offset", abs(best) > 1))
        results.append((f"{name}: quietest local hour is in 01-06", quietest in NIGHT))

        sleep = lin_sleep(df)
        sleeps[name] = sleep
        onset = sleep["onset_h"].median()
        wake = sleep["wake_h"].median()
        print(f"Lin sleep, {len(sleep)} user-nights: median onset {onset % 24:05.2f} h, "
              f"median wake {wake % 24:05.2f} h, median duration {sleep['hours'].median():.2f} h")
        results.append((f"{name}: median local sleep onset between 21:00 and 03:00", 21 <= onset <= 27))
        results.append((f"{name}: median local wake between 05:00 and 11:00", 29 <= wake <= 35))

    plot(data, sleeps)
    print(f"\nfigure -> {FIGURE}")

    print("\n== Summary ==")
    for claim, ok in results:
        print(f"[{'PASS' if ok else 'FAIL'}] {claim}")


if __name__ == "__main__":
    main()
