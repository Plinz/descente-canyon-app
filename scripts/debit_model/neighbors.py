"""Recent reports of neighbouring canyons ("crowd nowcast").

At runtime the app reads the site's "derniers débits" page: one level per canyon and per day for the
last 16 days (the lowest level when several reports exist the same day). Training reproduces it from
every published report (raw, unfiltered: the page shows them all).

For a target (canyon c, day J), neighbour cells are (canyon n != c, day d) with n within
``RADIUS_KM`` and J - window <= d <= J - 1. Each cell's anomaly is its level minus the neighbour's
usual level for that season (causal: reports of n before d, smoothed towards 2.0).
"""
from __future__ import annotations

import bisect
import json
import math
from collections import defaultdict
from pathlib import Path

import numpy as np
import pandas as pd

import features as F

RADIUS_KM = 30.0
CLOSE_KM = 10.0
PRIOR_STRENGTH = 3.0
PRIOR_MEAN = 2.0
POINT_PREFERENCE = ["ENTREE", "PARKING_AMONT", "SORTIE", "PARKING_AVAL", "POINT_REMARQUABLE"]

NEIGHBOR_FEATURES = F.NEIGHBOR_FEATURES


def canyon_points(geo_points_path: Path, targets_path: Path | None = None) -> dict[int, tuple[float, float]]:
    by_canyon: dict[int, dict[str, tuple[float, float]]] = {}
    for point in json.loads(geo_points_path.read_text()):
        if point.get("latitude") is None or point.get("longitude") is None:
            continue
        by_canyon.setdefault(int(point["canyonId"]), {}).setdefault(point.get("type") or "", (point["latitude"], point["longitude"]))
    output: dict[int, tuple[float, float]] = {}
    for canyon_id, points in by_canyon.items():
        output[canyon_id] = next((points[k] for k in POINT_PREFERENCE if k in points), next(iter(points.values())))
    if targets_path is not None and targets_path.exists():
        for line in targets_path.read_text().splitlines():
            if line.strip():
                target = json.loads(line)
                for canyon_id in target["canyonIds"]:
                    output.setdefault(int(canyon_id), (target["latitude"], target["longitude"]))
    return output


def neighbor_table(points: dict[int, tuple[float, float]], radius_km: float = RADIUS_KM) -> dict[int, list[tuple[int, float]]]:
    ids = np.array(sorted(points))
    lat = np.radians(np.array([points[i][0] for i in ids]))
    lon = np.radians(np.array([points[i][1] for i in ids]))
    table: dict[int, list[tuple[int, float]]] = {}
    for index, canyon_id in enumerate(ids):
        dlat = lat - lat[index]
        dlon = lon - lon[index]
        a = np.sin(dlat / 2) ** 2 + np.cos(lat[index]) * np.cos(lat) * np.sin(dlon / 2) ** 2
        distance = 6371.0 * 2 * np.arcsin(np.sqrt(np.clip(a, 0, 1)))
        close = np.where((distance <= radius_km) & (ids != canyon_id))[0]
        table[int(canyon_id)] = sorted(((int(ids[j]), round(float(distance[j]), 3)) for j in close), key=lambda x: x[1])
    return table


def day_cells(raw_reports: list[tuple[int, int, int]]) -> dict[int, list[tuple[int, int]]]:
    """(canyon, day, rank) -> canyon: sorted [(day, level)] with the page rule (lowest level of the day)."""
    cells: dict[tuple[int, int], int] = {}
    for canyon_id, day, rank in raw_reports:
        key = (canyon_id, day)
        cells[key] = min(rank, cells.get(key, 9))
    by_canyon: dict[int, list[tuple[int, int]]] = defaultdict(list)
    for (canyon_id, day), level in cells.items():
        by_canyon[canyon_id].append((day, level))
    for values in by_canyon.values():
        values.sort()
    return dict(by_canyon)


def seasonal_mean(counts: np.ndarray, sums: np.ndarray, month_index: int) -> float:
    """Smoothed mean level over the month (0-based) and its neighbours (weights 1, 0.5)."""
    weight = total = 0.0
    for offset, w in ((-1, 0.5), (0, 1.0), (1, 0.5)):
        m = (month_index + offset) % 12
        weight += w * counts[m]
        total += w * sums[m]
    return (total + PRIOR_STRENGTH * PRIOR_MEAN) / (weight + PRIOR_STRENGTH)


class FrozenSeasonalMeans:
    """Runtime variant: monthly aggregates frozen at export time (shipped in the lookups)."""

    def __init__(self, aggregates: dict[int, tuple[list[float], list[float]]]):
        self.aggregates = aggregates

    def mean(self, canyon_id: int, day: int) -> float:
        entry = self.aggregates.get(canyon_id)
        if entry is None:
            return PRIOR_MEAN
        return seasonal_mean(np.asarray(entry[0], dtype=float), np.asarray(entry[1], dtype=float), F.from_epoch_day(day).month - 1)


def monthly_aggregates(cells: dict[int, list[tuple[int, int]]]) -> dict[int, tuple[list[float], list[float]]]:
    output = {}
    for canyon_id, values in cells.items():
        counts = [0.0] * 12
        sums = [0.0] * 12
        for day, level in values:
            month = F.from_epoch_day(day).month - 1
            counts[month] += 1
            sums[month] += level
        output[canyon_id] = (counts, sums)
    return output


class SeasonalMeans:
    """Causal smoothed seasonal mean level of each canyon (month and neighbours weights 1, 0.5)."""

    def __init__(self, cells: dict[int, list[tuple[int, int]]]):
        self.days: dict[int, list[int]] = {}
        self.prefix: dict[int, np.ndarray] = {}
        for canyon_id, values in cells.items():
            months = np.array([F.from_epoch_day(d).month - 1 for d, _ in values])
            levels = np.array([lv for _, lv in values], dtype=float)
            counts = np.zeros((len(values) + 1, 12))
            sums = np.zeros((len(values) + 1, 12))
            for i, (m, lv) in enumerate(zip(months, levels)):
                counts[i + 1] = counts[i]
                sums[i + 1] = sums[i]
                counts[i + 1, m] += 1
                sums[i + 1, m] += lv
            self.days[canyon_id] = [d for d, _ in values]
            self.prefix[canyon_id] = np.stack([counts, sums])

    def mean(self, canyon_id: int, day: int) -> float:
        days = self.days.get(canyon_id)
        if not days:
            return PRIOR_MEAN
        index = bisect.bisect_left(days, day)  # reports strictly before ``day``
        counts, sums = self.prefix[canyon_id][0][index], self.prefix[canyon_id][1][index]
        return seasonal_mean(counts, sums, F.from_epoch_day(day).month - 1)


def features_for(
    canyon_id: int,
    target_day: int,
    neighbors: dict[int, list[tuple[int, float]]],
    cells: dict[int, list[tuple[int, int]]],
    seasonal,
    lag_days: int = 0,
) -> dict[str, float | None]:
    """Neighbour features; ``lag_days`` shifts the window end (reports known up to J - 1 - lag)."""
    last = target_day - 1 - lag_days
    acc = {3: [], 7: []}
    for neighbor_id, distance in neighbors.get(canyon_id, []):
        values = cells.get(neighbor_id)
        if not values:
            continue
        lo = bisect.bisect_left(values, (target_day - 7, -1))
        hi = bisect.bisect_right(values, (last, 99))
        for day, level in values[lo:hi]:
            anomaly = level - seasonal.mean(neighbor_id, day)
            weight = 1.0 / (1.0 + distance / 10.0)
            acc[7].append((level, anomaly, weight, distance))
            if day >= target_day - 3:
                acc[3].append((level, anomaly, weight, distance))
    out: dict[str, float | None] = {}
    for window in (3, 7):
        cells_w = acc[window]
        out[f"nb{window}Count"] = float(len(cells_w))
        if cells_w:
            w = np.array([c[2] for c in cells_w])
            levels = np.array([c[0] for c in cells_w], dtype=float)
            anomalies = np.array([c[1] for c in cells_w])
            out[f"nb{window}MeanAnom"] = float(np.sum(w * anomalies) / np.sum(w))
            out[f"nb{window}FracHigh"] = float(np.sum(w * (levels >= 3)) / np.sum(w))
            if window == 3:
                out["nb3MaxAnom"] = float(anomalies.max())
                out["nb3MeanLevel"] = float(np.sum(w * levels) / np.sum(w))
                close = [c for c in cells_w if c[3] <= CLOSE_KM]
                out["nbClose3MeanAnom"] = float(np.mean([c[1] for c in close])) if close else None
                out["nbNearest3Km"] = float(min(c[3] for c in cells_w))
        else:
            out[f"nb{window}MeanAnom"] = None
            out[f"nb{window}FracHigh"] = None
            if window == 3:
                out["nb3MaxAnom"] = None
                out["nb3MeanLevel"] = None
                out["nbClose3MeanAnom"] = None
                out["nbNearest3Km"] = None
    return out


NEIGHBOR_V2_FEATURES = ["nb15Count", "nb15MeanAnom", "nb15FracHigh", "nbLastAnom", "nbLastAge"]


def features_v2(canyon_id: int, target_day: int, neighbors: dict, cells: dict, seasonal) -> dict[str, float | None]:
    """Whole page window (15 days) and the most recent neighbour cell (experiment)."""
    window = []
    for neighbor_id, distance in neighbors.get(canyon_id, []):
        values = cells.get(neighbor_id)
        if not values:
            continue
        lo = bisect.bisect_left(values, (target_day - 15, -1))
        hi = bisect.bisect_right(values, (target_day - 1, 99))
        for day, level in values[lo:hi]:
            window.append((day, level, level - seasonal.mean(neighbor_id, day), 1.0 / (1.0 + distance / 10.0)))
    out: dict[str, float | None] = {"nb15Count": float(len(window)), "nb15MeanAnom": None, "nb15FracHigh": None, "nbLastAnom": None, "nbLastAge": None}
    if window:
        w = np.array([c[3] for c in window])
        out["nb15MeanAnom"] = float(np.sum(w * np.array([c[2] for c in window])) / w.sum())
        out["nb15FracHigh"] = float(np.sum(w * (np.array([c[1] for c in window]) >= 3)) / w.sum())
        last_day = max(c[0] for c in window)
        latest = [c for c in window if c[0] == last_day]
        out["nbLastAnom"] = float(np.mean([c[2] for c in latest]))
        out["nbLastAge"] = float(target_day - last_day)
    return out


def build_frame_v2(base: pd.DataFrame, raw_paths: list[Path], geo_points: Path, targets: Path) -> pd.DataFrame:
    points = canyon_points(geo_points, targets)
    neighbors = neighbor_table(points)
    cells = day_cells(load_raw_reports(raw_paths))
    seasonal = SeasonalMeans(cells)
    rows = [features_v2(int(c), int(d), neighbors, cells, seasonal) for c, d in zip(base["canyonId"], base["day"])]
    return pd.DataFrame(rows, index=base.index).astype(float)


def load_raw_reports(paths: list[Path]) -> list[tuple[int, int, int]]:
    seen = set()
    reports = []
    for path in paths:
        with path.open("r", encoding="utf-8") as handle:
            for line in handle:
                row = json.loads(line)
                rank = F.RANK_BY_LEVEL.get(row.get("niveau"))
                if rank is None or not row.get("date") or row["observationId"] in seen:
                    continue
                seen.add(row["observationId"])
                reports.append((int(row["canyonId"]), F.to_epoch_day(pd.Timestamp(row["date"]).date()), rank))
    return reports


def build_frame(base: pd.DataFrame, raw_paths: list[Path], geo_points: Path, targets: Path, lag_days: np.ndarray | None = None, radius_km: float = RADIUS_KM) -> pd.DataFrame:
    points = canyon_points(geo_points, targets)
    neighbors = neighbor_table(points, radius_km)
    cells = day_cells(load_raw_reports(raw_paths))
    seasonal = SeasonalMeans(cells)
    lags = np.zeros(len(base), dtype=int) if lag_days is None else lag_days
    rows = [
        features_for(int(c), int(d), neighbors, cells, seasonal, int(lag))
        for c, d, lag in zip(base["canyonId"], base["day"], lags)
    ]
    frame = pd.DataFrame(rows, index=base.index)
    return frame.astype(float)
