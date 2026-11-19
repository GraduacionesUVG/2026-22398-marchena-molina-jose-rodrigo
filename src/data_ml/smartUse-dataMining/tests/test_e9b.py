import numpy as np
import pandas as pd

from experiments.e9b import episodes, simulate_joint


def frame(t_min, f, s, yf, ys, user="u"):
    n = len(t_min)
    return pd.DataFrame({"user_id": [user] * n, "t": np.array(t_min, float) * 60, "day": ["d"] * n,
                         "p_f": f, "p_s": s, "y_f": yf, "y_s": ys})


def test_episode_rule_one_per_pattern_per_hour_and_cooldown():
    # F fires at every point 0, 5, 20, 61 min; S fires at 12 min
    df = frame([0, 5, 12, 20, 61], [.9, .9, .1, .9, .9], [.1, .1, .9, .1, .1], [1] * 5, [0] * 5)
    given = simulate_joint(df, c_f=.5, c_s=.5)
    # 0: F; 5: F blocked (cooldown + episode); 12: S (cooldown passed, S never used); 20: F blocked (episode);
    # 61: F allowed again (61 min since last F)
    assert given.tolist() == ["F", "", "S", "", "F"]


def test_both_fire_the_larger_margin_wins():
    df = frame([0], [.6], [.9], [1], [1])
    assert simulate_joint(df, c_f=.5, c_s=.5).tolist() == ["S"]


def test_episodes_are_runs_of_positives_split_by_gaps():
    df = frame([0, 10, 20, 100, 110], [0] * 5, [0] * 5, [1, 1, 0, 1, 1], [0] * 5)
    ep = episodes(df, "y_f")
    assert ep.tolist()[:2] == [ep.iloc[0]] * 2 and np.isnan(ep.iloc[2])
    assert ep.iloc[3] == ep.iloc[4] != ep.iloc[0]
