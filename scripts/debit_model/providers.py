"""Candidate feature sets for ``experiment.py`` (cached per candidate)."""
from __future__ import annotations

from pathlib import Path

import numpy as np
import pandas as pd

import features as F

CACHE = Path("build/debit-model/experiments/cache")
RAW_REPORTS = [
    Path("build/debit-pipeline/descente-refresh-2026-10-13/all_debit_observations.jsonl"),
    Path("build/debit-pipeline/descente-refresh-2026-06-05-reviewed/all_debit_observations.jsonl"),
]
GEO_POINTS = Path("offline-data/full/room-import/geo_points.json")
TARGETS = Path("build/debit-pipeline/weather-planning/weather_targets.jsonl")
# Names before the neighbour family joined the production contract.
BASE_NAMES = [name for name in F.FEATURE_NAMES if name not in F.REALTIME_FEATURES and name not in F.INFO_FEATURES]


def _cached(name: str, frame: pd.DataFrame, compute) -> pd.DataFrame:
    path = CACHE / f"{name}.pkl"
    if path.exists():
        cached = pd.read_pickle(path)
    else:
        CACHE.mkdir(parents=True, exist_ok=True)
        cached = compute()
        cached.to_pickle(path)
    keys = frame["observationId"]
    return cached.set_index("observationId").loc[keys].reset_index(drop=True).set_axis(frame.index)


def baseline(frame: pd.DataFrame, cutoff: int):
    return frame, list(BASE_NAMES)


def _neighbor_columns(frame: pd.DataFrame, lag: int = 0, radius: float = 30.0) -> pd.DataFrame:
    import neighbors as N

    def compute():
        base = pd.read_pickle("build/debit-model/dataset/base.pkl")[["observationId", "canyonId", "day"]]
        values = N.build_frame(base, RAW_REPORTS, GEO_POINTS, TARGETS, np.full(len(base), lag), radius)
        values["observationId"] = base["observationId"].to_numpy()
        return values
    name = "neighbors_v1" if (lag == 0 and radius == 30.0) else f"neighbors_lag{lag}_r{int(radius)}"
    return _cached(name, frame, compute)


def _neighbors_radius(radius: float):
    def build(frame: pd.DataFrame, cutoff: int):
        import neighbors as N
        columns = _neighbor_columns(frame, 0, radius)
        return pd.concat([frame, columns[N.NEIGHBOR_FEATURES]], axis=1), list(BASE_NAMES) + N.NEIGHBOR_FEATURES
    return build


def _with_lag_feature(frame: pd.DataFrame, lag: int) -> pd.DataFrame:
    import neighbors as N
    columns = _neighbor_columns(frame, lag)[N.NEIGHBOR_FEATURES].copy()
    columns["nbLagDays"] = float(lag)
    return columns


def neighbors_lag_augmented(frame: pd.DataFrame, cutoff: int):
    """Training rows get a random knowledge lag (0: 60 %, 1: 20 %, 2: 20 %) and the nbLagDays feature;
    test rows are evaluated at lag 0 (variants evaluate lags 1 and 2)."""
    import neighbors as N
    rng = np.random.default_rng(21)
    lags = rng.choice([0, 1, 2], size=len(frame), p=[0.6, 0.2, 0.2])
    lags = np.where(frame["day"].to_numpy() >= cutoff, 0, lags)
    parts = {lag: _with_lag_feature(frame, lag) for lag in (0, 1, 2)}
    columns = parts[0].copy()
    for lag in (1, 2):
        mask = lags == lag
        columns.loc[mask] = parts[lag].loc[mask]
    return pd.concat([frame, columns], axis=1), list(BASE_NAMES) + N.NEIGHBOR_FEATURES + ["nbLagDays"]


def neighbors_lag_variants(frame: pd.DataFrame):
    return {f"lag{lag}": pd.concat([frame, _with_lag_feature(frame, lag)], axis=1) for lag in (1, 2)}


def neighbors_plain_lag_variants(frame: pd.DataFrame):
    import neighbors as N
    return {f"lag{lag}": pd.concat([frame, _neighbor_columns(frame, lag)[N.NEIGHBOR_FEATURES]], axis=1) for lag in (1, 2)}


def neighbors(frame: pd.DataFrame, cutoff: int):
    import neighbors as N
    columns = _neighbor_columns(frame)
    extended = pd.concat([frame, columns[N.NEIGHBOR_FEATURES]], axis=1)
    return extended, list(BASE_NAMES) + N.NEIGHBOR_FEATURES


def _gauge_columns(frame: pd.DataFrame) -> pd.DataFrame:
    import gauges as G

    def compute():
        base = pd.read_pickle("build/debit-model/dataset/base.pkl")[["observationId", "canyonId", "day"]]
        values = G.build_frame(base, Path("build/debit-model/hubeau"))
        values["observationId"] = base["observationId"].to_numpy()
        return values
    return _cached("gauges_v1", frame, compute)


def gauges(frame: pd.DataFrame, cutoff: int):
    import gauges as G
    columns = _gauge_columns(frame)
    extended = pd.concat([frame, columns[G.GAUGE_FEATURES]], axis=1)
    return extended, list(BASE_NAMES) + G.GAUGE_FEATURES


def neighbors_gauges(frame: pd.DataFrame, cutoff: int):
    import gauges as G
    import neighbors as N
    extended = pd.concat([frame, _neighbor_columns(frame)[N.NEIGHBOR_FEATURES], _gauge_columns(frame)[G.GAUGE_FEATURES]], axis=1)
    return extended, list(BASE_NAMES) + N.NEIGHBOR_FEATURES + G.GAUGE_FEATURES


def _rain_columns(frame: pd.DataFrame) -> pd.DataFrame:
    import meteofrance as M
    import neighbors as N

    def compute():
        base = pd.read_pickle("build/debit-model/dataset/base.pkl")[["observationId", "canyonId", "day"]]
        points = N.canyon_points(GEO_POINTS, TARGETS)
        values = M.build_frame(base, Path("build/debit-model/meteofrance"), points, CACHE / "meteofrance_stations.pkl")
        values["observationId"] = base["observationId"].to_numpy()
        return values
    return _cached("rain_v1", frame, compute)


RAIN_V1 = ["mfRain1", "mfRain3", "mfRain7", "mfMax1", "mfMax3", "mfCount", "mfNearestKm", "mfClose1", "mfClose3"]


def rain(frame: pd.DataFrame, cutoff: int):
    import neighbors as N
    extended = pd.concat([frame, _neighbor_columns(frame)[N.NEIGHBOR_FEATURES], _rain_columns(frame)[RAIN_V1]], axis=1)
    return extended, list(BASE_NAMES) + N.NEIGHBOR_FEATURES + RAIN_V1


def all_sources(frame: pd.DataFrame, cutoff: int):
    import gauges as G
    import meteofrance as M
    import neighbors as N
    extended = pd.concat([
        frame,
        _neighbor_columns(frame)[N.NEIGHBOR_FEATURES],
        _gauge_columns(frame)[G.GAUGE_FEATURES],
        _rain_columns(frame)[RAIN_V1],
    ], axis=1)
    return extended, list(BASE_NAMES) + N.NEIGHBOR_FEATURES + G.GAUGE_FEATURES + RAIN_V1


def _rain_v2_columns(frame: pd.DataFrame) -> pd.DataFrame:
    import meteofrance as M
    import neighbors as N

    def compute():
        base = pd.read_pickle("build/debit-model/dataset/base.pkl")[["observationId", "canyonId", "day"]]
        points = N.canyon_points(GEO_POINTS, TARGETS)
        values = M.build_frame_v2(base, Path("build/debit-model/meteofrance"), points, CACHE / "meteofrance_stations.pkl")
        values["observationId"] = base["observationId"].to_numpy()
        return values
    return _cached("rain_v2", frame, compute)


def _neighbor_v2_columns(frame: pd.DataFrame) -> pd.DataFrame:
    import neighbors as N

    def compute():
        base = pd.read_pickle("build/debit-model/dataset/base.pkl")[["observationId", "canyonId", "day"]]
        values = N.build_frame_v2(base, RAW_REPORTS[:1], GEO_POINTS, TARGETS)
        values["observationId"] = base["observationId"].to_numpy()
        return values
    return _cached("neighbors_v2", frame, compute)


def rain_v2(frame: pd.DataFrame, cutoff: int):
    import meteofrance as M
    extended, names = rain(frame, cutoff)
    return pd.concat([extended, _rain_v2_columns(frame)[M.RAIN_V2_FEATURES]], axis=1), names + M.RAIN_V2_FEATURES


def nb_v2(frame: pd.DataFrame, cutoff: int):
    import neighbors as N
    extended, names = rain(frame, cutoff)
    return pd.concat([extended, _neighbor_v2_columns(frame)[N.NEIGHBOR_V2_FEATURES]], axis=1), names + N.NEIGHBOR_V2_FEATURES


def _snow_columns(frame: pd.DataFrame) -> pd.DataFrame:
    import neighbors as N
    import snow as S

    def compute():
        base = pd.read_pickle("build/debit-model/dataset/base.pkl")[["observationId", "canyonId", "day", "hydroBand3ElevationM", "hydroBand5ElevationM"]]
        points = N.canyon_points(GEO_POINTS, TARGETS)
        values = S.build_frame(base, Path("build/debit-model/meteofrance-other"), points, CACHE / "snow_stations.pkl")
        values["observationId"] = base["observationId"].to_numpy()
        return values
    return _cached("snow_v1", frame, compute)


def nb_rain_snow(frame: pd.DataFrame, cutoff: int):
    import snow as S
    extended, names = rain_v2(frame, cutoff)
    return pd.concat([extended, _snow_columns(frame)[S.SNOW_FEATURES]], axis=1), names + S.SNOW_FEATURES


def rain_v2_nb_v2(frame: pd.DataFrame, cutoff: int):
    import neighbors as N
    extended, names = rain_v2(frame, cutoff)
    return pd.concat([extended, _neighbor_v2_columns(frame)[N.NEIGHBOR_V2_FEATURES]], axis=1), names + N.NEIGHBOR_V2_FEATURES


def _rain_ch_columns(frame: pd.DataFrame) -> pd.DataFrame:
    import meteoswiss as W
    import neighbors as N

    def compute():
        base = pd.read_pickle("build/debit-model/dataset/base.pkl")[["observationId", "canyonId", "day"]]
        points = N.canyon_points(GEO_POINTS, TARGETS)
        values = W.build_frame(base, CACHE / "meteofrance_stations.pkl", Path("build/debit-model/meteoswiss"), points, CACHE / "rain_stations_fr_ch.pkl")
        values["observationId"] = base["observationId"].to_numpy()
        return values
    return _cached("rain_fr_ch", frame, compute)


RAIN_ALL = RAIN_V1 + ["mfRain14", "mfRain30", "mfWetDays7"]


def rain_fr_ch(frame: pd.DataFrame, cutoff: int):
    """Neighbours + rain gauges of both networks (FR + CH), v1 and long windows."""
    import neighbors as N
    extended = pd.concat([frame, _neighbor_columns(frame)[N.NEIGHBOR_FEATURES], _rain_ch_columns(frame)[RAIN_ALL]], axis=1)
    return extended, list(BASE_NAMES) + N.NEIGHBOR_FEATURES + RAIN_ALL


def _rain_ch_lagged(frame: pd.DataFrame, lag: int) -> pd.DataFrame:
    """Gauge features computed as of J - lag (latest gauges known at horizon ``lag``)."""
    import meteoswiss as W
    import neighbors as N

    def compute():
        base = pd.read_pickle("build/debit-model/dataset/base.pkl")[["observationId", "canyonId", "day"]].copy()
        base["day"] = base["day"] - lag
        points = N.canyon_points(GEO_POINTS, TARGETS)
        values = W.build_frame(base, CACHE / "meteofrance_stations.pkl", Path("build/debit-model/meteoswiss"), points, CACHE / "rain_stations_fr_ch.pkl")
        values["observationId"] = base["observationId"].to_numpy()
        return values
    return _cached(f"rain_fr_ch_lag{lag}", frame, compute)


def rain_fr_ch_lagged_variants(frame: pd.DataFrame):
    import neighbors as N
    return {
        f"h{lag}lag": pd.concat([frame, _neighbor_columns(frame, lag)[N.NEIGHBOR_FEATURES], _rain_ch_lagged(frame, lag)[RAIN_ALL]], axis=1)
        for lag in (1, 2)
    }


HORIZON_AUGMENT_FRACTION = 0.3


def rain_fr_ch_horizon_augmented(frame: pd.DataFrame, cutoff: int):
    """Training rows duplicated (30 %) with the information known at horizon 1 or 2."""
    import neighbors as N
    extended, names = rain_fr_ch(frame, cutoff)
    extended = extended.copy()
    extended["infoLagDays"] = 0.0
    train_mask = extended["day"].to_numpy() < cutoff - 365
    rng = np.random.default_rng(31)
    picked = np.where(train_mask & (rng.random(len(extended)) < HORIZON_AUGMENT_FRACTION))[0]
    lags = rng.choice([1, 2], size=len(picked))
    copies = []
    for lag in (1, 2):
        rows = picked[lags == lag]
        part = extended.iloc[rows].copy()
        neighbor = _neighbor_columns(frame, lag)[N.NEIGHBOR_FEATURES].iloc[rows]
        rain_values = _rain_ch_lagged(frame, lag)[RAIN_ALL].iloc[rows]
        part[N.NEIGHBOR_FEATURES] = neighbor.to_numpy()
        part[RAIN_ALL] = rain_values.to_numpy()
        part["infoLagDays"] = float(lag)
        copies.append(part)
    extended = pd.concat([extended] + copies, ignore_index=True)
    return extended, names + ["infoLagDays"]


def rain_fr_ch_horizon_augmented_variants(frame: pd.DataFrame):
    output = {}
    for name, variant in rain_fr_ch_lagged_variants(frame).items():
        variant = variant.copy()
        variant["infoLagDays"] = float(name[1])
        output[name] = variant
    return output


def horizon_model(frame: pd.DataFrame, cutoff: int):
    """Dedicated J+1/J+2 model: every row carries the information known 1 or 2 days earlier."""
    import neighbors as N
    extended, names = rain_fr_ch(frame, cutoff)
    extended = extended.copy()
    rng = np.random.default_rng(37)
    lags = rng.choice([1, 2], size=len(extended))
    for lag in (1, 2):
        mask = lags == lag
        extended.loc[mask, N.NEIGHBOR_FEATURES] = _neighbor_columns(frame, lag)[N.NEIGHBOR_FEATURES].loc[mask].to_numpy()
        extended.loc[mask, RAIN_ALL] = _rain_ch_lagged(frame, lag)[RAIN_ALL].loc[mask].to_numpy()
    extended["infoLagDays"] = lags.astype(float)
    return extended, names + ["infoLagDays"]


def rain_fr_ch_horizon_variants(frame: pd.DataFrame):
    """What the app knows at horizon h (J = today + h): gauges up to today - 1 (so RR(J-1) is
    unknown for h >= 1: the gauge features are missing) and neighbour reports up to today - 1."""
    import neighbors as N
    output = {}
    for horizon in (1, 2):
        rain_columns = _rain_ch_columns(frame)[RAIN_ALL].copy()
        rain_columns[:] = np.nan
        rain_columns["mfCount"] = 0.0
        output[f"h{horizon}"] = pd.concat([frame, _neighbor_columns(frame, horizon)[N.NEIGHBOR_FEATURES], rain_columns], axis=1)
    return output


def rain_realistic(frame: pd.DataFrame, cutoff: int):
    """J0 as the app really sees it: neighbours up to J-1, rain gauges up to J-2 (gauge features as
    of J-1: the Météo-France file of the morning of J stops at J-2)."""
    import neighbors as N
    extended = pd.concat([frame, _neighbor_columns(frame)[N.NEIGHBOR_FEATURES], _rain_ch_lagged(frame, 1)[RAIN_ALL]], axis=1)
    return extended, list(BASE_NAMES) + N.NEIGHBOR_FEATURES + RAIN_ALL


def _auto_columns(frame: pd.DataFrame, lag: int = 0) -> pd.DataFrame:
    import json as _json
    import meteofrance as M

    def compute():
        base = pd.read_pickle("build/debit-model/dataset/base.pkl")[["observationId", "canyonId", "day"]]
        payload = pd.read_pickle(CACHE / "rain_stations_fr_ch.pkl")
        hourly = {c for codes in _json.loads(Path("build/debit-model/meteofrance-cache/hourly_stations.json").read_text()).values() for c in codes}
        rows = [M.auto_features(int(c), int(d) - lag, payload["series"], payload["nearby"], hourly) for c, d in zip(base["canyonId"], base["day"])]
        values = pd.DataFrame(rows).astype(float)
        values["observationId"] = base["observationId"].to_numpy()
        return values
    return _cached(f"rain_auto_lag{lag}", frame, compute)


AUTO = ["mfAutoRain1", "mfAutoMax1", "mfAutoCount", "mfAutoNearestKm"]


def rain_realistic_auto(frame: pd.DataFrame, cutoff: int):
    """J0 with the hourly file: all gauges up to J-2 + automatic gauges' rain of J-1."""
    extended, names = rain_realistic(frame, cutoff)
    return pd.concat([extended, _auto_columns(frame)[AUTO]], axis=1), names + AUTO


def rain_realistic_auto_aug(frame: pd.DataFrame, cutoff: int):
    """Same, with the J-1 automatic rain hidden on 20 % of the training rows (early-morning use,
    before the 05:44 UTC hourly file)."""
    extended, names = rain_realistic_auto(frame, cutoff)
    extended = extended.copy()
    rng = np.random.default_rng(53)
    hide = (rng.random(len(extended)) < 0.2) & (extended["day"].to_numpy() < cutoff - 365)
    extended.loc[hide, ["mfAutoRain1", "mfAutoMax1", "mfAutoNearestKm"]] = np.nan
    extended.loc[hide, "mfAutoCount"] = 0.0
    return extended, names


def auto_missing_variant(frame: pd.DataFrame):
    extended, _ = rain_realistic_auto(frame, 0)
    extended = extended.copy()
    extended[["mfAutoRain1", "mfAutoMax1", "mfAutoNearestKm"]] = np.nan
    extended["mfAutoCount"] = 0.0
    return {"autoMissing": extended}


def recency_weights(train: pd.DataFrame) -> np.ndarray:
    """Older reports count less (reporting habits and canyon conditions drift): half weight 12 years back."""
    age_years = (train["day"].max() - train["day"].to_numpy()) / 365.25
    return np.power(0.5, age_years / 12.0)


CANDIDATES = {
    "baseline": {"build": baseline},
    "neighbors": {"build": neighbors, "variants": neighbors_plain_lag_variants},
    "neighbors_r15": {"build": _neighbors_radius(15.0)},
    "neighbors_r50": {"build": _neighbors_radius(50.0)},
    "neighbors_lagaug": {"build": neighbors_lag_augmented, "variants": neighbors_lag_variants},
    "gauges": {"build": gauges},
    "nb_depth8": {"build": neighbors, "params": {"depth": 8, "l2_leaf_reg": 3.0}},
    "nb_depth6": {"build": neighbors, "params": {"depth": 6}},
    "nb_nocold": {"build": neighbors, "cold": False},
    "nb_recency": {"build": neighbors, "weights": recency_weights},
    "nb_ens3": {"build": neighbors, "seeds": 3},
    "neighbors_gauges": {"build": neighbors_gauges},
    "nb_rain": {"build": rain},
    "rain_v2": {"build": rain_v2},
    "nb_v2": {"build": nb_v2},
    "nb_rain_snow": {"build": nb_rain_snow},
    "rain_v2_nb_v2": {"build": rain_v2_nb_v2},
    "rain_fr_ch": {"build": rain_fr_ch},
    "rain_realistic": {"build": rain_realistic},
    "rain_realistic_auto": {"build": rain_realistic_auto, "variants": auto_missing_variant},
    "rain_realistic_auto_aug": {"build": rain_realistic_auto_aug, "variants": auto_missing_variant},
    "rain_fr_ch_horizons": {"build": rain_fr_ch, "variants": rain_fr_ch_horizon_variants},
    "rain_fr_ch_lagged": {"build": rain_fr_ch, "variants": rain_fr_ch_lagged_variants},
    "rain_fr_ch_haug": {"build": rain_fr_ch_horizon_augmented, "variants": rain_fr_ch_horizon_augmented_variants},
    "horizon_model": {"build": horizon_model, "variants": rain_fr_ch_horizon_augmented_variants},
    "rain_fr_ch_rsm05": {"build": rain_fr_ch, "params": {"rsm": 0.5}},
    "rain_fr_ch_lr08": {"build": rain_fr_ch, "params": {"learning_rate": 0.08, "iterations": 3000}},
    "rain_fr_ch_l2_10": {"build": rain_fr_ch, "params": {"l2_leaf_reg": 10.0}},
    "all_sources": {"build": all_sources},
}
