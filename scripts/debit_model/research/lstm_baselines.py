"""CatBoost references of the LSTM experiments, with the ``experiment.py`` settings (FAST_PARAMS).

Feature sets:
* ``cb_static``: time + static descriptors;
* ``cb_hydro``: + hand-made hydro features (``F.WEATHER_FEATURES``);
* ``cb_hydropure``: same without the five "since the last report" ones, the LSTM ``weather`` rival
  (the LSTM does not know when the canyon was last reported);
* ``cb_full``: every production feature (cold-canyon copies, as production);
* ``cb_fullnohydro``: production without the hand-made hydro features;
* ``cb_full+<tag>``: production + the LSTM outputs of run ``<tag>`` (out-of-fold, ``lstm_oof.py``);
* ``cb_fullnohydro+<tag>``: same without the hand-made hydro features.

With ``--params prod`` the runs use the production settings of ``train.py`` (learning rate 0.08,
up to 3000 iterations) and their names end with ``P`` (``cb_fullP``). With ``--horizon`` they are
J+1/J+2 models (``D.horizon``, tested at J+1) and their names end with ``J1``.

Writes ``build/debit-model/lstm/runs/<name>_<fold>_s<seed>.npz/.json`` like ``lstm_train.py``.

    PYTHONPATH=scripts/debit_model:scripts/debit_model/research .venv/bin/python \
        scripts/debit_model/research/lstm_baselines.py --fold 2024 --sets cb_static,cb_hydro,cb_full
"""
from __future__ import annotations

import argparse
import json
import time

import numpy as np
import pandas as pd
from catboost import CatBoostClassifier, Pool

import evaluation as E
import experiment as X
import features as F
import history as H
import lstm_data as D
import train as T

RUNS = D.CACHE / "runs"
HYDRO = list(F.WEATHER_FEATURES)
# Hand-made hydro features that also use the date of the canyon's last report (history).
SINCE_LAST = ["hDeltaLogQ3", "hDeltaLogQ10", "hDeltaLogQ30", "hLiquidSinceLast", "hSweChangeSinceLast"]
SETS = {
    "cb_static": (F.TIME_FEATURES + F.STATIC_FEATURES, False),
    "cb_hydro": (F.TIME_FEATURES + F.STATIC_FEATURES + HYDRO, False),
    "cb_hydropure": (F.TIME_FEATURES + F.STATIC_FEATURES + [n for n in HYDRO if n not in SINCE_LAST], False),
    "cb_full": (list(F.FEATURE_NAMES), True),
    "cb_fullnohydro": ([n for n in F.FEATURE_NAMES if n not in HYDRO], True),
}


def lstm_columns(frame: pd.DataFrame, tag: str, fold: str, kind: str) -> tuple[pd.DataFrame, list[str]]:
    """LSTM outputs of ``<tag>`` (out-of-fold on train/validation rows) joined on observationId."""
    with np.load(RUNS / f"oof_{tag}_{fold}.npz", allow_pickle=True) as npz:
        obs = npz["obs"]
        values = npz["p"] if kind == "p" else npz["state"]
    prefix = f"lstm{kind.upper()}"
    if kind == "p":
        cumulative = np.cumsum(values[:, ::-1], axis=1)[:, ::-1][:, 1:]  # P(rank >= k), k = 1..5
        expected = (values * np.arange(6)).sum(axis=1, keepdims=True)
        values = np.concatenate([cumulative, expected], axis=1)
    names = [f"{prefix}{i}" for i in range(values.shape[1])]
    table = pd.DataFrame(values, columns=names)
    table["observationId"] = obs
    table = table.drop_duplicates("observationId").set_index("observationId")
    joined = table.reindex(frame["observationId"].to_numpy())
    return pd.concat([frame.reset_index(drop=True), joined.reset_index(drop=True)], axis=1), names


def run(name: str, fold: str, seed: int, feature_names: list[str], cold: bool, frame: pd.DataFrame, threads: int = 8, prod: bool = False, horizon: bool = False) -> dict:
    train, validation, test = D.split(frame, fold)
    if horizon:
        train, validation, test = D.horizon(train, validation, test, D.day(D.FOLDS[fold][0]))
    if cold:
        sample = train.sample(frac=T.COLD_AUGMENT_FRACTION, random_state=13)
        train = pd.concat([train, H.cold_canyon(sample)], ignore_index=True)

    def matrix(part):
        values = part[feature_names].to_numpy(dtype=np.float64)
        return np.where(np.isnan(values), F.MISSING, values).astype(np.float32)

    started = time.time()
    params = dict(T.PARAMS if prod else X.FAST_PARAMS, random_seed=7 + seed, verbose=0, thread_count=threads, train_dir=str(D.CACHE / "catboost_info"))
    model = CatBoostClassifier(**params)
    model.fit(
        Pool(matrix(train), [F.LEVELS[r] for r in train["rank"]], feature_names=feature_names),
        eval_set=Pool(matrix(validation), [F.LEVELS[r] for r in validation["rank"]], feature_names=feature_names),
        early_stopping_rounds=100, use_best_model=True,
    )
    order = [list(model.classes_).index(level) for level in F.LEVELS]
    val_p = model.predict_proba(matrix(validation))[:, order]
    test_p = model.predict_proba(matrix(test))[:, order]
    thresholds = E.tune_thresholds(validation["rank"].to_numpy(), val_p)
    y = test["rank"].to_numpy()
    metrics = {**E.level_metrics(y, E.decide(test_p, thresholds)), **E.probabilistic_metrics(y, test_p)}
    RUNS.mkdir(parents=True, exist_ok=True)
    stem = RUNS / f"{name}_{fold}_s{seed}"
    np.savez_compressed(stem.with_suffix(".npz"), val_p=val_p, test_p=test_p, val_obs=validation["observationId"].to_numpy(), test_obs=test["observationId"].to_numpy(), thresholds=thresholds)
    importances = sorted(zip(feature_names, model.get_feature_importance().tolist()), key=lambda item: -item[1])[:25]
    report = {"name": name, "fold": fold, "seed": seed, "test": metrics, "valRps": E.ranked_probability_score(validation["rank"].to_numpy(), val_p),
              "bestIteration": int(model.get_best_iteration()), "seconds": round(time.time() - started), "topFeatures": importances}
    stem.with_suffix(".json").write_text(json.dumps(report, indent=1))
    print(json.dumps({"name": name, "fold": fold, "seed": seed, "valRps": round(report["valRps"], 5), "testRps": round(metrics["rps"], 5), "accuracy": round(metrics["accuracy"], 4), "highF1": round(metrics["highF1"], 4), "seconds": report["seconds"]}), flush=True)
    return report


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--fold", required=True, choices=list(D.FOLDS))
    parser.add_argument("--sets", required=True)
    parser.add_argument("--seeds", default="0")
    parser.add_argument("--lstm-kind", default="p", choices=["p", "state"])
    parser.add_argument("--threads", type=int, default=8)
    parser.add_argument("--params", default="fast", choices=["fast", "prod"])
    parser.add_argument("--horizon", action="store_true", help="J+1/J+2 model (D.horizon), tested at J+1")
    parser.add_argument("--wait", action="store_true", help="wait until the out-of-fold files of the LSTM tags exist")
    args = parser.parse_args()
    if args.wait:
        for tag in {name.partition("+")[2] for name in args.sets.split(",")} - {""}:
            while not (RUNS / f"oof_{tag}_{args.fold}.npz").exists():
                time.sleep(60)
    base_frame = D.fold(D.day(D.FOLDS[args.fold][0]))
    weather = D.weather(base_frame["canyonId"].unique())
    base_frame = base_frame[base_frame["canyonId"].isin(weather["targetOf"].keys())]
    for name in args.sets.split(","):
        stem, _, tag = name.partition("+")
        feature_names, cold = SETS[stem]
        frame = base_frame
        if tag:
            frame, extra = lstm_columns(base_frame, tag, args.fold, args.lstm_kind)
            feature_names = feature_names + extra  # cold-canyon copies keep them (weather-driven)
        for seed in (int(s) for s in args.seeds.split(",")):
            label = (name if args.lstm_kind == "p" else f"{name}~state") + ("P" if args.params == "prod" else "") + ("J1" if args.horizon else "")
            run(label, args.fold, seed, feature_names, cold, frame, args.threads, args.params == "prod", args.horizon)


if __name__ == "__main__":
    main()
