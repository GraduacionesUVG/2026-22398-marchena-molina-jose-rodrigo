"""Window metrics over one person's session log (README §5.1–§5.3).

Times are float seconds since the Unix epoch (UTC). Sessions are sorted,
non-overlapping, half-open [start, end). Only the session log is used, so
the same functions can be ported to the device (training/deployment parity).

F (fragmented use) = n_sessions * span / W, 0 with fewer than two sessions.
S (sticky use) = ln(longest session in seconds). Sessions are clipped to the
window (cleaning step 7), which caps S at ln(W) (step 8); durations under
1 s count as 1 s so S is never negative.
"""

import math
from dataclasses import dataclass

import numpy as np
import pandas as pd


@dataclass(frozen=True)
class WindowMetrics:
    n_sessions: int
    span_s: float
    longest_s: float
    f: float
    s: float


EMPTY = WindowMetrics(0, 0.0, 0.0, 0.0, 0.0)


def window_indices(starts: np.ndarray, ends: np.ndarray, a: float, w: float) -> tuple[int, int]:
    """Range [i, j) of the sessions that overlap [a, a + w)."""
    i = int(np.searchsorted(ends, a, side="right"))
    j = int(np.searchsorted(starts, a + w, side="left"))
    return i, max(i, j)


def window_metrics(starts: np.ndarray, ends: np.ndarray, a: float, w: float) -> WindowMetrics:
    """F and S of the window [a, a + w), with sessions clipped to it."""
    i, j = window_indices(starts, ends, a, w)
    if j == i:
        return EMPTY
    clipped_start = np.maximum(starts[i:j], a)
    clipped_end = np.minimum(ends[i:j], a + w)
    n = j - i
    span = float(clipped_end[-1] - clipped_start[0])
    longest = float((clipped_end - clipped_start).max())
    f = n * span / w if n >= 2 else 0.0
    return WindowMetrics(n, span, longest, f, math.log(max(longest, 1.0)))


def decision_points(starts: np.ndarray, ends: np.ndarray, k: float, t_max: float):
    """Decision times: each session start (unlock), then every k s inside the
    session while t < end and t < start + t_max.

    Returns (times, session_idx, is_unlock).
    """
    stop = np.minimum(ends, starts + t_max)
    counts = np.maximum(np.ceil((stop - starts) / k).astype(int), 1)
    session_idx = np.repeat(np.arange(len(starts)), counts)
    step = np.arange(counts.sum()) - np.repeat(np.cumsum(counts) - counts, counts)
    times = starts[session_idx] + step * k
    return times, session_idx, step == 0


def grid_starts(first: float, last: float, w: float, offset_s: float) -> np.ndarray:
    """Starts of contiguous windows of width w, aligned to local clock time,
    covering [first, last)."""
    a0 = math.floor((first + offset_s) / w) * w - offset_s
    return np.arange(a0, last, w)


def person_windows(starts: np.ndarray, ends: np.ndarray, no_close: np.ndarray,
                   data_start: float, data_end: float, times: np.ndarray, w: float,
                   missing_starts: np.ndarray | None = None,
                   missing_ends: np.ndarray | None = None) -> pd.DataFrame:
    """Metrics of [t, t + w) for each t, with the masks.

    masked: the window overlaps a no_close session (cleaning step 5), so it
    gets no label. censored: the window starts before the data, runs past
    its end or overlaps a missing-data interval [missing_start, missing_end)
    (cleaning step 6), so its metrics are incomplete.
    """
    rows = []
    for t in times:
        m = window_metrics(starts, ends, t, w)
        i, j = window_indices(starts, ends, t, w)
        rows.append((t, m.n_sessions, m.span_s, m.longest_s, m.f, m.s, bool(no_close[i:j].any())))
    out = pd.DataFrame(rows, columns=["t", "n_sessions", "span_s", "longest_s", "f", "s", "masked"])
    out["censored"] = (out["t"] < data_start) | (out["t"] + w > data_end)
    if missing_starts is not None and len(missing_starts):
        t = out["t"].to_numpy()[:, None]
        overlaps = (t < missing_ends[None, :]) & (t + w > missing_starts[None, :])
        out["censored"] |= overlaps.any(axis=1)
    return out
