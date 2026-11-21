"""Data for the LSTM hydrology experiments (see ``lstm_README.md``).

* ``weather()``: one dense daily series per weather target on a common day axis, with the inputs
  of an LSTM (log rain, temperature, valid flag), cached in ``build/debit-model/lstm/weather.npz``.
* ``fold(cutoff)``: the v6 dataset rows with the history features of a walk-forward fold
  (``history.add_history``, as ``train.py``), cached per cutoff.

Run from the repository root with ``PYTHONPATH=scripts/debit_model``.
"""
from __future__ import annotations

import json
from datetime import date
from pathlib import Path

import numpy as np
import pandas as pd

import features as F
import history as H
import train as T
from weather_store import WeatherStore

CACHE = Path("build/debit-model/lstm")
DATASET = Path("build/debit-model/dataset")
WEATHER = Path("build/debit-model/weather/era5_daily-2026-10-29.npz")
TARGETS = Path("build/debit-pipeline/weather-planning-2026-10-29/weather_targets.jsonl")
# Test periods of experiment.py (A/B harness) plus the first walk-forward fold of train.py.
FOLDS = {
    "2023": ("2023-01-01", "2024-01-01"),
    "2024": ("2024-01-01", "2025-01-01"),
    "2025-2026": ("2025-01-01", "2026-11-01"),
}
DYNAMIC = ["logRain", "temp", "valid"]
# Daily outputs of the app's hand-made hydro model (hydro.step), per canyon: hybrid LSTM inputs.
HYDRO_CHANNELS = ["logLiquid", "logSwe", "soilFill", "logQ1", "logQ10", "logQ90"]


def day(iso: str) -> int:
    return F.to_epoch_day(date.fromisoformat(iso))


def canyon_targets() -> dict[int, str]:
    mapping: dict[int, str] = {}
    for line in TARGETS.read_text().splitlines():
        if line.strip():
            target = json.loads(line)
            for canyon_id in target["canyonIds"]:
                mapping[int(canyon_id)] = target["targetId"]
    return mapping


def base() -> pd.DataFrame:
    return pd.read_pickle(DATASET / "base.pkl")


def weather(canyon_ids) -> dict:
    """Dense (targets, days, 3) array; ``targetOf`` maps canyon id -> row."""
    path = CACHE / "weather.npz"
    if path.exists():
        with np.load(path) as npz:
            data = {key: npz[key] for key in npz.files}
        return {
            "series": data["series"],
            "day0": int(data["day0"]),
            "targetOf": dict(zip(data["canyonIds"].tolist(), data["targetRows"].tolist())),
            "targetIds": data["targetIds"],
        }
    CACHE.mkdir(parents=True, exist_ok=True)
    mapping = canyon_targets()
    store = WeatherStore.load(WEATHER)
    canyons = sorted(c for c in set(int(c) for c in canyon_ids) if c in mapping and store.get(mapping[c]) is not None)
    target_ids = sorted({mapping[c] for c in canyons})
    series = [store.get(t) for t in target_ids]
    day0 = min(s.start_day for s in series)
    days = max(s.end_day for s in series) - day0 + 1
    output = np.zeros((len(target_ids), days, len(DYNAMIC)), dtype=np.float32)
    for row, s in enumerate(series):
        offset = s.start_day - day0
        length = len(s.precipitation)
        precipitation = np.full((1, days), np.nan)
        temperature = np.full((1, days), np.nan)
        precipitation[0, offset:offset + length] = s.precipitation
        temperature[0, offset:offset + length] = s.temperature
        valid = ~np.isnan(precipitation[0]) & ~np.isnan(temperature[0])
        from hydro import fill_window
        p, t, _ = fill_window(precipitation, temperature)
        output[row, :, 0] = np.log1p(np.maximum(p[0], 0.0))
        output[row, :, 1] = t[0]
        output[row, :, 2] = valid
    row_of_target = {t: i for i, t in enumerate(target_ids)}
    target_rows = [row_of_target[mapping[c]] for c in canyons]
    np.savez(path, series=output, day0=day0, canyonIds=np.array(canyons), targetRows=np.array(target_rows), targetIds=np.array(target_ids))
    return {"series": output, "day0": day0, "targetOf": dict(zip(canyons, target_rows)), "targetIds": np.array(target_ids)}


def hydro_channels(weather_data: dict) -> dict:
    """(canyons, days, 6) daily states of ``hydro.step`` run over the whole record of each canyon.

    The app simulates a 400-day window instead of the whole record: the slow reservoirs differ a
    little at the start of that window, which does not matter for a research input.
    """
    import hydro

    path = CACHE / "hydro_channels.npz"
    if path.exists():
        with np.load(path) as npz:
            return {"series": npz["series"], "rowOf": dict(zip(npz["canyonIds"].tolist(), range(len(npz["canyonIds"]))))}
    static = json.loads((DATASET / "canyon_static.json").read_text())
    canyons = sorted(weather_data["targetOf"])
    rows = np.array([weather_data["targetOf"][c] for c in canyons])
    series = weather_data["series"]
    days = series.shape[1]

    def value(canyon: int, key: str, default: float) -> float:
        number = static.get(str(canyon), {}).get(key)
        return float(number) if number is not None else default

    latitude = np.array([value(c, "weatherLatitude", 45.0) for c in canyons])
    reference = np.array([value(c, "weatherElevationM", 1000.0) for c in canyons])
    bands = np.array([[value(c, f"hydroBand{i}ElevationM", value(c, "weatherElevationM", 1000.0)) for i in range(1, 6)] for c in canyons])
    soil = np.array([value(c, "hydroSoilCapacityMm", hydro.DEFAULT_SOIL_CAPACITY_MM) for c in canyons])
    params = hydro.HydroParameters.build(latitude, reference, bands, soil)
    state = hydro.HydroState.initial(params)
    precipitation = np.expm1(series[rows, :, 0].astype(np.float64))
    temperature = series[rows, :, 1].astype(np.float64)
    first = date(1970, 1, 1).toordinal() + weather_data["day0"]
    output = np.zeros((len(canyons), days, len(HYDRO_CHANNELS)), dtype=np.float32)
    for index in range(days):
        day_of_year = np.full(len(canyons), date.fromordinal(first + index).timetuple().tm_yday, dtype=np.float64)
        liquid, _ = hydro.step(state, params, precipitation[:, index], temperature[:, index], day_of_year)
        output[:, index, 0] = np.log1p(liquid)
        output[:, index, 1] = np.log1p(state.swe.mean(axis=1))
        output[:, index, 2] = state.soil / params.soil_capacity
        output[:, index, 3] = np.log(state.q[:, 0] + 0.01)
        output[:, index, 4] = np.log(state.q[:, 2] + 0.01)
        output[:, index, 5] = np.log(state.q[:, 4] + 0.01)
    np.savez(path, series=output, canyonIds=np.array(canyons))
    return {"series": output, "rowOf": {c: i for i, c in enumerate(canyons)}}


def fold(cutoff: int) -> pd.DataFrame:
    """All rows with the history features known at ``cutoff`` (same frame as train.walkforward)."""
    path = CACHE / f"history_{cutoff}.pkl"
    if path.exists():
        return pd.read_pickle(path)
    CACHE.mkdir(parents=True, exist_ok=True)
    rows, histories, observations = T.load(DATASET)
    frame = H.add_history(rows, histories, observations, cutoff)
    frame.to_pickle(path)
    return frame


def horizon(train: pd.DataFrame, validation: pd.DataFrame, test: pd.DataFrame, cutoff: int) -> tuple[pd.DataFrame, pd.DataFrame, pd.DataFrame]:
    """J+1/J+2 rows as ``train.py``: real-time and history features lagged by 1 or 2 days at random
    on the training and validation rows, by 1 day on the test rows (evaluated at J+1)."""
    lagged = T.load_lagged(DATASET)
    _, histories, observations = T.load(DATASET)
    return (
        T.with_lag(train, lagged, T.random_lags(train, 41), histories, observations, cutoff),
        T.with_lag(validation, lagged, T.random_lags(validation, 43), histories, observations, cutoff),
        T.with_lag(test, lagged, np.ones(len(test), dtype=np.int64), histories, observations, cutoff),
    )


def split(frame: pd.DataFrame, name: str) -> tuple[pd.DataFrame, pd.DataFrame, pd.DataFrame]:
    """Train / validation (last training year) / test rows, in frame order (as train.py)."""
    start, end = FOLDS[name]
    cutoff = day(start)
    train_all = frame[frame["day"] < cutoff]
    validation_start = cutoff - T.VALIDATION_DAYS
    return (
        train_all[train_all["day"] < validation_start],
        train_all[train_all["day"] >= validation_start],
        frame[(frame["day"] >= cutoff) & (frame["day"] < day(end))],
    )


if __name__ == "__main__":
    import time

    started = time.time()
    rows = base()
    data = weather(rows["canyonId"].unique())
    print("weather", data["series"].shape, "day0", data["day0"], f"{time.time() - started:.0f}s", flush=True)
    channels = hydro_channels(data)
    print("hydro channels", channels["series"].shape, f"{time.time() - started:.0f}s", flush=True)
    for name, (start, _) in FOLDS.items():
        frame = fold(day(start))
        train, validation, test = split(frame, name)
        print(name, len(train), len(validation), len(test), f"{time.time() - started:.0f}s", flush=True)
