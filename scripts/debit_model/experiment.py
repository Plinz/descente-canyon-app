"""Fast A/B harness for feature/model candidates.

Each candidate adds columns to the base dataset (``extra feature providers``) and/or changes the
CatBoost parameters. It is trained on two walk-forward folds with a faster learning rate than the
production fit; only relative comparisons between candidates are meaningful.

Usage: python experiment.py --candidates baseline,neighbors --output build/debit-model/experiments/run1.json
"""
from __future__ import annotations

import argparse
import json
import time
from datetime import date
from pathlib import Path

import numpy as np
import pandas as pd
from catboost import CatBoostClassifier, Pool

import evaluation as E
import features as F
import history as H
import train as T

EXPERIMENT_DIR = Path("build/debit-model/experiments")
FOLDS = [("2024", "2024-01-01", "2025-01-01"), ("2025-2026", "2025-01-01", "2026-11-01")]
FAST_PARAMS = dict(T.PARAMS, learning_rate=0.15, iterations=1500, verbose=0)


def day(iso: str) -> int:
    return F.to_epoch_day(date.fromisoformat(iso))


def fold_frame(base, histories, observations, cutoff: int) -> pd.DataFrame:
    cache = EXPERIMENT_DIR / "cache" / f"history_{cutoff}.pkl"
    if cache.exists():
        return pd.read_pickle(cache)
    cache.parent.mkdir(parents=True, exist_ok=True)
    frame = H.add_history(base, histories, observations, cutoff)
    frame.to_pickle(cache)
    return frame


def run_candidate(name: str, frame: pd.DataFrame, feature_names: list[str], params: dict, cutoff: int, end: int, cold: bool = True, variants: dict | None = None, weights=None, seeds: int = 1) -> dict:
    train_all = frame[frame["day"] < cutoff]
    test = frame[(frame["day"] >= cutoff) & (frame["day"] < end)]
    validation_start = cutoff - T.VALIDATION_DAYS
    train = train_all[train_all["day"] < validation_start]
    validation = train_all[train_all["day"] >= validation_start]
    if cold:
        sample = train.sample(frac=T.COLD_AUGMENT_FRACTION, random_state=13)
        train = pd.concat([train, H.cold_canyon(sample)], ignore_index=True)

    def matrix(part):
        values = part[feature_names].to_numpy(dtype=np.float64)
        return np.where(np.isnan(values), F.MISSING, values).astype(np.float32)

    started = time.time()
    train_weights = weights(train) if weights is not None else None
    models = []
    for seed in range(seeds):
        model = CatBoostClassifier(**dict(params, random_seed=params.get("random_seed", 7) + seed))
        model.fit(
            Pool(matrix(train), [F.LEVELS[r] for r in train["rank"]], feature_names=feature_names, weight=train_weights),
            eval_set=Pool(matrix(validation), [F.LEVELS[r] for r in validation["rank"]], feature_names=feature_names),
            early_stopping_rounds=100, use_best_model=True,
        )
        models.append(model)
    order = [list(models[0].classes_).index(level) for level in F.LEVELS]

    class Ensemble:
        def predict_proba(self, x):
            return np.mean([m.predict_proba(x) for m in models], axis=0)

        def get_best_iteration(self):
            return int(np.mean([m.get_best_iteration() for m in models]))

        def get_feature_importance(self):
            return np.mean([m.get_feature_importance() for m in models], axis=0)

    model = Ensemble()
    val_p = model.predict_proba(matrix(validation))[:, order]
    thresholds = E.tune_thresholds(validation["rank"].to_numpy(), val_p)
    test_p = model.predict_proba(matrix(test))[:, order]
    y = test["rank"].to_numpy()
    pred = E.decide(test_p, thresholds)
    result = {**E.level_metrics(y, pred), **E.probabilistic_metrics(y, test_p)}
    result["bestIteration"] = int(model.get_best_iteration())
    result["seconds"] = round(time.time() - started)
    importances = model.get_feature_importance()
    result["topFeatures"] = sorted(zip(feature_names, [round(float(v), 3) for v in importances]), key=lambda x: -x[1])[:15]
    for variant_name, variant_frame in (variants or {}).items():
        variant_test = variant_frame[(variant_frame["day"] >= cutoff) & (variant_frame["day"] < end)]
        variant_p = model.predict_proba(matrix(variant_test))[:, order]
        variant_y = variant_test["rank"].to_numpy()
        result[f"variant_{variant_name}"] = {
            **{k: round(v, 4) for k, v in E.level_metrics(variant_y, E.decide(variant_p, thresholds)).items() if isinstance(v, float)},
            **{k: round(v, 4) for k, v in E.probabilistic_metrics(variant_y, variant_p).items() if isinstance(v, float)},
        }
    result["_probabilities"] = test_p
    return result


def main() -> None:
    import providers  # candidate feature providers

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--candidates", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--dataset-dir", default="build/debit-model/dataset")
    parser.add_argument("--folds", default="all")
    args = parser.parse_args()

    base, histories, observations = T.load(Path(args.dataset_dir))
    report = {}
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    folds = FOLDS if args.folds == "all" else [f for f in FOLDS if f[0] in args.folds.split(",")]
    for fold_name, start, end in folds:
        cutoff, end_day = day(start), day(end)
        frame = fold_frame(base, histories, observations, cutoff)
        for candidate in args.candidates.split(","):
            spec = providers.CANDIDATES[candidate]
            extended, names = spec["build"](frame, cutoff)
            params = dict(FAST_PARAMS, **spec.get("params", {}))
            variants = spec["variants"](frame) if "variants" in spec else None
            result = run_candidate(
                candidate, extended, names, params, cutoff, end_day, variants=variants,
                cold=spec.get("cold", True), weights=spec.get("weights"), seeds=spec.get("seeds", 1),
            )
            probabilities = result.pop("_probabilities")
            np.save(output.with_name(f"{output.stem}_{fold_name}_{candidate}.npy"), probabilities)
            report.setdefault(fold_name, {})[candidate] = result
            print(json.dumps({"fold": fold_name, "candidate": candidate, **{k: (round(v, 4) if isinstance(v, float) else v) for k, v in result.items() if k != "topFeatures"}}), flush=True)
            output.write_text(json.dumps(report, indent=1, default=str))


if __name__ == "__main__":
    main()
