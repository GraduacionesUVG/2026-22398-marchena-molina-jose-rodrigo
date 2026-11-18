"""LSTM over the last 24 h of sessions + MLP with the profile (E8, E13).

Input per decision point t:
  sequence  every session that started in [t - 24 h, t], oldest first, at most
            N_MAX (the most recent ones). One step per session: log1p gap
            since the previous session's end, log1p duration, log1p apps,
            open-session flag, sin and cos of the local start hour. The last
            step is the open session: duration up to t, apps 0, flag 1 (its
            final app count is not known at t). Recomputed from scratch at
            every decision point (no state carried across points).
  static    profile block + time of day + weekend + point type, standardized
            with a scaler fitted on MPUD.

The LSTM's last hidden state is concatenated with the static vector and fed to
a small MLP that outputs the logit of y.
"""

import copy
import math

import numpy as np
import pandas as pd
import torch
from sklearn.preprocessing import StandardScaler
from torch import nn
from torch.nn.utils.rnn import pack_padded_sequence, pad_sequence

from experiments.modeling import loso_folds, temporal_split
from pipelines.labels.build_windows import seconds

DAY, HOUR = 86400.0, 3600.0
N_MAX = 256
STATIC = ["prof_pf", "prof_ps", "prof_f_median", "prof_s_median", "prof_freq_day", "prof_use_day_logmin",
          "prof_logdur_median", "prof_nonuse_day_logmin", "prof_lognonuse_median",
          "tod_sin", "tod_cos", "weekend", "in_session"]
STEP_DIM = 6
SEED = 0


class SessionLog:
    """Per-person step matrices built once; samples are slices of them."""

    def __init__(self, sessions: pd.DataFrame):
        self.people = {}
        for user, s in sessions.groupby("user_id", sort=False):
            starts, ends = seconds(s["start_utc"]), seconds(s["end_utc"])
            offset = float((s["start_local"].iloc[0] - s["start_utc"].iloc[0]).total_seconds())
            prev_end = np.concatenate([[starts[0]], ends[:-1]])
            hour = ((starts + offset) % DAY) / HOUR
            steps = np.stack([
                np.log1p(np.maximum(starts - prev_end, 0)),
                np.log1p(ends - starts),
                np.log1p(s["app_count_max"].to_numpy(dtype=float)),
                np.zeros(len(s)),
                np.sin(2 * math.pi * hour / 24),
                np.cos(2 * math.pi * hour / 24),
            ], axis=1).astype(np.float32) / np.array([10, 10, 3, 1, 1, 1], dtype=np.float32)
            self.people[user] = (starts, ends, steps)

    def sequence(self, user: str, t: float) -> np.ndarray:
        starts, ends, steps = self.people[user]
        k = int(np.searchsorted(starts, t, side="right"))
        i = max(int(np.searchsorted(starts, t - DAY, side="left")), k - N_MAX)
        seq = steps[i:k].copy()
        if len(seq) and ends[k - 1] >= t:  # open session: only what is known at t
            seq[-1, 1] = np.log1p(t - starts[k - 1]) / 10
            seq[-1, 2] = 0.0
            seq[-1, 3] = 1.0
        if not len(seq):
            seq = np.zeros((1, STEP_DIM), dtype=np.float32)
        return seq


class Data(torch.utils.data.Dataset):
    def __init__(self, log: SessionLog, df: pd.DataFrame, scaler: StandardScaler):
        self.log = log
        self.users = df["user_id"].to_numpy()
        self.t = seconds(pd.to_datetime(df["t_utc"]))
        self.static = scaler.transform(df[STATIC].to_numpy(dtype=np.float32)).astype(np.float32)
        self.y = df["y"].to_numpy(dtype=np.float32)

    def __len__(self):
        return len(self.y)

    def __getitem__(self, i):
        return torch.from_numpy(self.log.sequence(self.users[i], self.t[i])), self.static[i], self.y[i]


def collate(batch):
    seqs, static, y = zip(*batch)
    lengths = torch.tensor([len(s) for s in seqs])
    return pad_sequence(seqs, batch_first=True), lengths, torch.tensor(np.stack(static)), torch.tensor(y)


class Net(nn.Module):
    def __init__(self, hidden: int = 32):
        super().__init__()
        self.lstm = nn.LSTM(STEP_DIM, hidden, batch_first=True)
        self.head = nn.Sequential(nn.Linear(hidden + len(STATIC), 32), nn.ReLU(), nn.Dropout(0.2),
                                  nn.Linear(32, 1))

    def forward(self, seq, lengths, static):
        packed = pack_padded_sequence(seq, lengths, batch_first=True, enforce_sorted=False)
        _, (h, _) = self.lstm(packed)
        return self.head(torch.cat([h[-1], static], dim=1)).squeeze(1)


class LSTMModel:
    PRE_LR, FINE_LR = 3e-3, 3e-4
    PRE_EPOCHS, FINE_EPOCHS, PATIENCE, BATCH = 30, 20, 3, 256

    def __init__(self, net: Net, scaler: StandardScaler):
        self.net, self.scaler = net, scaler

    def _loader(self, log, df, shuffle):
        return torch.utils.data.DataLoader(Data(log, df, self.scaler), batch_size=self.BATCH,
                                           shuffle=shuffle, collate_fn=collate)

    def _val_loss(self, loader):
        self.net.eval()
        loss, n = 0.0, 0
        with torch.no_grad():
            for seq, lengths, static, y in loader:
                loss += nn.functional.binary_cross_entropy_with_logits(
                    self.net(seq, lengths, static), y, reduction="sum").item()
                n += len(y)
        return loss / n

    def fit(self, log, train_df, val_df, lr, max_epochs, verbose=False):
        torch.manual_seed(SEED)
        opt = torch.optim.Adam(self.net.parameters(), lr=lr)
        tr, va = self._loader(log, train_df, True), self._loader(log, val_df, False)
        best, best_loss, bad = copy.deepcopy(self.net.state_dict()), self._val_loss(va), 0
        for epoch in range(max_epochs):
            self.net.train()
            for seq, lengths, static, y in tr:
                opt.zero_grad()
                nn.functional.binary_cross_entropy_with_logits(self.net(seq, lengths, static), y).backward()
                opt.step()
            loss = self._val_loss(va)
            if verbose:
                print(f"epoch {epoch + 1}: val logloss {loss:.4f}")
            if loss < best_loss - 1e-5:
                best, best_loss, bad = copy.deepcopy(self.net.state_dict()), loss, 0
            else:
                bad += 1
                if bad >= self.PATIENCE:
                    break
        self.net.load_state_dict(best)
        return self

    def predict(self, log, df):
        self.net.eval()
        out = []
        with torch.no_grad():
            for seq, lengths, static, _ in self._loader(log, df, False):
                out.append(torch.sigmoid(self.net(seq, lengths, static)).numpy())
        return np.concatenate(out)


def pretrain(log: SessionLog, mpud: pd.DataFrame, n_val: int = 20, verbose=True) -> LSTMModel:
    users = sorted(mpud["user_id"].unique())
    val = set(np.random.default_rng(SEED).choice(users, size=n_val, replace=False))
    scaler = StandardScaler().fit(mpud[STATIC].to_numpy(dtype=np.float32))
    torch.manual_seed(SEED)
    model = LSTMModel(Net(), scaler)
    return model.fit(log, mpud[~mpud["user_id"].isin(val)], mpud[mpud["user_id"].isin(val)],
                     LSTMModel.PRE_LR, LSTMModel.PRE_EPOCHS, verbose)


def finetune(pre: LSTMModel | None, log, train_df, val_df) -> LSTMModel:
    if pre is None:  # real data only: fresh network and scaler (training people), pretraining schedule
        torch.manual_seed(SEED)
        scaler = StandardScaler().fit(train_df[STATIC].to_numpy(dtype=np.float32))
        return LSTMModel(Net(), scaler).fit(log, train_df, val_df, LSTMModel.PRE_LR, LSTMModel.PRE_EPOCHS)
    model = LSTMModel(copy.deepcopy(pre.net), pre.scaler)
    return model.fit(log, train_df, val_df, LSTMModel.FINE_LR, LSTMModel.FINE_EPOCHS)


def run_loso(log, real: pd.DataFrame, pre: LSTMModel | None, verbose=True) -> np.ndarray:
    scores = np.full(len(real), np.nan)
    for n, (test, val, train) in enumerate(loso_folds(real["user_id"]), 1):
        tr, va, te = (real["user_id"].isin(g) for g in (train, val, test))
        model = finetune(pre, log, real[tr], real[va])
        scores[te.to_numpy()] = model.predict(log, real[te])
        if verbose:
            print(f"fold {n}/18 done")
    return scores


def run_temporal(log, real: pd.DataFrame, pre, n_val: int = 3):
    train, test = temporal_split(real)
    users = sorted(train["user_id"].unique())
    val = set(np.random.default_rng(SEED).choice(users, size=n_val, replace=False))
    model = finetune(pre, log, train[~train["user_id"].isin(val)], train[train["user_id"].isin(val)])
    return test, model.predict(log, test)
