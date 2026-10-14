"""Météo-France rain gauges (open data) near the canyons as nowcast features.

Daily precipitation RR of the climatological network (``fetch_meteofrance.py``). The app downloads
the department "latest" file every morning; it contains RR up to J-1.

Features for target day J use RR(J-1), RR(J-3..J-1) and RR(J-7..J-1) at the gauges within
``RADIUS_KM`` (inverse-distance weighted mean, plus the maximum over gauges to catch local storms).
"""
from __future__ import annotations

import gzip
import json
import math
from pathlib import Path

import numpy as np
import pandas as pd

import features as F

RADIUS_KM = 25.0
CLOSE_KM = 12.0
MAX_STATIONS = 8
START = 20040101
RAIN_FEATURES = F.RAIN_FEATURES


def department_of(station_code: str) -> str:
    """Department part of a station code, as used in the file names (``Q_<dept>_...``)."""
    return station_code[:3] if station_code[:2] in ("97", "98") else station_code[:2]


def load_stations(directory: Path, points: dict[int, tuple[float, float]], cache: Path) -> tuple[dict, dict]:
    """Return ({station: (start_day, rr array)}, {canyon: [(station, km)]})."""
    if cache.exists():
        payload = pd.read_pickle(cache)
        return payload["series"], payload["nearby"]
    lat_c = np.array([p[0] for p in points.values()])
    lon_c = np.array([p[1] for p in points.values()])
    frames = []
    for path in sorted(directory.glob("Q_*_RR-T-Vent.csv.gz")):
        if "avant-1949" in path.name:
            continue
        with gzip.open(path, "rt", encoding="utf-8", errors="replace") as handle:
            frame = pd.read_csv(handle, sep=";", usecols=["NUM_POSTE", "LAT", "LON", "ALTI", "AAAAMMJJ", "RR"], dtype={"NUM_POSTE": str})
        frame = frame[(frame["AAAAMMJJ"] >= START) & frame["RR"].notna()]
        if frame.empty:
            continue
        stations = frame.groupby("NUM_POSTE")[["LAT", "LON"]].first()
        keep = []
        for code, (lat, lon) in stations.iterrows():
            near = (np.abs(lat_c - lat) < 0.3) & (np.abs(lon_c - lon) < 0.45)
            if near.any():
                keep.append(code)
        frames.append(frame[frame["NUM_POSTE"].isin(keep)])
        print(f"{path.name}: {len(keep)} stations kept", flush=True)
    data = pd.concat(frames, ignore_index=True)
    data["day"] = pd.to_datetime(data["AAAAMMJJ"].astype(str), format="%Y%m%d").map(lambda t: F.to_epoch_day(t.date()))
    series = {}
    meta = {}
    for code, group in data.groupby("NUM_POSTE"):
        days = group["day"].to_numpy()
        start = int(days.min())
        values = np.full(int(days.max()) - start + 1, np.nan)
        values[days - start] = group["RR"].to_numpy(dtype=float)
        series[code] = (start, values)
        meta[code] = (float(group["LAT"].iloc[0]), float(group["LON"].iloc[0]), float(group["ALTI"].iloc[0]))
    nearby = {}
    codes = list(meta)
    lat_s = np.radians(np.array([meta[c][0] for c in codes]))
    lon_s = np.radians(np.array([meta[c][1] for c in codes]))
    for canyon_id, (lat, lon) in points.items():
        la, lo = math.radians(lat), math.radians(lon)
        a = np.sin((lat_s - la) / 2) ** 2 + math.cos(la) * np.cos(lat_s) * np.sin((lon_s - lo) / 2) ** 2
        distance = 6371.0 * 2 * np.arcsin(np.sqrt(np.clip(a, 0, 1)))
        close = np.argsort(distance)
        nearby[canyon_id] = [(codes[i], round(float(distance[i]), 3)) for i in close[:MAX_STATIONS] if distance[i] <= RADIUS_KM]
    cache.parent.mkdir(parents=True, exist_ok=True)
    pd.to_pickle({"series": series, "nearby": nearby, "meta": meta}, cache)
    return series, nearby


def _value(series, code: str, day: int) -> float:
    start, values = series[code]
    index = day - start
    if 0 <= index < len(values):
        return float(values[index])
    return math.nan


def features_for(canyon_id: int, target_day: int, series: dict, nearby: dict) -> dict[str, float | None]:
    """Short windows (weighted over gauges) + long windows at the nearest usable gauge."""
    out = _short_window_features(canyon_id, target_day, series, nearby)
    out.update(features_v2(canyon_id, target_day, series, nearby))
    return out


def _short_window_features(canyon_id: int, target_day: int, series: dict, nearby: dict) -> dict[str, float | None]:
    stations = []
    for code, distance in nearby.get(canyon_id, []):
        r = [_value(series, code, target_day - k) for k in range(1, 8)]
        if math.isnan(r[0]) or sum(math.isnan(v) for v in r) > 2:
            continue
        r = [0.0 if math.isnan(v) else v for v in r]
        stations.append((distance, r[0], sum(r[:3]), sum(r)))
    out: dict[str, float | None] = {name: None for name in RAIN_FEATURES if name not in RAIN_V2_FEATURES}
    out["mfCount"] = float(len(stations))
    if not stations:
        return out
    w = np.array([1.0 / (1.0 + d / 5.0) for d, *_ in stations])
    r1 = np.array([s[1] for s in stations])
    r3 = np.array([s[2] for s in stations])
    r7 = np.array([s[3] for s in stations])
    out.update({
        "mfRain1": float(np.sum(w * r1) / w.sum()),
        "mfRain3": float(np.sum(w * r3) / w.sum()),
        "mfRain7": float(np.sum(w * r7) / w.sum()),
        "mfMax1": float(r1.max()),
        "mfMax3": float(r3.max()),
        "mfNearestKm": float(min(d for d, *_ in stations)),
    })
    close = [s for s in stations if s[0] <= CLOSE_KM]
    if close:
        out["mfClose1"] = float(np.mean([s[1] for s in close]))
        out["mfClose3"] = float(np.mean([s[2] for s in close]))
    return out


RAIN_V2_FEATURES = ["mfRain14", "mfRain30", "mfWetDays7"]


def features_v2(canyon_id: int, target_day: int, series: dict, nearby: dict) -> dict[str, float | None]:
    """Longer station windows (experiment): 14/30-day sums and wet days, nearest usable gauge first."""
    for code, distance in nearby.get(canyon_id, []):
        r = [_value(series, code, target_day - k) for k in range(1, 31)]
        if math.isnan(r[0]) or sum(math.isnan(v) for v in r) > 4:
            continue
        r = [0.0 if math.isnan(v) else v for v in r]
        return {"mfRain14": float(sum(r[:14])), "mfRain30": float(sum(r)), "mfWetDays7": float(sum(v >= 1.0 for v in r[:7]))}
    return {name: None for name in RAIN_V2_FEATURES}


def build_frame_v2(base: pd.DataFrame, directory: Path, points: dict[int, tuple[float, float]], cache: Path) -> pd.DataFrame:
    series, nearby = load_stations(directory, points, cache)
    rows = [features_v2(int(c), int(d), series, nearby) for c, d in zip(base["canyonId"], base["day"])]
    return pd.DataFrame(rows, index=base.index).astype(float)


AUTO_FEATURES = F.AUTO_RAIN_FEATURES


def is_automatic(code: str, hourly_codes: set[str]) -> bool:
    """Stations whose rain of J-1 is known on the morning of J: Météo-France hourly network
    (hourly file), every MeteoSwiss station (daily file published with J-1)."""
    return code.startswith(("CHS_", "CHP_")) or code in hourly_codes


def auto_features(canyon_id: int, target_day: int, series: dict, nearby: dict, hourly_codes: set[str]) -> dict[str, float | None]:
    """Rain of J-1 (06 UTC J-1 -> 06 UTC J) at the automatic gauges within 25 km."""
    values = {}
    for code, _ in nearby.get(canyon_id, []):
        if is_automatic(code, hourly_codes) and code in series:
            rain = _value(series, code, target_day - 1)
            if not math.isnan(rain):
                values[code] = rain
    return auto_features_from_values(nearby.get(canyon_id, []), values)


def auto_features_from_values(stations: list[tuple[str, float]], values: dict[str, float]) -> dict[str, float | None]:
    """Same features from yesterday's rain already known per automatic gauge (app path)."""
    found = [(distance, values[code]) for code, distance in stations if code in values]
    out: dict[str, float | None] = {"mfAutoRain1": None, "mfAutoMax1": None, "mfAutoCount": float(len(found)), "mfAutoNearestKm": None}
    if found:
        w = np.array([1.0 / (1.0 + d / 5.0) for d, _ in found])
        r = np.array([v for _, v in found])
        out.update({"mfAutoRain1": float(np.sum(w * r) / w.sum()), "mfAutoMax1": float(r.max()), "mfAutoNearestKm": float(min(d for d, _ in found))})
    return out


def build_frame(base: pd.DataFrame, directory: Path, points: dict[int, tuple[float, float]], cache: Path) -> pd.DataFrame:
    series, nearby = load_stations(directory, points, cache)
    rows = [features_for(int(c), int(d), series, nearby) for c, d in zip(base["canyonId"], base["day"])]
    return pd.DataFrame(rows, index=base.index).astype(float)
