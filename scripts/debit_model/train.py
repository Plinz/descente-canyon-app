"""Walk-forward evaluation and final training of the debit models.

Two models share the feature contract:

* ``today`` (J0): every real-time source is known up to J-1;
* ``horizon`` (J+1, J+2): reports and gauges are only known up to today - 1, i.e. lagged by the
  horizon (``infoLagDays``); trained on rows whose real-time features are lagged by 1 or 2 days.

Walk-forward: for each test period, the models only know observations dated before the period
(canyon history, massif/region priors and training rows), exactly like the app on the first day of
that period. Decision thresholds are tuned on the last training year (validation), never on test.

Usage:
  python train.py walkforward --dataset-dir build/debit-model/dataset --output-dir build/debit-model/walkforward
  python train.py final --dataset-dir build/debit-model/dataset --walkforward-dir build/debit-model/walkforward --output-dir build/debit-model/final
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

FOLDS = [
    ("2023", "2023-01-01", "2024-01-01"),
    ("2024", "2024-01-01", "2025-01-01"),
    ("2025-2026", "2025-01-01", "2026-11-01"),
]
VALIDATION_DAYS = 365
COLD_AUGMENT_FRACTION = 0.12  # share of training rows duplicated as "cold canyon" rows
AUTO_HIDDEN_FRACTION = 0.2  # training rows without yesterday's automatic gauges (app used before 05:44 UTC)
PARAMS = dict(
    loss_function="MultiClass",
    depth=7,
    learning_rate=0.08,
    l2_leaf_reg=5.0,
    iterations=3000,
    border_count=128,
    random_strength=1.0,
    thread_count=8,
    random_seed=7,
    verbose=200,
    class_names=F.LEVELS,
    train_dir="build/debit-model/catboost_info",
)


def day(iso: str) -> int:
    return F.to_epoch_day(date.fromisoformat(iso))


def load(dataset_dir: Path):
    base = pd.read_pickle(dataset_dir / "base.pkl")
    histories = pd.read_pickle(dataset_dir / "canyon_history.pkl")
    canyons = {int(row["id"]): row for row in json.loads(Path("offline-data/full/room-import/canyons.json").read_text())}
    observations = H.observations_frame(histories, canyons)
    return base, histories, observations


def load_lagged(dataset_dir: Path) -> dict[int, pd.DataFrame]:
    """Real-time features as known 1 and 2 days earlier, indexed by observationId."""
    return {lag: pd.read_pickle(dataset_dir / f"realtime_lag{lag}.pkl").set_index("observationId") for lag in (1, 2)}


LAGGED_COLUMNS = F.REALTIME_FEATURES + F.INFO_FEATURES


def with_lag(frame: pd.DataFrame, lagged: dict[int, pd.DataFrame], lags: np.ndarray) -> pd.DataFrame:
    """Copy of ``frame`` whose real-time features are those known ``lags`` days earlier (0 = as is)."""
    output = frame.copy()
    ids = output["observationId"].to_numpy()
    for lag in (1, 2):
        mask = lags == lag
        if mask.any():
            output.loc[output.index[mask], LAGGED_COLUMNS] = lagged[lag].loc[ids[mask], LAGGED_COLUMNS].to_numpy()
    return output


def random_lags(frame: pd.DataFrame, seed: int) -> np.ndarray:
    return np.random.default_rng(seed).choice([1, 2], size=len(frame))


def with_history(base, histories, observations, cutoff: int | None):
    return H.add_history(base, histories, observations, cutoff)


def matrix(frame: pd.DataFrame) -> np.ndarray:
    values = frame[F.FEATURE_NAMES].to_numpy(dtype=np.float64)
    return np.where(np.isnan(values), F.MISSING, values).astype(np.float32)


def labels(frame: pd.DataFrame) -> np.ndarray:
    return frame["rank"].to_numpy(dtype=np.int64)


def predict(model: CatBoostClassifier, frame: pd.DataFrame) -> np.ndarray:
    probabilities = model.predict_proba(matrix(frame))
    order = [list(model.classes_).index(level) for level in F.LEVELS]
    return probabilities[:, order]


def baselines(frame: pd.DataFrame) -> dict[str, np.ndarray]:
    last = frame["histLastRank"].to_numpy()
    since = frame["histDaysSinceLast"].to_numpy()
    season = np.clip(np.rint(frame["histSeasonMeanRank"].to_numpy()), 0, 5).astype(np.int64)
    persistence = np.where(last >= 0, last, season).astype(np.int64)
    recent = np.where((last >= 0) & (since <= 14), last, season).astype(np.int64)
    return {"persistence": persistence, "recentPersistenceElseSeason": recent, "seasonalClimatology": season}


def segment(frame: pd.DataFrame) -> np.ndarray:
    since = frame["histDaysSinceLast"].to_numpy()
    return np.select(
        [since < 0, since <= 7, since <= 30, since <= 365],
        ["noHistory", "last0-7d", "last8-30d", "last31-365d"],
        default="last>365d",
    )


def report_block(y, probabilities, thresholds, frame) -> dict:
    pred = E.decide(probabilities, thresholds)
    block = {"model": {**E.level_metrics(y, pred), **E.probabilistic_metrics(y, probabilities)}}
    for name, levels in baselines(frame).items():
        block[name] = {**E.level_metrics(y, levels), **E.probabilistic_metrics(y, np.clip(E.onehot_probabilities(levels), 0.01, 1) / np.clip(E.onehot_probabilities(levels), 0.01, 1).sum(axis=1, keepdims=True))}
    segments = segment(frame)
    block["segments"] = {}
    for name in sorted(set(segments)):
        mask = segments == name
        persistence = baselines(frame[mask])["persistence"]
        block["segments"][name] = {
            "model": E.level_metrics(y[mask], pred[mask]),
            "persistence": E.level_metrics(y[mask], persistence),
        }
    months = np.array([F.from_epoch_day(d).month for d in frame["day"]])
    block["seasons"] = {}
    for name, members in (("winter", (12, 1, 2)), ("spring", (3, 4, 5)), ("summer", (6, 7, 8)), ("autumn", (9, 10, 11))):
        mask = np.isin(months, members)
        if mask.sum():
            block["seasons"][name] = E.level_metrics(y[mask], pred[mask])
    block["calibrationHigh"] = E.calibration_table(y, probabilities)
    block["confusion"] = pd.crosstab(pd.Series(y, name="true"), pd.Series(pred, name="pred")).to_dict()
    return block


def augment(train: pd.DataFrame, seed: int = 13) -> pd.DataFrame:
    """Hide yesterday's automatic gauges on a share of rows (the Météo-France hourly file is published
    at 05:44 UTC), then add cold-canyon copies so the model also serves canyons without reports nor
    climatology."""
    train = train.copy()
    hide = np.random.default_rng(seed + 40).random(len(train)) < AUTO_HIDDEN_FRACTION
    columns = [name for name in F.AUTO_RAIN_FEATURES if name != "mfAutoCount"]
    train.loc[train.index[hide], columns] = np.nan
    train.loc[train.index[hide], "mfAutoCount"] = 0.0
    sample = train.sample(frac=COLD_AUGMENT_FRACTION, random_state=seed)
    return pd.concat([train, H.cold_canyon(sample)], ignore_index=True)


def fit(train: pd.DataFrame, validation: pd.DataFrame | None, iterations: int | None = None) -> CatBoostClassifier:
    train = augment(train)
    params = dict(PARAMS)
    if iterations is not None:
        params["iterations"] = iterations
    model = CatBoostClassifier(**params)
    train_pool = Pool(matrix(train), [F.LEVELS[r] for r in labels(train)], feature_names=F.FEATURE_NAMES)
    if validation is not None:
        validation_pool = Pool(matrix(validation), [F.LEVELS[r] for r in labels(validation)], feature_names=F.FEATURE_NAMES)
        model.fit(train_pool, eval_set=validation_pool, early_stopping_rounds=150, use_best_model=True)
    else:
        model.fit(train_pool)
    return model


def oof_frame(probabilities: np.ndarray, test: pd.DataFrame, name: str) -> pd.DataFrame:
    frame = pd.DataFrame(probabilities, columns=[f"p{k}" for k in range(6)])
    frame["rank"] = labels(test)
    frame["fold"] = name
    frame["day"] = test["day"].to_numpy()
    frame["canyonId"] = test["canyonId"].to_numpy()
    frame["segment"] = segment(test)
    for baseline_name, levels in baselines(test).items():
        frame[baseline_name] = levels
    return frame


def walkforward(args) -> None:
    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    base, histories, observations = load(Path(args.dataset_dir))
    lagged = load_lagged(Path(args.dataset_dir))
    report = {"folds": {}, "params": {k: v for k, v in PARAMS.items() if k != "class_names"}}
    oof_today, oof_horizon = [], []
    for name, start, end in FOLDS:
        started = time.time()
        cutoff = day(start)
        data = with_history(base, histories, observations, cutoff)
        train_all = data[data["day"] < cutoff]
        test = data[(data["day"] >= cutoff) & (data["day"] < day(end))]
        validation_start = cutoff - VALIDATION_DAYS
        train = train_all[train_all["day"] < validation_start]
        validation = train_all[train_all["day"] >= validation_start]
        print(f"fold {name}: train={len(train)} validation={len(validation)} test={len(test)}", flush=True)

        # J0 model
        model = fit(train, validation)
        validation_probabilities = predict(model, validation)
        thresholds = E.tune_thresholds(labels(validation), validation_probabilities)
        risk = E.risk_thresholds(labels(validation), validation_probabilities)
        test_probabilities = predict(model, test)
        block = report_block(labels(test), test_probabilities, thresholds, test)
        cold_test = H.cold_canyon(test)
        cold_probabilities = predict(model, cold_test)
        cold_y = labels(cold_test)
        block["coldCanyon"] = {
            "model": {**E.level_metrics(cold_y, E.decide(cold_probabilities, thresholds)), **E.probabilistic_metrics(cold_y, cold_probabilities)},
            "massifSeasonalClimatology": E.level_metrics(cold_y, np.clip(np.rint(cold_test["histSeasonMeanRank"].to_numpy()), 0, 5).astype(np.int64)),
        }
        auto_missing = test.copy()
        auto_missing[[n for n in F.AUTO_RAIN_FEATURES if n != "mfAutoCount"]] = np.nan
        auto_missing["mfAutoCount"] = 0.0
        missing_probabilities = predict(model, auto_missing)
        block["autoMissing"] = {**E.level_metrics(labels(test), E.decide(missing_probabilities, thresholds)), **E.probabilistic_metrics(labels(test), missing_probabilities)}
        block["thresholds"] = thresholds.tolist()
        block["riskThresholds"] = risk
        block["bestIteration"] = int(model.get_best_iteration())
        importances = model.get_feature_importance()
        block["topFeatures"] = sorted(zip(F.FEATURE_NAMES, importances.tolist()), key=lambda item: -item[1])[:30]

        # J+1 / J+2 model: real-time information lagged by the horizon
        horizon_train = with_lag(train, lagged, random_lags(train, 41))
        horizon_validation = with_lag(validation, lagged, random_lags(validation, 43))
        horizon_model = fit(horizon_train, horizon_validation)
        horizon_validation_probabilities = predict(horizon_model, horizon_validation)
        horizon_thresholds = E.tune_thresholds(labels(horizon_validation), horizon_validation_probabilities)
        block["horizonModel"] = {
            "thresholds": horizon_thresholds.tolist(),
            "riskThresholds": E.risk_thresholds(labels(horizon_validation), horizon_validation_probabilities),
            "bestIteration": int(horizon_model.get_best_iteration()),
        }
        block["horizons"] = {}
        for horizon in (1, 2):
            horizon_test = with_lag(test, lagged, np.full(len(test), horizon))
            for model_name, current, current_thresholds in (("horizonModel", horizon_model, horizon_thresholds), ("todayModel", model, thresholds)):
                probabilities = predict(current, horizon_test)
                block["horizons"][f"J+{horizon}/{model_name}"] = {
                    **E.level_metrics(labels(test), E.decide(probabilities, current_thresholds)),
                    **E.probabilistic_metrics(labels(test), probabilities),
                }
                if horizon == 1 and model_name == "horizonModel":
                    oof_horizon.append(oof_frame(probabilities, test, name))
        block["seconds"] = round(time.time() - started)
        report["folds"][name] = block
        oof_today.append(oof_frame(test_probabilities, test, name))
        print(json.dumps({"fold": name, "model": block["model"], "persistence": block["persistence"], "horizons": block["horizons"]}, indent=1), flush=True)
        (output_dir / "report.json").write_text(json.dumps(report, indent=1, default=str))

    pooled = {}
    for model_name, frames, key in (("today", oof_today, "thresholds"), ("horizon", oof_horizon, None)):
        frame = pd.concat(frames, ignore_index=True)
        frame.to_pickle(output_dir / f"oof_{model_name}.pkl")
        probabilities = frame[[f"p{k}" for k in range(6)]].to_numpy()
        y = frame["rank"].to_numpy()
        fold_thresholds = [report["folds"][n][key] if key else report["folds"][n]["horizonModel"]["thresholds"] for n, _, _ in FOLDS]
        thresholds = np.mean(fold_thresholds, axis=0)
        pooled[model_name] = {
            "thresholds": thresholds.tolist(),
            "model": {**E.level_metrics(y, E.decide(probabilities, thresholds)), **E.probabilistic_metrics(y, probabilities)},
            "persistence": E.level_metrics(y, frame["persistence"].to_numpy()),
            "recentPersistenceElseSeason": E.level_metrics(y, frame["recentPersistenceElseSeason"].to_numpy()),
            "seasonalClimatology": E.level_metrics(y, frame["seasonalClimatology"].to_numpy()),
        }
    report["pooled"] = pooled["today"]
    report["pooledHorizonJ1"] = pooled["horizon"]
    (output_dir / "report.json").write_text(json.dumps(report, indent=1, default=str))
    print(json.dumps(pooled, indent=1))


def final(args) -> None:
    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    walk = json.loads((Path(args.walkforward_dir) / "report.json").read_text())
    base, histories, observations = load(Path(args.dataset_dir))
    lagged = load_lagged(Path(args.dataset_dir))
    data = with_history(base, histories, observations, None)
    summary = {"rowCount": int(len(data)), "firstDay": str(F.from_epoch_day(int(data["day"].min()))), "lastDay": str(F.from_epoch_day(int(data["day"].max())))}
    for model_name, oof_file, best_iterations, rows in (
        ("today", "oof_today.pkl", [b["bestIteration"] for b in walk["folds"].values()], data),
        ("horizon", "oof_horizon.pkl", [b["horizonModel"]["bestIteration"] for b in walk["folds"].values()], with_lag(data, lagged, random_lags(data, 47))),
    ):
        iterations = int(np.mean(best_iterations) * 1.15) + 1
        print(f"final {model_name} fit on {len(rows)} rows, {iterations} iterations", flush=True)
        model = fit(rows, None, iterations=iterations)
        model.save_model(str(output_dir / f"model_{model_name}.cbm"))
        # Decision thresholds from honest out-of-fold predictions (models that never saw those rows).
        oof = pd.read_pickle(Path(args.walkforward_dir) / oof_file)
        probabilities = oof[[f"p{k}" for k in range(6)]].to_numpy()
        y = oof["rank"].to_numpy()
        thresholds = E.tune_thresholds(y, probabilities)
        importances = model.get_feature_importance()
        summary[model_name] = {
            "iterations": iterations,
            "thresholds": thresholds.tolist(),
            "riskThresholds": E.risk_thresholds(y, probabilities),
            "oofWithFinalThresholds": E.level_metrics(y, E.decide(probabilities, thresholds)),
            "topFeatures": sorted(zip(F.FEATURE_NAMES, importances.tolist()), key=lambda item: -item[1])[:40],
        }
        rows[["observationId", "canyonId", "day", "rank"] + F.FEATURE_NAMES].head(2000).to_pickle(output_dir / f"sample_rows_{model_name}.pkl")
    (output_dir / "summary.json").write_text(json.dumps(summary, indent=1))
    print(json.dumps({k: (v if not isinstance(v, dict) else {kk: vv for kk, vv in v.items() if kk != "topFeatures"}) for k, v in summary.items()}, indent=1))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=["walkforward", "final"])
    parser.add_argument("--dataset-dir", default="build/debit-model/dataset")
    parser.add_argument("--walkforward-dir", default="build/debit-model/walkforward")
    parser.add_argument("--output-dir", required=True)
    args = parser.parse_args()
    if args.mode == "walkforward":
        walkforward(args)
    else:
        final(args)


if __name__ == "__main__":
    main()
