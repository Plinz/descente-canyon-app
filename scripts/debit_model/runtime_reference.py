"""Single-prediction reference implementation of the app feature path.

Mirrors ``DebitFeatureBuilder.computeFeatureValues`` exactly (same inputs, same fallbacks), so its
output can be used as golden vectors for the Kotlin test and to replay app predictions offline.
"""
from __future__ import annotations

import math
from datetime import date, timedelta

import numpy as np

import features as F
import hydro
import meteofrance as M
import neighbors as N

BAND_KEYS = [f"hydroBand{i}ElevationM" for i in range(1, 6)]


def aggregate_from_lookup(values: dict[str, float], prefix: str) -> F.MonthlyAggregate:
    return F.MonthlyAggregate.from_lookup({k[len(prefix) + 1:]: v for k, v in values.items() if k.startswith(prefix + ".")})


def compute_features(
    target: date,
    weather: dict[date, tuple[float | None, float | None]],
    grid_latitude: float | None,
    grid_longitude: float | None,
    grid_elevation: float | None,
    static_values: dict[str, float],
    canyon: dict,
    history: list[tuple[int, int]],
    lookup_values: dict[str, float],
    canyon_id: int = 0,
    neighbor_list: list[tuple[int, float]] | None = None,
    recent_cells: list[tuple[int, int, int]] | None = None,
    cell_aggregates: dict[int, tuple[list[float], list[float]]] | None = None,
    rain_stations: list[tuple[str, float]] | None = None,
    rain_values: dict[str, dict[int, float]] | None = None,
    horizon: int = 0,
    automatic_rain: dict[str, float] | None = None,
) -> dict[str, float | None] | None:
    static = {k: v for k, v in static_values.items() if v is not None}
    for key, canyon_key in (("altitudeDepartM", "altitudeDepart"), ("deniveleM", "denivele"), ("longueurM", "longueur"), ("cascadeMaxM", "cascadeMax")):
        if key not in static and canyon.get(canyon_key) is not None:
            static[key] = float(canyon[canyon_key])
    if grid_latitude is not None:
        static["weatherLatitude"] = grid_latitude
    if grid_longitude is not None:
        static["weatherLongitude"] = grid_longitude
    if grid_elevation is not None:
        static["weatherElevationM"] = grid_elevation
    if any(static.get(key) is None for key in BAND_KEYS):
        base = static.get("altitudeDepartM", static.get("weatherElevationM", 1000.0))
        for key, offset in zip(BAND_KEYS, hydro.FALLBACK_BAND_OFFSETS_M):
            static[key] = round(base + offset, 1)
    static.setdefault("hydroSoilCapacityMm", hydro.DEFAULT_SOIL_CAPACITY_MM)

    window = hydro.WINDOW_DAYS
    first = target - timedelta(days=window - 1)
    precipitation = np.full((1, window), np.nan)
    temperature = np.full((1, window), np.nan)
    for index in range(window):
        p, t = weather.get(first + timedelta(days=index), (None, None))
        if p is not None:
            precipitation[0, index] = p
        if t is not None:
            temperature[0, index] = t
    p, t, valid = hydro.fill_window(precipitation, temperature)
    if valid[0] < hydro.MIN_VALID_DAYS or np.isnan(precipitation[0, -1]):
        return None
    latitude = grid_latitude if grid_latitude is not None else static.get("weatherLatitude")
    reference = grid_elevation if grid_elevation is not None else static.get("weatherElevationM", static.get("altitudeDepartM"))
    if latitude is None or reference is None:
        return None

    target_day = F.to_epoch_day(target)
    past = [(d, r) for d, r in history if d < target_day]
    last_day = max((d for d, _ in past), default=None)
    since = np.array([float(target_day - last_day) if last_day is not None else math.nan])
    log_mean = np.array([[static.get(f"hydroLog{n}Mean", math.nan) for n in F.RESERVOIR_NAMES]])
    log_std = np.array([[static.get(f"hydroLog{n}Std", math.nan) for n in F.RESERVOIR_NAMES]])
    hydro_values = F.hydro_features(
        p, t, np.array([target_day]), np.array([latitude]), np.array([reference]),
        np.array([[static[key] for key in BAND_KEYS]]), np.array([static["hydroSoilCapacityMm"]]),
        since, log_mean, log_std,
    )
    priors = F.area_priors(
        target.month,
        aggregate_from_lookup(lookup_values, "s"),
        aggregate_from_lookup(lookup_values, "r"),
        aggregate_from_lookup(lookup_values, "g"),
    )
    values: dict[str, float | None] = {}
    values.update(F.time_features(target))
    values.update(static)
    for name, array in hydro_values.items():
        number = float(array[0])
        values[name] = None if math.isnan(number) else number
    values.update(F.history_features(past, target_day, target.month, priors))
    cells: dict[int, list[tuple[int, int]]] = {}
    for cell_canyon, cell_day, cell_rank in recent_cells or []:
        cells.setdefault(cell_canyon, []).append((cell_day, cell_rank))
    for entries in cells.values():
        entries.sort()
    values.update(N.features_for(
        canyon_id, target_day, {canyon_id: neighbor_list or []}, cells, N.FrozenSeasonalMeans(cell_aggregates or {}),
        lag_days=horizon,
    ))
    series = {}
    for code, by_day in (rain_values or {}).items():
        if by_day:
            start = min(by_day)
            array = np.full(max(by_day) - start + 1, np.nan)
            for d, rr in by_day.items():
                array[d - start] = rr
            series[code] = (start, array)
    stations = [(code, km) for code, km in (rain_stations or []) if code in series]
    # Daily gauge file known up to today - 2, i.e. features as of J - horizon - 1.
    values.update(M.features_for(canyon_id, target_day - horizon - 1, series, {canyon_id: stations}))
    # Yesterday's rain (today - 1 = J - horizon - 1) at the automatic gauges.
    values.update(M.auto_features_from_values(rain_stations or [], automatic_rain or {}))
    values["infoLagDays"] = float(horizon)
    return values
