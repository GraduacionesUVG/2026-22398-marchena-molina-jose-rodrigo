"""Candidate feature groups for E8c (README §6.5).

Same contract as smartuse/features.py: only the session log up to t and the
frozen profile; ends are clipped to t and the open session's apps are never
read. Groups:
  alternation   short-session share, short/long switch rate, memory
                coefficient of the day's durations, RMSSD of log intervals
  in_session    where the current duration sits among the profile's sessions
  relative      current values minus or against the profile
  trend         recent change: last 5 min, acceleration, last long session
  hour_profile  the person's positive rates and activity at this local hour
"""

import math
from dataclasses import dataclass

import numpy as np

from smartuse.windows import window_metrics

DAY, HOUR = 86400.0, 3600.0
SHORT_S = 60.0
LONG_S = 600.0

GROUPS = {
    "alternation": ["short_share_60", "switch_rate_60", "memory_day", "rmssd_gap_60"],
    "in_session": ["cur_dur_pct", "cur_survival_10", "cur_vs_typical"],
    "relative": ["f60_minus_pf", "s60_minus_ps", "use_vs_hour", "n15_vs_hour"],
    "trend": ["n_5", "accel", "since_long_logmin", "last_dur_log"],
    "hour_profile": ["rate_f_hour", "rate_s_hour", "points_hour"],
}


@dataclass(frozen=True)
class ExtraProfile:
    dur_sorted: np.ndarray     # profile session durations (s), sorted
    logdur_median: float
    use_by_hour: np.ndarray    # median cumulative use (min) by the end of each local hour
    starts_by_hour: np.ndarray  # median sessions started per local hour
    rate_f_hour: np.ndarray    # share of profile points with F > pf, per local hour
    rate_s_hour: np.ndarray
    points_hour: np.ndarray    # profile decision points per day, per local hour


def _hour(t, offset_s):
    return np.floor(((np.asarray(t) + offset_s) % DAY) / HOUR).astype(int)


def build_extra_profile(starts, ends, prof_t, prof_f, prof_s, pf, ps, day_bounds, offset_s) -> ExtraProfile:
    durs, use, by_hour = [], [], []
    for a, b in day_bounds:
        m = (starts >= a) & (starts < b)
        durs.append(ends[m] - starts[m])
        by_hour.append(np.bincount(_hour(starts[m], offset_s), minlength=24))
        cum = []
        for h in range(24):
            hi = a + (h + 1) * HOUR
            s, e = starts[m], np.minimum(ends[m], hi)
            cum.append(float(np.clip(e - s, 0, None)[s < hi].sum() / 60))
        use.append(cum)
    durs = np.sort(np.concatenate(durs)) if durs else np.array([])
    hours = _hour(prof_t, offset_s)
    f_pos, s_pos = prof_f > pf, prof_s > ps
    rate_f, rate_s, pts = np.full(24, f_pos.mean()), np.full(24, s_pos.mean()), np.zeros(24)
    for h in range(24):
        m = hours == h
        if m.any():
            rate_f[h], rate_s[h], pts[h] = f_pos[m].mean(), s_pos[m].mean(), m.sum() / len(day_bounds)
    return ExtraProfile(durs, float(np.median(np.log1p(durs))) if len(durs) else 0.0,
                        np.median(np.array(use), axis=0), np.median(np.array(by_hour), axis=0),
                        rate_f, rate_s, pts)


def extract_extra(starts, ends, apps, t, offset_s, xp: ExtraProfile, pf: float, ps: float) -> dict:
    k = int(np.searchsorted(starts, t, side="right"))
    s, e = starts[:k], np.minimum(ends[:k], t)
    current = k - 1 if k and e[-1] >= t else -1
    done_end = current if current >= 0 else k  # completed sessions are [0, done_end)
    h = int(_hour(t, offset_s))
    v = {}

    # alternation (last 60 min, sessions clipped to the window)
    i60 = int(np.searchsorted(e, t - HOUR, side="right"))
    cs, ce = np.maximum(s[i60:], t - HOUR), e[i60:]
    d60 = ce - cs
    short = d60 < SHORT_S
    gaps = cs[1:] - ce[:-1]
    v["short_share_60"] = float(short.mean()) if len(d60) else 0.0
    v["switch_rate_60"] = float((short[1:] != short[:-1]).mean()) if len(d60) >= 2 else 0.0
    day0 = math.floor((t + offset_s) / DAY) * DAY - offset_s
    dd = np.log1p(e[s >= day0] - s[s >= day0])
    v["memory_day"] = (float(np.corrcoef(dd[:-1], dd[1:])[0, 1])
                       if len(dd) >= 4 and dd[:-1].std() > 0 and dd[1:].std() > 0 else 0.0)
    lg = np.log1p(gaps)
    v["rmssd_gap_60"] = float(np.sqrt(np.mean(np.diff(lg) ** 2))) if len(lg) >= 3 else 0.0

    # in session
    cur = t - s[current] if current >= 0 else 0.0
    n = len(xp.dur_sorted)
    longer = xp.dur_sorted[xp.dur_sorted > cur]
    v["cur_dur_pct"] = float(np.searchsorted(xp.dur_sorted, cur, side="right") / n) if n and current >= 0 else 0.0
    v["cur_survival_10"] = float((longer > cur + LONG_S).mean()) if current >= 0 and len(longer) else 0.0
    v["cur_vs_typical"] = math.log1p(cur) - xp.logdur_median if current >= 0 else 0.0

    # relative to the profile
    m60 = window_metrics(s, e, t - HOUR, HOUR)
    v["f60_minus_pf"] = m60.f - pf
    v["s60_minus_ps"] = m60.s - ps
    today = (np.clip(e - np.maximum(s, day0), 0, None)[e > day0]).sum() / 60
    typical = xp.use_by_hour[h - 1] if h > 0 else 0.0
    v["use_vs_hour"] = math.log1p(today) - math.log1p(typical)
    started_15 = float(((s >= t - 900) & (s < t)).sum())
    v["n15_vs_hour"] = started_15 - xp.starts_by_hour[h] / 4

    # trend
    v["n_5"] = float(len(e) - np.searchsorted(e, t - 300, side="right"))
    started_45 = float(((s >= t - HOUR) & (s < t - 900)).sum())
    v["accel"] = started_15 - started_45 / 3
    done = np.arange(done_end)
    long_done = done[(e[done] - s[done]) >= LONG_S] if len(done) else done
    v["since_long_logmin"] = (math.log1p(min((t - e[long_done[-1]]) / 60, 1440)) if len(long_done)
                              else math.log1p(1440))
    v["last_dur_log"] = math.log1p(e[done_end - 1] - s[done_end - 1]) if done_end >= 1 else 0.0

    # hour profile
    v["rate_f_hour"] = float(xp.rate_f_hour[h])
    v["rate_s_hour"] = float(xp.rate_s_hour[h])
    v["points_hour"] = float(xp.points_hour[h])
    return v
