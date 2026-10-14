"""Feature contract of the debit model (v3).

Every feature listed in ``FEATURE_NAMES`` is computed by exactly one function of this module and by
its Kotlin twin in ``DebitFeatureBuilder.kt``. Feature families:

* time: day-of-year harmonics;
* static: a curated set of physical basin descriptors plus hydrological parameters derived by
  ``hydro.basin_parameters`` and the canyon's simulated-flow climatology;
* weather/hydro: outputs of ``hydro.simulate`` over the ``WINDOW_DAYS`` window ending on the
  target day (target day included, as forecast at runtime);
* history: the canyon's own past reports strictly before the target day (the app recomputes them
  from the scraped debit list), smoothed towards massif/region monthly priors shipped as lookups.

Missing values are encoded as ``MISSING`` (-9999), which CatBoost treats as a distinct small value.
"""
from __future__ import annotations

import math
from collections import defaultdict
from dataclasses import dataclass, field
from datetime import date, timedelta

import numpy as np

import hydro

MISSING = -9999.0
EPOCH = date(1970, 1, 1)
LEVELS = ["SEC", "FILET", "CORRECT", "GROS", "TRES_GROS", "CRUE"]
RANK_BY_LEVEL = {level: rank for rank, level in enumerate(LEVELS)}
HIGH_RANK = 3  # GROS and above
LOW_RANK = 1  # FILET and below

CANYON_PRIOR_STRENGTH = 3.0
MASSIF_PRIOR_STRENGTH = 20.0
REGION_PRIOR_STRENGTH = 50.0
DELTA_MAX_DAYS = 300  # "since last observation" features need the state of that day in the window

STATIC_FEATURES = [
    "areaKm2Log",
    "basinMeanElevationM",
    "basinReliefM",
    "fractionAbove1500m",
    "fractionAbove2000m",
    "fractionAbove2500m",
    "meanSlopeDeg",
    "timeOfConcentrationKirpichMin",
    "drainageDensityKmPerKm2",
    "meanAnnualPrecipMm",
    "meanSnowFractionClimatology",
    "aridityIndex",
    "forestFraction",
    "bareRockFraction",
    "glacierFraction",
    "carbonateFraction",
    "karstIndicator",
    "availableWaterCapacityMm",
    "runoffPotentialIndex",
    "lakeFraction",
    "regulatedCatchment",
    "altitudeDepartM",
    "deniveleM",
    "longueurM",
    "cascadeMaxM",
    "weatherLatitude",
    "weatherLongitude",
    "weatherElevationM",
    "hydroBand1ElevationM",
    "hydroBand3ElevationM",
    "hydroBand5ElevationM",
    "hydroSoilCapacityMm",
    "hydroLogQ10Mean",
    "hydroLogQ90Mean",
]

TIME_FEATURES = ["doySin", "doyCos"]

RESERVOIR_NAMES = ["Q1", "Q3", "Q10", "Q30", "Q90"]

WEATHER_FEATURES = [
    "wPrecipDay0",
    "wPrecipDay1",
    "wPrecip3d",
    "wPrecip7d",
    "wPrecip30d",
    "wPrecipMax7d",
    "wTempDay0",
    "wTemp7d",
    "hLiquidDay0",
    "hLiquid3d",
    "hLiquid7d",
    "hMelt7d",
    "hSwe",
    "hSnowCover",
    "hSweChange30d",
    "hSoilFill",
    *[f"h{name}" for name in RESERVOIR_NAMES],
    "hQ1Evening",
    "hQ3Evening",
    *[f"hZ{name}" for name in RESERVOIR_NAMES],
    "hZQ1Evening",
    "hDeltaLogQ3",
    "hDeltaLogQ10",
    "hDeltaLogQ30",
    "hLiquidSinceLast",
    "hSweChangeSinceLast",
]

HISTORY_FEATURES = [
    "histCount",
    "histLastRank",
    "histDaysSinceLast",
    "histCount7d",
    "histMeanRank7d",
    "histCount30d",
    "histMeanRank30d",
    "histCount365d",
    "histSeasonWeight",
    "histSeasonMeanRank",
    "histSeasonFracHigh",
    "histSeasonFracLow",
    "histMeanRank",
    "histLastAnomaly",
    "massifSeasonMeanRank",
    "massifSeasonFracHigh",
    "massifSeasonWeightLog",
    "regionSeasonMeanRank",
    "regionSeasonFracHigh",
]

# Recent reports of canyons within 30 km (the site's "derniers débits" day grid), see neighbors.py.
NEIGHBOR_FEATURES = [
    "nb3Count", "nb3MeanAnom", "nb3MaxAnom", "nb3MeanLevel", "nb3FracHigh",
    "nb7Count", "nb7MeanAnom", "nb7FracHigh",
    "nbClose3MeanAnom", "nbNearest3Km",
]

# Météo-France rain gauges within 25 km (daily RR up to J-1), see meteofrance.py.
RAIN_FEATURES = [
    "mfRain1", "mfRain3", "mfRain7", "mfMax1", "mfMax3", "mfCount", "mfNearestKm", "mfClose1", "mfClose3",
    "mfRain14", "mfRain30", "mfWetDays7",
]

# Rain of J-1 at the automatic gauges (Météo-France hourly network, MeteoSwiss), see meteofrance.py.
AUTO_RAIN_FEATURES = ["mfAutoRain1", "mfAutoMax1", "mfAutoCount", "mfAutoNearestKm"]

# Age of the real-time information. What the app knows on the morning of today for J = today + h:
# * neighbour reports up to today - 1 -> neighbour window lagged by h;
# * daily gauge file up to today - 2 -> RAIN_FEATURES computed as of J - h - 1;
# * automatic gauges up to today - 1 -> AUTO_RAIN_FEATURES computed as of J - h.
# ``infoLagDays`` = h (0 for the J0 model, 1-2 for the J+1/J+2 model).
INFO_FEATURES = ["infoLagDays"]
REALTIME_FEATURES = NEIGHBOR_FEATURES + RAIN_FEATURES + AUTO_RAIN_FEATURES

FEATURE_NAMES = TIME_FEATURES + STATIC_FEATURES + WEATHER_FEATURES + HISTORY_FEATURES + NEIGHBOR_FEATURES + RAIN_FEATURES + AUTO_RAIN_FEATURES + INFO_FEATURES


def to_epoch_day(day: date) -> int:
    return (day - EPOCH).days


def from_epoch_day(day: int) -> date:
    return EPOCH + timedelta(days=int(day))


# --------------------------------------------------------------------------------------------
# time
# --------------------------------------------------------------------------------------------

def time_features(target: date) -> dict[str, float]:
    angle = 2.0 * math.pi * (target.timetuple().tm_yday - 1) / 365.25
    return {"doySin": round(math.sin(angle), 6), "doyCos": round(math.cos(angle), 6)}


# --------------------------------------------------------------------------------------------
# static
# --------------------------------------------------------------------------------------------

def static_feature_values(
    static: dict,
    canyon: dict,
    weather_latitude: float | None,
    weather_longitude: float | None,
    weather_elevation: float | None,
    climatology: dict[str, float] | None,
) -> dict[str, float | None]:
    """Static descriptors exported per canyon in ``canyon_static_features.json``.

    The app reads them as-is, except the three weather* fields that it overrides with the location
    and elevation returned by the weather API when available.
    """
    area = static.get("basinAreaRasterKm2") or static.get("upstreamCatchmentAreaKm2")
    altitude = static.get("altitudeDepartM", canyon.get("altitudeDepart"))
    params = hydro.basin_parameters({**static, "altitudeDepartM": altitude}, weather_elevation)
    values: dict[str, float | None] = {
        "areaKm2Log": round(math.log1p(float(area)), 6) if area else None,
        "altitudeDepartM": altitude,
        "deniveleM": static.get("deniveleM", canyon.get("denivele")),
        "longueurM": static.get("longueurM", canyon.get("longueur")),
        "cascadeMaxM": static.get("cascadeMaxM", canyon.get("cascadeMax")),
        "weatherLatitude": weather_latitude,
        "weatherLongitude": weather_longitude,
        "weatherElevationM": weather_elevation,
        "hydroBand1ElevationM": params.band_elevations_m[0],
        "hydroBand2ElevationM": params.band_elevations_m[1],
        "hydroBand3ElevationM": params.band_elevations_m[2],
        "hydroBand4ElevationM": params.band_elevations_m[3],
        "hydroBand5ElevationM": params.band_elevations_m[4],
        "hydroSoilCapacityMm": params.soil_capacity_mm,
    }
    for name in STATIC_FEATURES:
        if name not in values:
            raw = static.get(name)
            if isinstance(raw, bool):
                raw = 1.0 if raw else 0.0
            values[name] = raw
    for name in RESERVOIR_NAMES:
        values[f"hydroLog{name}Mean"] = climatology.get(f"hydroLog{name}Mean") if climatology else None
        values[f"hydroLog{name}Std"] = climatology.get(f"hydroLog{name}Std") if climatology else None
    return values


def band_elevations(static_values: dict) -> list[float]:
    return [float(static_values[f"hydroBand{i}ElevationM"]) for i in range(1, 6)]


# --------------------------------------------------------------------------------------------
# priors / history
# --------------------------------------------------------------------------------------------

@dataclass
class MonthlyAggregate:
    """Per-month report counts for an entity (canyon, massif, region, global)."""
    count: list[float] = field(default_factory=lambda: [0.0] * 12)
    rank_sum: list[float] = field(default_factory=lambda: [0.0] * 12)
    high: list[float] = field(default_factory=lambda: [0.0] * 12)
    low: list[float] = field(default_factory=lambda: [0.0] * 12)

    def add(self, month: int, rank: int, weight: float = 1.0) -> None:
        index = month - 1
        self.count[index] += weight
        self.rank_sum[index] += weight * rank
        self.high[index] += weight if rank >= HIGH_RANK else 0.0
        self.low[index] += weight if rank <= LOW_RANK else 0.0

    def season(self, month: int) -> tuple[float, float, float, float]:
        """Weighted (count, rankSum, high, low) over the month and its neighbours (weights 1, 0.5)."""
        totals = [0.0, 0.0, 0.0, 0.0]
        for offset, weight in ((-1, 0.5), (0, 1.0), (1, 0.5)):
            index = (month - 1 + offset) % 12
            totals[0] += weight * self.count[index]
            totals[1] += weight * self.rank_sum[index]
            totals[2] += weight * self.high[index]
            totals[3] += weight * self.low[index]
        return totals[0], totals[1], totals[2], totals[3]

    def to_lookup(self) -> dict[str, float]:
        values: dict[str, float] = {}
        for index in range(12):
            if self.count[index] > 0:
                month = index + 1
                values[f"m{month}.count"] = round(self.count[index], 3)
                values[f"m{month}.rankSum"] = round(self.rank_sum[index], 3)
                values[f"m{month}.high"] = round(self.high[index], 3)
                values[f"m{month}.low"] = round(self.low[index], 3)
        return values

    @classmethod
    def from_lookup(cls, values: dict[str, float]) -> "MonthlyAggregate":
        aggregate = cls()
        for index in range(12):
            month = index + 1
            aggregate.count[index] = float(values.get(f"m{month}.count", 0.0))
            aggregate.rank_sum[index] = float(values.get(f"m{month}.rankSum", 0.0))
            aggregate.high[index] = float(values.get(f"m{month}.high", 0.0))
            aggregate.low[index] = float(values.get(f"m{month}.low", 0.0))
        return aggregate


def smoothed(total: float, weight: float, prior: float, strength: float) -> float:
    return (total + strength * prior) / (weight + strength)


def area_priors(month: int, massif: MonthlyAggregate, region: MonthlyAggregate, global_: MonthlyAggregate) -> dict[str, float]:
    """Hierarchically smoothed seasonal priors: global -> region -> massif.

    Training rows must only see reports dated before them (see ``history.add_history``): a prior that
    contains the row's own label leaks it (leave-one-out encoding is a deterministic function of it).
    """
    def season(aggregate: MonthlyAggregate) -> tuple[float, float, float, float]:
        return aggregate.season(month)

    g_count, g_rank, g_high, g_low = season(global_)
    g_count = max(g_count, 1e-9)
    global_mean, global_high, global_low = g_rank / g_count, g_high / g_count, g_low / g_count
    r_count, r_rank, r_high, r_low = season(region)
    region_mean = smoothed(r_rank, r_count, global_mean, REGION_PRIOR_STRENGTH)
    region_high = smoothed(r_high, r_count, global_high, REGION_PRIOR_STRENGTH)
    region_low = smoothed(r_low, r_count, global_low, REGION_PRIOR_STRENGTH)
    m_count, m_rank, m_high, m_low = season(massif)
    return {
        "regionSeasonMeanRank": region_mean,
        "regionSeasonFracHigh": region_high,
        "regionSeasonFracLow": region_low,
        "massifSeasonMeanRank": smoothed(m_rank, m_count, region_mean, MASSIF_PRIOR_STRENGTH),
        "massifSeasonFracHigh": smoothed(m_high, m_count, region_high, MASSIF_PRIOR_STRENGTH),
        "massifSeasonFracLow": smoothed(m_low, m_count, region_low, MASSIF_PRIOR_STRENGTH),
        "massifSeasonWeightLog": math.log1p(m_count),
    }


def history_features(
    past: list[tuple[int, int]],
    target_day: int,
    month: int,
    priors: dict[str, float],
) -> dict[str, float | None]:
    """Canyon history features.

    ``past``: (epochDay, rank) of the canyon's reports strictly before ``target_day``, any order.
    """
    canyon = MonthlyAggregate()
    count7 = count30 = count365 = 0
    rank7 = rank30 = 0.0
    last_day = None
    last_rank = None
    total_rank = 0.0
    for day, rank in past:
        canyon.add(from_epoch_day(day).month, rank)
        total_rank += rank
        age = target_day - day
        if age <= 7:
            count7 += 1
            rank7 += rank
        if age <= 30:
            count30 += 1
            rank30 += rank
        if age <= 365:
            count365 += 1
        # ties on the same day: keep the highest rank (safety-first and order independent)
        if last_day is None or day > last_day or (day == last_day and rank > (last_rank or 0)):
            last_day, last_rank = day, rank

    s_count, s_rank, s_high, s_low = canyon.season(month)
    season_mean = smoothed(s_rank, s_count, priors["massifSeasonMeanRank"], CANYON_PRIOR_STRENGTH)
    count = len(past)
    all_mean = smoothed(total_rank, float(count), priors["massifSeasonMeanRank"], CANYON_PRIOR_STRENGTH)
    return {
        "histCount": float(count),
        "histLastRank": float(last_rank) if last_rank is not None else None,
        "histDaysSinceLast": float(target_day - last_day) if last_day is not None else None,
        "histCount7d": float(count7),
        "histMeanRank7d": rank7 / count7 if count7 else None,
        "histCount30d": float(count30),
        "histMeanRank30d": rank30 / count30 if count30 else None,
        "histCount365d": float(count365),
        "histSeasonWeight": s_count,
        "histSeasonMeanRank": season_mean,
        "histSeasonFracHigh": smoothed(s_high, s_count, priors["massifSeasonFracHigh"], CANYON_PRIOR_STRENGTH),
        "histSeasonFracLow": smoothed(s_low, s_count, priors["massifSeasonFracLow"], CANYON_PRIOR_STRENGTH),
        "histMeanRank": all_mean,
        "histLastAnomaly": (last_rank - season_mean) if last_rank is not None else None,
        "massifSeasonMeanRank": priors["massifSeasonMeanRank"],
        "massifSeasonFracHigh": priors["massifSeasonFracHigh"],
        "massifSeasonWeightLog": priors["massifSeasonWeightLog"],
        "regionSeasonMeanRank": priors["regionSeasonMeanRank"],
        "regionSeasonFracHigh": priors["regionSeasonFracHigh"],
    }


# --------------------------------------------------------------------------------------------
# weather / hydro (vectorised)
# --------------------------------------------------------------------------------------------

def hydro_features(
    precipitation: np.ndarray,
    temperature: np.ndarray,
    target_days: np.ndarray,
    latitude: np.ndarray,
    reference_elevation: np.ndarray,
    bands: np.ndarray,
    soil_capacity: np.ndarray,
    days_since_last: np.ndarray,
    log_q_mean: np.ndarray,
    log_q_std: np.ndarray,
) -> dict[str, np.ndarray]:
    """Weather/hydro features for n windows.

    precipitation/temperature: (n, WINDOW_DAYS) already filled (``hydro.fill_window``), the target
    day in the last column. days_since_last: (n,) float, NaN when no previous report.
    log_q_mean/log_q_std: (n, 5) canyon climatology of log1p(Q), NaN when unknown.
    """
    n, days = precipitation.shape
    offsets = np.arange(days - 1, -1, -1)  # days before target for each column
    day_numbers = target_days[:, None] - offsets[None, :]
    day_of_year = _day_of_year(day_numbers)

    since = np.where(np.isnan(days_since_last), -1, days_since_last)
    delta_ok = (since >= 1) & (since <= DELTA_MAX_DAYS)
    capture_index = np.where(delta_ok, (days - 1) - since, -1).astype(np.int64)

    result = hydro.simulate(
        precipitation, temperature, day_of_year, latitude, reference_elevation, bands, soil_capacity,
        capture_index=capture_index,
    )
    p, t = precipitation, temperature
    last = days - 1
    features: dict[str, np.ndarray] = {
        "wPrecipDay0": p[:, last],
        "wPrecipDay1": p[:, last - 1],
        "wPrecip3d": p[:, last - 3:last].sum(axis=1),
        "wPrecip7d": p[:, last - 7:last].sum(axis=1),
        "wPrecip30d": p[:, last - 30:last].sum(axis=1),
        "wPrecipMax7d": p[:, last - 7:last].max(axis=1),
        "wTempDay0": t[:, last],
        "wTemp7d": t[:, last - 7:last].mean(axis=1),
        "hLiquidDay0": result.liquid_input[:, last],
        "hLiquid3d": result.liquid_input[:, last - 3:last].sum(axis=1),
        "hLiquid7d": result.liquid_input[:, last - 7:last].sum(axis=1),
        "hMelt7d": result.melt[:, last - 7:last].sum(axis=1),
        "hSwe": result.morning_swe,
        "hSnowCover": result.morning_snow_cover,
        "hSweChange30d": result.swe[:, last - 1] - result.swe[:, last - 31],
        "hSoilFill": result.morning_soil_fill,
    }
    log_morning = np.log1p(result.morning_q)
    log_evening = np.log1p(result.evening_q)
    for index, name in enumerate(RESERVOIR_NAMES):
        features[f"h{name}"] = result.morning_q[:, index]
        features[f"hZ{name}"] = (log_morning[:, index] - log_q_mean[:, index]) / log_q_std[:, index]
    features["hQ1Evening"] = result.evening_q[:, 0]
    features["hQ3Evening"] = result.evening_q[:, 1]
    features["hZQ1Evening"] = (log_evening[:, 0] - log_q_mean[:, 0]) / log_q_std[:, 0]

    log_captured = np.log1p(result.captured_q)
    features["hDeltaLogQ3"] = np.where(delta_ok, log_morning[:, 1] - log_captured[:, 1], np.nan)
    features["hDeltaLogQ10"] = np.where(delta_ok, log_morning[:, 2] - log_captured[:, 2], np.nan)
    features["hDeltaLogQ30"] = np.where(delta_ok, log_morning[:, 3] - log_captured[:, 3], np.nan)
    columns = np.arange(days)[None, :]
    since_mask = (columns >= capture_index[:, None]) & (columns < last) & delta_ok[:, None]
    features["hLiquidSinceLast"] = np.where(delta_ok, (result.liquid_input * since_mask).sum(axis=1), np.nan)
    features["hSweChangeSinceLast"] = np.where(delta_ok, result.morning_swe - result.captured_swe, np.nan)
    return features


def _day_of_year(epoch_days: np.ndarray) -> np.ndarray:
    """Vectorised day-of-year (1..366) for epoch days."""
    dates = np.datetime64("1970-01-01") + epoch_days.astype("timedelta64[D]")
    years = dates.astype("datetime64[Y]")
    return (dates - years).astype(np.int64) + 1


def climatology_from_series(
    precipitation: np.ndarray,
    temperature: np.ndarray,
    start_days: np.ndarray,
    valid_from: np.ndarray,
    latitude: np.ndarray,
    reference_elevation: np.ndarray,
    bands: np.ndarray,
    soil_capacity: np.ndarray,
    min_years: float = 3.0,
) -> list[dict[str, float] | None]:
    """Mean/std of log1p(Q_k) from a continuous simulation of each target's full record.

    Arrays are calendar-aligned (n, days); statistics use days at least ``WINDOW_DAYS`` after the
    first valid day of each target (spin-up) and from ``valid_from`` onwards.
    """
    n, days = precipitation.shape
    day_numbers = start_days[:, None] + np.arange(days)[None, :]
    day_of_year = _day_of_year(day_numbers)
    sums = np.zeros((n, len(RESERVOIR_NAMES)))
    squares = np.zeros((n, len(RESERVOIR_NAMES)))
    counts = np.zeros(n)

    params = hydro.HydroParameters.build(latitude, reference_elevation, bands, soil_capacity)
    state = hydro.HydroState.initial(params)
    for index in range(days):
        hydro.step(state, params, precipitation[:, index], temperature[:, index], day_of_year[:, index])
        q = state.q
        use = index >= valid_from
        if use.any():
            log_q = np.log1p(q[use])
            sums[use] += log_q
            squares[use] += log_q * log_q
            counts[use] += 1

    output: list[dict[str, float] | None] = []
    for row in range(n):
        if counts[row] < min_years * 365:
            output.append(None)
            continue
        mean = sums[row] / counts[row]
        std = np.sqrt(np.maximum(squares[row] / counts[row] - mean * mean, 1e-6))
        entry: dict[str, float] = {}
        for index, name in enumerate(RESERVOIR_NAMES):
            entry[f"hydroLog{name}Mean"] = round(float(mean[index]), 6)
            entry[f"hydroLog{name}Std"] = round(float(max(std[index], 0.05)), 6)
        output.append(entry)
    return output


def finalize_value(value) -> float:
    if value is None:
        return MISSING
    try:
        number = float(value)
    except (TypeError, ValueError):
        return MISSING
    if math.isnan(number) or math.isinf(number):
        return MISSING
    return number


def lookup_tables(rows: list[tuple[str, str, int, int]]) -> tuple[dict[str, MonthlyAggregate], dict[str, MonthlyAggregate], MonthlyAggregate]:
    """Aggregate (massif, region, month, rank) rows into monthly lookups."""
    massifs: dict[str, MonthlyAggregate] = defaultdict(MonthlyAggregate)
    regions: dict[str, MonthlyAggregate] = defaultdict(MonthlyAggregate)
    global_ = MonthlyAggregate()
    for massif, region, month, rank in rows:
        massifs[massif].add(month, rank)
        regions[region].add(month, rank)
        global_.add(month, rank)
    return dict(massifs), dict(regions), global_
