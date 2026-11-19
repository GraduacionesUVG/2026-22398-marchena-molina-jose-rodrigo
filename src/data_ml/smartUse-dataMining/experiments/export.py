"""Export the merged models to a plain-text model file read by the Java evaluator.

Tree semantics copied from XGBoost (and reproduced in export/java/SmartUseModel.java):
  - features and thresholds are float32; at an internal node go to `yes` if
    x < threshold, else `no`; a NaN goes to `missing`
  - a seed's margin = base_margin + sum of its trees' leaves, where
    base_margin = logit(base_score) for binary:logistic
  - the merged model averages the seeds' margins, applies sigmoid, then the
    calibrator (raw, or Platt: sigmoid(a * logit(p) + b)), and fires when
    score > cutoff

File format (text, one item per line):
  smartuse-model 1
  pattern <F|S>
  features <n> <name,name,...>
  calibrator raw | calibrator platt <a> <b>
  cutoff <c>
  seeds <n>
  then per seed: "seed <base_margin> <n_trees>", and per tree "tree <n_nodes>"
  followed by one line per node id: <feature> <threshold> <yes> <no> <missing> <leaf>
  (feature = -1 marks a leaf; unused ids are written as leaves with value 0)
"""

import json
from pathlib import Path

import numpy as np

from smartuse.model import MergedModel


def base_margin(booster) -> float:
    cfg = json.loads(booster.save_config())
    p = float(cfg["learner"]["learner_model_param"]["base_score"].strip("[]"))
    return float(np.log(p / (1 - p)))


def parse_trees(booster) -> list[dict]:
    """Each tree as arrays indexed by node id: feature, threshold, yes, no, missing, leaf."""
    trees = []
    for dump in booster.get_dump(dump_format="json"):
        nodes = {}

        def walk(n):
            nodes[n["nodeid"]] = n
            for c in n.get("children", []):
                walk(c)

        walk(json.loads(dump))
        size = max(nodes) + 1
        t = {"feature": np.full(size, -1, dtype=np.int32), "threshold": np.zeros(size, dtype=np.float32),
             "yes": np.zeros(size, dtype=np.int32), "no": np.zeros(size, dtype=np.int32),
             "missing": np.zeros(size, dtype=np.int32), "leaf": np.zeros(size, dtype=np.float64)}
        for i, n in nodes.items():
            if "leaf" in n:
                t["leaf"][i] = n["leaf"]
            else:
                t["feature"][i] = int(n["split"].lstrip("f"))
                t["threshold"][i] = np.float32(n["split_condition"])
                t["yes"][i], t["no"][i], t["missing"][i] = n["yes"], n["no"], n["missing"]
        trees.append(t)
    return trees


def margin_reference(trees: list[dict], base: float, X: np.ndarray) -> np.ndarray:
    """Margin of one seed, computed from the parsed trees (vectorized over rows)."""
    X = np.asarray(X, dtype=np.float32)
    rows = np.arange(len(X))
    total = np.full(len(X), base, dtype=np.float64)
    for t in trees:
        node = np.zeros(len(X), dtype=np.int32)
        while True:
            f = t["feature"][node]
            inner = f >= 0
            if not inner.any():
                break
            x = X[rows[inner], f[inner]]
            nd = node[inner]
            nxt = np.where(np.isnan(x), t["missing"][nd], np.where(x < t["threshold"][nd], t["yes"][nd], t["no"][nd]))
            node[inner] = nxt
        total += t["leaf"][node]
    return total


def _calibrate(p: np.ndarray, cal: dict) -> np.ndarray:
    if cal["method"] == "platt":
        p = np.clip(p, 1e-6, 1 - 1e-6)
        return 1 / (1 + np.exp(-(cal["a"] * np.log(p / (1 - p)) + cal["b"])))
    return p


def write_model_file(model: MergedModel, path: Path, pattern: str) -> None:
    lines = ["smartuse-model 1", f"pattern {pattern}",
             f"features {len(model.features)} {','.join(model.features)}"]
    cal = model.calibrator
    lines.append("calibrator raw" if cal["method"] == "raw" else f"calibrator platt {cal['a']!r} {cal['b']!r}")
    lines += [f"cutoff {float(model.cutoff)!r}", f"seeds {len(model.boosters)}"]
    for b in model.boosters:
        trees = parse_trees(b)
        lines.append(f"seed {base_margin(b)!r} {len(trees)}")
        for t in trees:
            lines.append(f"tree {len(t['feature'])}")
            for i in range(len(t["feature"])):
                lines.append(f"{t['feature'][i]} {float(t['threshold'][i])!r} {t['yes'][i]} {t['no'][i]} "
                             f"{t['missing'][i]} {float(t['leaf'][i])!r}")
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    Path(path).write_text("\n".join(lines) + "\n", encoding="utf-8")


def read_model_file(path: Path) -> dict:
    """Parse a model file back; `score` scores a matrix from the file alone."""
    it = iter(Path(path).read_text(encoding="utf-8").splitlines())
    assert next(it) == "smartuse-model 1"
    pattern = next(it).split()[1]
    features = next(it).split()[2].split(",")
    c = next(it).split()
    cal = {"method": "raw"} if c[1] == "raw" else {"method": "platt", "a": float(c[2]), "b": float(c[3])}
    cutoff = float(next(it).split()[1])
    seeds = []
    for _ in range(int(next(it).split()[1])):
        _, base, n_trees = next(it).split()
        trees = []
        for _ in range(int(n_trees)):
            n = int(next(it).split()[1])
            rows = [next(it).split() for _ in range(n)]
            trees.append({"feature": np.array([int(r[0]) for r in rows], dtype=np.int32),
                          "threshold": np.array([float(r[1]) for r in rows], dtype=np.float32),
                          "yes": np.array([int(r[2]) for r in rows], dtype=np.int32),
                          "no": np.array([int(r[3]) for r in rows], dtype=np.int32),
                          "missing": np.array([int(r[4]) for r in rows], dtype=np.int32),
                          "leaf": np.array([float(r[5]) for r in rows])})
        seeds.append((float(base), trees))

    def score(X):
        margin = np.mean([margin_reference(trees, base, X) for base, trees in seeds], axis=0)
        return _calibrate(1 / (1 + np.exp(-margin)), cal)

    return {"pattern": pattern, "features": features, "calibrator": cal, "cutoff": cutoff, "score": score}
