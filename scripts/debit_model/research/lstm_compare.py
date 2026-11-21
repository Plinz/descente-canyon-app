"""Compare runs of ``build/debit-model/lstm/runs`` on the same test rows.

A candidate is a run stem without fold (``w64_s0``, ``cb_full_s0``), several stems joined by ``&``
(probabilities averaged: seed or model ensembles), each optionally weighted (``cb_full_s0*0.7&w64_s0*0.3``).
The thresholds of an ensemble are tuned again on its averaged validation probabilities.

For every candidate and fold: RPS, accuracy, MAE, GROS+ F1, AUC P(>= GROS), and the RPS gap to the
reference with a 95 % interval from a paired bootstrap over canyons (1000 draws).

    PYTHONPATH=scripts/debit_model:scripts/debit_model/research .venv/bin/python \
        scripts/debit_model/research/lstm_compare.py --folds 2024,2025-2026 --reference cb_full_s0 w64_s0 "cb_full_s0&w64_s0"
"""
from __future__ import annotations

import argparse
import json

import numpy as np
import pandas as pd

import evaluation as E
import lstm_data as D

RUNS = D.CACHE / "runs"


def load(stem: str, fold: str) -> dict:
    parts = []
    for item in stem.split("&"):
        name, _, weight = item.partition("*")
        with np.load(RUNS / f"{name.rsplit('_s', 1)[0]}_{fold}_s{name.rsplit('_s', 1)[1]}.npz", allow_pickle=True) as npz:
            parts.append((float(weight or 1.0), {key: npz[key] for key in npz.files}))
    total = sum(w for w, _ in parts)
    reference = parts[0][1]
    output = {"val_obs": reference["val_obs"], "test_obs": reference["test_obs"]}
    for key, obs_key in (("val_p", "val_obs"), ("test_p", "test_obs")):
        acc = np.zeros_like(reference[key])
        for weight, part in parts:
            if not np.array_equal(part[obs_key], reference[obs_key]):
                index = pd.Index(part[obs_key]).get_indexer(reference[obs_key])
                if (index < 0).any():
                    raise SystemExit(f"{stem}: rows differ")
                acc += weight * part[key][index]
            else:
                acc += weight * part[key]
        output[key] = acc / total
    output["thresholds"] = reference["thresholds"] if len(parts) == 1 else None
    return output


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("candidates", nargs="+")
    parser.add_argument("--reference", required=True)
    parser.add_argument("--folds", default="2024,2025-2026")
    parser.add_argument("--draws", type=int, default=1000)
    parser.add_argument("--json", default="")
    args = parser.parse_args()
    rows = D.base()[["observationId", "canyonId", "day", "rank"]].drop_duplicates("observationId").set_index("observationId")
    report = {}
    for fold in args.folds.split(","):
        reference = load(args.reference, fold)
        obs = reference["test_obs"]
        y = rows.loc[obs, "rank"].to_numpy()
        y_val = rows.loc[reference["val_obs"], "rank"].to_numpy()
        canyons = rows.loc[obs, "canyonId"].to_numpy()
        groups = pd.Series(np.arange(len(obs))).groupby(canyons).apply(lambda s: s.to_numpy()).to_list()
        rng = np.random.default_rng(5)
        draws = [np.concatenate([groups[i] for i in rng.integers(0, len(groups), len(groups))]) for _ in range(args.draws)]

        def per_row_rps(p):
            c_pred = np.cumsum(p, axis=1)[:, :-1]
            c_true = (np.arange(5)[None, :] >= y[:, None]).astype(float)
            return np.sum((c_pred - c_true) ** 2, axis=1) / 5

        ref_rps = per_row_rps(reference["test_p"])
        print(f"\n== fold {fold}: {len(obs)} test rows, {len(groups)} canyons, reference {args.reference}")
        print(f"{'candidate':42s} {'RPS':>8s} {'dRPS':>9s} {'95% CI':>21s} {'acc':>6s} {'MAE':>6s} {'F1 GROS+':>8s} {'AUC':>6s}")
        for candidate in [args.reference] + [c for c in args.candidates if c != args.reference]:
            run = load(candidate, fold)
            index = pd.Index(run["test_obs"]).get_indexer(obs)
            if (index < 0).any():
                raise SystemExit(f"{candidate}: test rows differ from the reference")
            p = run["test_p"][index]
            val_index = pd.Index(run["val_obs"]).get_indexer(reference["val_obs"])
            thresholds = run["thresholds"] if run["thresholds"] is not None else E.tune_thresholds(y_val, run["val_p"][val_index])
            levels = E.level_metrics(y, E.decide(p, thresholds))
            probabilistic = E.probabilistic_metrics(y, p)
            gap = per_row_rps(p) - ref_rps
            boot = np.array([gap[d].mean() for d in draws])
            low, high = np.percentile(boot, [2.5, 97.5])
            print(f"{candidate:42s} {probabilistic['rps']:8.5f} {gap.mean():+9.5f} [{low:+.5f}; {high:+.5f}] {levels['accuracy']:6.3f} {levels['mae']:6.3f} {levels['highF1']:8.3f} {probabilistic['highAuc']:6.3f}")
            report.setdefault(fold, {})[candidate] = {"rps": probabilistic["rps"], "dRps": float(gap.mean()), "ci": [float(low), float(high)], "accuracy": levels["accuracy"], "mae": levels["mae"], "highF1": levels["highF1"], "highAuc": probabilistic["highAuc"]}
    if args.json:
        with open(args.json, "w") as handle:
            json.dump(report, handle, indent=1)


if __name__ == "__main__":
    main()
