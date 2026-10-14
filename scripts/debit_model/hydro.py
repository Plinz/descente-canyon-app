"""Conceptual hydrological state model shared by training and the Android runtime.

Why: the debit label depends on hydrological *state* (snowpack, soil storage, slow karst/groundwater
reservoirs) far more than on raw recent rainfall sums. This module turns a daily weather window
(precipitation, mean temperature at the weather-grid elevation) into physically meaningful states.

The model is deliberately simple and parameter-free per canyon (except values derived from static
basin descriptors), so that it is robust without calibration and trivially portable:

* snow: 5 equal-area elevation bands, temperature lapse rate, rain/snow partition, degree-day melt;
* production: GR4J production store (capacity derived from soil water capacity), Oudin PET;
* routing: bank of linear reservoirs with time constants 1, 3, 10, 30 and 90 days.

Contract with the app (``DebitHydroModel.kt``): identical constants, identical update order,
window of ``WINDOW_DAYS`` days ending on the target day, deterministic initial state. Any change here
must be mirrored in Kotlin; ``export_golden_vectors.py`` produces fixtures checked by a Kotlin test.

Array convention: every function works on numpy arrays of shape (n,) so that thousands of
observation windows are simulated in parallel; the Kotlin port works on scalars.
"""
from __future__ import annotations

import math
from dataclasses import dataclass

import numpy as np

WINDOW_DAYS = 400  # simulated days up to and including the target day
MIN_VALID_DAYS = 380  # windows with fewer observed days are rejected
BAND_COUNT = 5
LAPSE_RATE_C_PER_M = -0.0065
PRECIP_GRADIENT_PER_M = 0.0004
PRECIP_FACTOR_MIN = 0.5
PRECIP_FACTOR_MAX = 2.0
SNOW_ALL_BELOW_C = -1.0
RAIN_ALL_ABOVE_C = 2.0
DEGREE_DAY_FACTOR_MM = 3.5
SWE_CAP_MM = 2000.0
SNOW_COVER_SWE_MM = 5.0
RESERVOIR_DAYS = (1.0, 3.0, 10.0, 30.0, 90.0)
DEFAULT_SOIL_CAPACITY_MM = 250.0
SOIL_CAPACITY_MIN_MM = 100.0
SOIL_CAPACITY_MAX_MM = 500.0
INITIAL_SOIL_FILL = 0.5
FALLBACK_BAND_OFFSETS_M = (0.0, 150.0, 300.0, 450.0, 600.0)


@dataclass(frozen=True)
class BasinParameters:
    band_elevations_m: tuple[float, float, float, float, float]
    soil_capacity_mm: float


def _interp_elevation(quantile: float, points: list[tuple[float, float]]) -> float:
    for (q0, z0), (q1, z1) in zip(points, points[1:]):
        if q0 <= quantile <= q1:
            if q1 - q0 < 1e-9:
                return z0
            return z0 + (z1 - z0) * (quantile - q0) / (q1 - q0)
    return points[-1][1]


def basin_parameters(static: dict, fallback_elevation_m: float | None) -> BasinParameters:
    """Derive band elevations and soil capacity from static descriptors.

    Band elevations are the 10/30/50/70/90 % area quantiles of a hypsometric curve interpolated
    through (0, min), (fraction below 1500/2000/2500 m), (0.5, median), (1, max).
    The result is exported as static features, so the app never re-implements this function.
    """
    zmin = static.get("basinMinElevationM")
    zmax = static.get("basinMaxElevationM")
    zmed = static.get("basinMedianElevationM")
    if zmin is not None and zmax is not None and zmax >= zmin > -500:
        points = [(0.0, float(zmin))]
        if zmed is not None and zmin <= zmed <= zmax:
            points.append((0.5, float(zmed)))
        for level, key in ((1500.0, "fractionAbove1500m"), (2000.0, "fractionAbove2000m"), (2500.0, "fractionAbove2500m")):
            fraction = static.get(key)
            if fraction is not None and zmin < level < zmax:
                points.append((1.0 - float(fraction), level))
        points.append((1.0, float(zmax)))
        points.sort()
        # enforce a monotone curve (descriptor rounding can create small inversions)
        monotone: list[tuple[float, float]] = []
        for q, z in points:
            if monotone and z < monotone[-1][1]:
                z = monotone[-1][1]
            monotone.append((q, z))
        bands = tuple(_interp_elevation(q, monotone) for q in (0.1, 0.3, 0.5, 0.7, 0.9))
    else:
        base = static.get("altitudeDepartM")
        if base is None:
            base = fallback_elevation_m if fallback_elevation_m is not None else 1000.0
        bands = tuple(float(base) + offset for offset in FALLBACK_BAND_OFFSETS_M)

    awc = static.get("availableWaterCapacityMm")
    if awc is not None and awc > 0:
        soil_capacity = min(max(2.0 * float(awc), SOIL_CAPACITY_MIN_MM), SOIL_CAPACITY_MAX_MM)
    else:
        soil_capacity = DEFAULT_SOIL_CAPACITY_MM
    return BasinParameters(tuple(round(b, 1) for b in bands), round(soil_capacity, 1))


def extraterrestrial_radiation(latitude_deg: np.ndarray, day_of_year: np.ndarray) -> np.ndarray:
    """FAO-56 extraterrestrial radiation (MJ m-2 day-1)."""
    phi = np.radians(latitude_deg)
    angle = 2.0 * math.pi * day_of_year / 365.0
    declination = 0.409 * np.sin(angle - 1.39)
    inverse_distance = 1.0 + 0.033 * np.cos(angle)
    sunset = np.arccos(np.clip(-np.tan(phi) * np.tan(declination), -1.0, 1.0))
    return (24.0 * 60.0 / math.pi) * 0.0820 * inverse_distance * (
        sunset * np.sin(phi) * np.sin(declination) + np.cos(phi) * np.cos(declination) * np.sin(sunset)
    )


def oudin_pet(radiation: np.ndarray, temperature_c: np.ndarray) -> np.ndarray:
    return np.maximum(0.0, radiation / 2.45 * (temperature_c + 5.0) / 100.0)


def fill_window(precipitation: np.ndarray, temperature: np.ndarray) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    """Fill missing values: precipitation -> 0, temperature -> previous valid (or next valid).

    Inputs have shape (n, days). Returns filled copies and the per-row count of valid days
    (a day is valid when both variables are present).
    """
    valid = ~np.isnan(precipitation) & ~np.isnan(temperature)
    p = np.where(np.isnan(precipitation), 0.0, precipitation)
    t = temperature.copy()
    # forward fill then backward fill along time
    for index in range(1, t.shape[1]):
        missing = np.isnan(t[:, index])
        t[missing, index] = t[missing, index - 1]
    for index in range(t.shape[1] - 2, -1, -1):
        missing = np.isnan(t[:, index])
        t[missing, index] = t[missing, index + 1]
    t = np.where(np.isnan(t), 10.0, t)
    return p, t, valid.sum(axis=1)


@dataclass
class SimulationResult:
    """States are recorded at two moments.

    * ``morning``: after processing day J-1 (state when the canyon is descended on day J).
    * ``evening``: after processing day J (includes the target-day rain/melt).
    """
    morning_q: np.ndarray  # (n, reservoirs)
    evening_q: np.ndarray  # (n, reservoirs)
    morning_swe: np.ndarray  # (n,)
    morning_snow_cover: np.ndarray  # (n,)
    morning_soil_fill: np.ndarray  # (n,)
    liquid_input: np.ndarray  # (n, days)  rain + melt per day, basin mean
    melt: np.ndarray  # (n, days)
    swe: np.ndarray  # (n, days) basin-mean SWE after each day
    captured_q: np.ndarray  # (n, reservoirs) morning reservoir state at ``capture_index`` (NaN if none)
    captured_swe: np.ndarray  # (n,) morning basin-mean SWE at ``capture_index`` (NaN if none)


@dataclass
class HydroParameters:
    """Per-window constant arrays derived from basin parameters."""
    latitude_deg: np.ndarray  # (n,)
    temperature_offset: np.ndarray  # (n, 5)
    precip_factor: np.ndarray  # (n, 5)
    soil_capacity: np.ndarray  # (n,)
    decay: np.ndarray  # (reservoirs,)

    @classmethod
    def build(cls, latitude_deg, reference_elevation_m, band_elevations_m, soil_capacity_mm) -> "HydroParameters":
        dz = band_elevations_m - reference_elevation_m[:, None]
        return cls(
            latitude_deg=latitude_deg,
            temperature_offset=LAPSE_RATE_C_PER_M * dz,
            precip_factor=np.clip(1.0 + PRECIP_GRADIENT_PER_M * dz, PRECIP_FACTOR_MIN, PRECIP_FACTOR_MAX),
            soil_capacity=soil_capacity_mm,
            decay=np.array([math.exp(-1.0 / k) for k in RESERVOIR_DAYS]),
        )


@dataclass
class HydroState:
    swe: np.ndarray  # (n, 5) mm
    soil: np.ndarray  # (n,) mm
    q: np.ndarray  # (n, reservoirs) mm/day

    @classmethod
    def initial(cls, params: HydroParameters) -> "HydroState":
        n = params.soil_capacity.shape[0]
        return cls(
            swe=np.zeros((n, BAND_COUNT)),
            soil=INITIAL_SOIL_FILL * params.soil_capacity.copy(),
            q=np.zeros((n, len(RESERVOIR_DAYS))),
        )


def step(state: HydroState, params: HydroParameters, precipitation: np.ndarray, temperature: np.ndarray, day_of_year: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    """Advance one day in place. Returns (basin liquid input, basin melt) in mm."""
    t_band = temperature[:, None] + params.temperature_offset
    p_band = precipitation[:, None] * params.precip_factor
    snow_fraction = np.clip((RAIN_ALL_ABOVE_C - t_band) / (RAIN_ALL_ABOVE_C - SNOW_ALL_BELOW_C), 0.0, 1.0)
    snow = p_band * snow_fraction
    rain = p_band - snow
    swe = state.swe + snow
    melt = np.minimum(swe, np.maximum(0.0, DEGREE_DAY_FACTOR_MM * t_band))
    state.swe = np.minimum(swe - melt, SWE_CAP_MM)
    liquid = (rain + melt).mean(axis=1)

    pet = oudin_pet(extraterrestrial_radiation(params.latitude_deg, day_of_year), t_band.mean(axis=1))
    x1 = params.soil_capacity
    soil = state.soil
    net_rain = np.maximum(liquid - pet, 0.0)
    net_evap = np.maximum(pet - liquid, 0.0)
    fill = soil / x1
    tp = np.tanh(net_rain / x1)
    storage_gain = x1 * (1.0 - fill * fill) * tp / (1.0 + fill * tp)
    te = np.tanh(net_evap / x1)
    storage_loss = soil * (2.0 - fill) * te / (1.0 + (1.0 - fill) * te)
    soil = soil - storage_loss + storage_gain
    percolation = soil * (1.0 - (1.0 + (4.0 / 9.0 * soil / x1) ** 4) ** -0.25)
    state.soil = soil - percolation
    runoff = percolation + (net_rain - storage_gain)
    state.q = params.decay * state.q + (1.0 - params.decay) * runoff[:, None]
    return liquid, melt.mean(axis=1)


def simulate(
    precipitation: np.ndarray,
    temperature: np.ndarray,
    day_of_year: np.ndarray,
    latitude_deg: np.ndarray,
    reference_elevation_m: np.ndarray,
    band_elevations_m: np.ndarray,
    soil_capacity_mm: np.ndarray,
    capture_index: np.ndarray | None = None,
) -> SimulationResult:
    """Run the model over windows.

    precipitation, temperature, day_of_year: (n, days) with the target day in the last column.
    latitude_deg, reference_elevation_m, soil_capacity_mm: (n,). band_elevations_m: (n, 5).
    capture_index: (n,) day index whose *morning* state (before processing that day) is captured,
    or -1 for none. Used for "change since the last observation" features.
    """
    n, days = precipitation.shape
    params = HydroParameters.build(latitude_deg, reference_elevation_m, band_elevations_m, soil_capacity_mm)
    state = HydroState.initial(params)
    if capture_index is None:
        capture_index = np.full(n, -1, dtype=np.int64)
    captured_q = np.full((n, len(RESERVOIR_DAYS)), np.nan)
    captured_swe = np.full(n, np.nan)
    liquid_history = np.zeros((n, days))
    melt_history = np.zeros((n, days))
    swe_history = np.zeros((n, days))
    morning = None

    for index in range(days):
        capture = capture_index == index
        if capture.any():
            captured_q[capture] = state.q[capture]
            captured_swe[capture] = state.swe[capture].mean(axis=1)
        if index == days - 1:
            morning = (state.q.copy(), state.swe.mean(axis=1), (state.swe > SNOW_COVER_SWE_MM).mean(axis=1), state.soil / params.soil_capacity)
        liquid, melt = step(state, params, precipitation[:, index], temperature[:, index], day_of_year[:, index])
        liquid_history[:, index] = liquid
        melt_history[:, index] = melt
        swe_history[:, index] = state.swe.mean(axis=1)

    assert morning is not None
    return SimulationResult(
        morning_q=morning[0],
        evening_q=state.q,
        morning_swe=morning[1],
        morning_snow_cover=morning[2],
        morning_soil_fill=morning[3],
        liquid_input=liquid_history,
        melt=melt_history,
        swe=swe_history,
        captured_q=captured_q,
        captured_swe=captured_swe,
    )
