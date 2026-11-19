"""Feature vector at a decision point (README §6).

Computed only from one person's session log (starts, ends in epoch seconds
UTC; apps per session) and the frozen profile, with nothing after t: ends
are clipped to t, sessions starting after t are ignored, and the current
session's app count is never read (it is only final when the session ends).
The device runs the same function on its log, with the open session ending
at t (training/deployment parity).

Blocks: current state; immediate (15 min) and current (60 min) windows;
local calendar day so far (Lin parameters, cumulative and Siebers); frozen
profile; time of day; decision-point context. Durations and intervals are
log1p(seconds). A statistic below its minimum number of events gets 0 and a
flag: few2_* (fewer than 2 sessions) and few3_* (fewer than 3 intervals).
"""

import math
from dataclasses import dataclass

import numpy as np

from smartuse.windows import window_metrics

DAY = 86400.0
HOUR = 3600.0
QS = np.linspace(0.05, 0.95, 19)
WINDOWS_MIN = (15, 60)


@dataclass(frozen=True)
class Profile:
    pf: float
    ps: float
    f_q: np.ndarray            # quantiles QS of F over the profile windows
    s_q: np.ndarray
    f_median: float
    s_median: float
    freq_day: float            # Lin parameters, median over profile days
    use_day_min: float
    logdur_median: float
    nonuse_day_min: float
    lognonuse_median: float
    sessions_by_hour: np.ndarray  # median sessions started per local hour (24)


def burstiness(intervals: np.ndarray) -> float:
    """B = (sigma - mu) / (sigma + mu) with the Kim and Jo (2016) finite-n correction."""
    n = len(intervals)
    mu = float(intervals.mean())
    if mu == 0:
        return 0.0
    r = float(intervals.std()) / mu
    a, b = math.sqrt(n + 1), math.sqrt(n - 1)
    return (a * r - b) / ((a - 2) * r + b)


def lin_stats(starts: np.ndarray, ends: np.ndarray, a: float, b: float) -> dict:
    """Lin et al. parameters over [a, b), sessions clipped to it."""
    i = int(np.searchsorted(ends, a, side="right"))
    j = int(np.searchsorted(starts, b, side="left"))
    s, e = np.maximum(starts[i:j], a), np.minimum(ends[i:j], b)
    dur = e - s
    gaps = s[1:] - e[:-1]
    return {
        "freq": float(len(dur)),
        "use_min": float(dur.sum() / 60),
        "logdur_median": float(np.median(np.log1p(dur))) if len(dur) >= 2 else 0.0,
        "nonuse_min": float(gaps.sum() / 60),
        "lognonuse_median": float(np.median(np.log1p(gaps))) if len(gaps) >= 2 else 0.0,
        "gaps": gaps,
        "first_start": float(s[0]) if len(s) else float("nan"),
    }


def local_day_start(t: float, offset_s: float) -> float:
    return math.floor((t + offset_s) / DAY) * DAY - offset_s


def build_profile(starts: np.ndarray, ends: np.ndarray, apps: np.ndarray, f_values: np.ndarray,
                  s_values: np.ndarray, day_bounds: list[tuple[float, float]], offset_s: float,
                  p: float = 0.80) -> Profile:
    """Frozen profile from the profile days: thresholds from the decision-point
    windows (f_values, s_values) and Lin parameters from the session log."""
    days = [lin_stats(starts, ends, a, b) for a, b in day_bounds]
    by_hour = []
    for a, b in day_bounds:
        st = starts[(starts >= a) & (starts < b)]
        hours = np.floor(((st + offset_s) % DAY) / HOUR).astype(int)
        by_hour.append(np.bincount(hours, minlength=24))

    def med(key):
        return float(np.median([d[key] for d in days]))

    return Profile(
        pf=float(np.quantile(f_values, p)), ps=float(np.quantile(s_values, p)),
        f_q=np.quantile(f_values, QS), s_q=np.quantile(s_values, QS),
        f_median=float(np.median(f_values)), s_median=float(np.median(s_values)),
        freq_day=med("freq"), use_day_min=med("use_min"), logdur_median=med("logdur_median"),
        nonuse_day_min=med("nonuse_min"), lognonuse_median=med("lognonuse_median"),
        sessions_by_hour=np.median(np.array(by_hour), axis=0),
    )


def _window_block(s: np.ndarray, e: np.ndarray, apps: np.ndarray, current: int, t: float,
                  w: float, tag: str) -> dict:
    a = t - w
    i = int(np.searchsorted(e, a, side="right"))
    cs, ce = np.maximum(s[i:], a), e[i:]
    dur = np.log1p(ce - cs)
    gaps = cs[1:] - ce[:-1]
    done = [k for k in range(i, len(s)) if k != current]
    m = window_metrics(s, e, a, w)
    n = len(cs)
    out = {
        f"n_{tag}": float(n),
        f"f_{tag}": m.f,
        f"logdur_median_{tag}": float(np.median(dur)) if n >= 2 else 0.0,
        f"logdur_mean_{tag}": float(dur.mean()) if n >= 2 else 0.0,
        f"few2_{tag}": float(n < 2),
        f"logdur_sd_{tag}": float(dur.std()) if n >= 3 else 0.0,
        f"burst_{tag}": burstiness(gaps) if len(gaps) >= 3 else 0.0,
        f"few3_{tag}": float(len(gaps) < 3),
        f"apps_mean_{tag}": float(apps[done].mean()) if done else 0.0,
        f"apps_max_{tag}": float(apps[done].max()) if done else 0.0,
        f"noapps_{tag}": float(not done),
    }
    if tag == "60":
        out["s_60"] = m.s
    return out


def extract(starts: np.ndarray, ends: np.ndarray, apps: np.ndarray, t: float, offset_s: float,
            in_session: bool, profile: Profile) -> dict:
    """Feature vector at decision time t. in_session: the point is a periodic
    re-evaluation inside a session (1) rather than the session's start (0)."""
    k = int(np.searchsorted(starts, t, side="right"))
    s, e, a = starts[:k], np.minimum(ends[:k], t), apps[:k]
    current = k - 1 if k and e[-1] >= t else -1

    v = {}
    if current >= 0:
        v["cur_logdur"] = math.log1p(t - s[current])
        prev_end = e[current - 1] if current >= 1 else float("nan")
        v["since_last_logmin"] = math.log1p((s[current] - prev_end) / 60) if current >= 1 else 0.0
    else:
        v["cur_logdur"] = 0.0
        v["since_last_logmin"] = math.log1p((t - e[-1]) / 60) if k else 0.0
    has_previous = current >= 1 or (current < 0 and k >= 1)
    v["no_previous"] = float(not has_previous)

    for w in WINDOWS_MIN:
        v.update(_window_block(s, e, a, current, t, w * 60.0, str(w)))

    day0 = local_day_start(t, offset_s)
    day = lin_stats(s, e, day0, t)
    v.update({
        "freq_day": day["freq"],
        "use_day_logmin": math.log1p(day["use_min"]),
        "logdur_median_day": day["logdur_median"],
        "nonuse_day_logmin": math.log1p(day["nonuse_min"]),
        "lognonuse_median_day": day["lognonuse_median"],
        "since_first_logmin": math.log1p((t - day["first_start"]) / 60) if day["freq"] else 0.0,
        "burst_day": burstiness(day["gaps"]) if len(day["gaps"]) >= 3 else 0.0,
    })
    max_f = max_s = positive_hours = 0.0
    h = day0
    while h + HOUR <= t:
        m = window_metrics(s, e, h, HOUR)
        max_f, max_s = max(max_f, m.f), max(max_s, m.s)
        positive_hours += float(m.f > profile.pf or m.s > profile.ps)
        h += HOUR
    v.update({"max_f60_day": max_f, "max_s60_day": max_s, "positive_hours_day": positive_hours})

    local_hour = int(((t + offset_s) % DAY) // HOUR)
    started_last_hour = float(((s >= t - HOUR) & (s < t)).sum())
    v.update({
        "prof_pf": profile.pf, "prof_ps": profile.ps,
        "prof_f_median": profile.f_median, "prof_s_median": profile.s_median,
        "prof_freq_day": profile.freq_day, "prof_use_day_logmin": math.log1p(profile.use_day_min),
        "prof_logdur_median": profile.logdur_median,
        "prof_nonuse_day_logmin": math.log1p(profile.nonuse_day_min),
        "prof_lognonuse_median": profile.lognonuse_median,
        "f60_rank": float(np.interp(v["f_60"], profile.f_q, QS)),
        "s60_rank": float(np.interp(v["s_60"], profile.s_q, QS)),
        "sessions_vs_hour_typical": started_last_hour - float(profile.sessions_by_hour[local_hour]),
    })

    minute = ((t + offset_s) % DAY) / 60
    weekday = (math.floor((t + offset_s) / DAY) + 3) % 7  # 1970-01-01 was a Thursday
    v.update({
        "tod_sin": math.sin(2 * math.pi * minute / 1440),
        "tod_cos": math.cos(2 * math.pi * minute / 1440),
        "weekend": float(weekday >= 5),
        "in_session": float(in_session),
    })
    return v
