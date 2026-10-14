"""Observed snow depth near the canyon (Météo-France climatological network, open data).

``autres-parametres`` daily files carry NEIGETOTX (maximum total snow depth of the day, cm) at
mountain stations (including the Nivôse network). The app reads the department "latest" files.

For a target day J (values up to J-1):
* the most representative station for the upper basin (score = distance/20 km + |altitude - upper
  band elevation|/300 m, within ``RADIUS_KM``): depth at J-1, 7-day change, altitude offset;
* mean depth of the stations above the basin median elevation.
"""
from __future__ import annotations

import gzip
import math
from pathlib import Path

import numpy as np
import pandas as pd

import features as F

RADIUS_KM = 40.0
MAX_STATIONS = 10
START = 20040101
SNOW_FEATURES = ["snowDepth", "snowChange7", "snowAltOffset", "snowDistKm", "snowUpperMean", "snowCount"]


def load_stations(directory: Path, points: dict[int, tuple[float, float]], cache: Path):
    if cache.exists():
        payload = pd.read_pickle(cache)
        return payload["series"], payload["nearby"], payload["meta"]
    lat_c = np.array([p[0] for p in points.values()])
    lon_c = np.array([p[1] for p in points.values()])
    frames = []
    for path in sorted(directory.glob("Q_*_autres-parametres.csv.gz")):
        with gzip.open(path, "rt", encoding="utf-8", errors="replace") as handle:
            frame = pd.read_csv(handle, sep=";", usecols=["NUM_POSTE", "LAT", "LON", "ALTI", "AAAAMMJJ", "NEIGETOTX"], dtype={"NUM_POSTE": str})
        frame = frame[(frame["AAAAMMJJ"] >= START) & frame["NEIGETOTX"].notna()]
        if frame.empty:
            continue
        stations = frame.groupby("NUM_POSTE")[["LAT", "LON"]].first()
        keep = [code for code, (lat, lon) in stations.iterrows() if ((np.abs(lat_c - lat) < 0.45) & (np.abs(lon_c - lon) < 0.65)).any()]
        frames.append(frame[frame["NUM_POSTE"].isin(keep)])
        print(f"{path.name}: {len(keep)} snow stations kept", flush=True)
    data = pd.concat(frames, ignore_index=True)
    data["day"] = pd.to_datetime(data["AAAAMMJJ"].astype(str), format="%Y%m%d").map(lambda t: F.to_epoch_day(t.date()))
    series, meta = {}, {}
    for code, group in data.groupby("NUM_POSTE"):
        days = group["day"].to_numpy()
        start = int(days.min())
        values = np.full(int(days.max()) - start + 1, np.nan)
        values[days - start] = group["NEIGETOTX"].to_numpy(dtype=float)
        series[code] = (start, values)
        meta[code] = (float(group["LAT"].iloc[0]), float(group["LON"].iloc[0]), float(group["ALTI"].iloc[0]))
    codes = list(meta)
    lat_s = np.radians(np.array([meta[c][0] for c in codes]))
    lon_s = np.radians(np.array([meta[c][1] for c in codes]))
    nearby = {}
    for canyon_id, (lat, lon) in points.items():
        la, lo = math.radians(lat), math.radians(lon)
        a = np.sin((lat_s - la) / 2) ** 2 + math.cos(la) * np.cos(lat_s) * np.sin((lon_s - lo) / 2) ** 2
        distance = 6371.0 * 2 * np.arcsin(np.sqrt(np.clip(a, 0, 1)))
        order = np.argsort(distance)
        nearby[canyon_id] = [(codes[i], round(float(distance[i]), 3), meta[codes[i]][2]) for i in order[:MAX_STATIONS] if distance[i] <= RADIUS_KM]
    cache.parent.mkdir(parents=True, exist_ok=True)
    pd.to_pickle({"series": series, "nearby": nearby, "meta": meta}, cache)
    return series, nearby, meta


def _value(series, code: str, day: int) -> float:
    start, values = series[code]
    index = day - start
    return float(values[index]) if 0 <= index < len(values) else math.nan


def features_for(canyon_id: int, target_day: int, median_elevation: float | None, upper_elevation: float | None, series, nearby) -> dict[str, float | None]:
    out: dict[str, float | None] = {name: None for name in SNOW_FEATURES}
    stations = []
    for code, distance, altitude in nearby.get(canyon_id, []):
        depth = _value(series, code, target_day - 1)
        if math.isnan(depth):
            continue
        stations.append((code, distance, altitude, depth, _value(series, code, target_day - 8)))
    out["snowCount"] = float(len(stations))
    if not stations or upper_elevation is None:
        return out
    best = min(stations, key=lambda s: s[1] / 20.0 + abs(s[2] - upper_elevation) / 300.0)
    out["snowDepth"] = best[3]
    out["snowChange7"] = best[3] - best[4] if not math.isnan(best[4]) else None
    out["snowAltOffset"] = best[2] - upper_elevation
    out["snowDistKm"] = best[1]
    if median_elevation is not None:
        upper = [s[3] for s in stations if s[2] >= median_elevation]
        out["snowUpperMean"] = float(np.mean(upper)) if upper else 0.0
    return out


def build_frame(base: pd.DataFrame, directory: Path, points, cache: Path) -> pd.DataFrame:
    series, nearby, _ = load_stations(directory, points, cache)
    median = base["hydroBand3ElevationM"].replace(F.MISSING, np.nan).to_numpy()
    upper = base["hydroBand5ElevationM"].replace(F.MISSING, np.nan).to_numpy()
    rows = []
    for c, d, m, u in zip(base["canyonId"], base["day"], median, upper):
        rows.append(features_for(int(c), int(d), None if math.isnan(m) else float(m), None if math.isnan(u) else float(u), series, nearby))
    return pd.DataFrame(rows, index=base.index).astype(float)
