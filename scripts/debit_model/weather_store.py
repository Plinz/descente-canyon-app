"""Compact daily weather store (ERA5 via Open-Meteo) keyed by weather target.

The historical Open-Meteo cache of the debit pipeline is a 7 GB JSONL file. This module converts it
once to a compact ``.npz`` store with one continuous daily series per weather target, and merges
gap-filling fetches (``fetch_era5_gap.py``) on top of it.

Only precipitation and mean 2 m temperature are kept: they are the only daily inputs of the
hydrological model (``hydro.py``), which keeps the runtime contract with the Android app minimal.
"""
from __future__ import annotations

import argparse
import json
from dataclasses import dataclass
from datetime import date
from pathlib import Path

import numpy as np

EPOCH = date(1970, 1, 1)


def epoch_day(iso: str) -> int:
    return (date.fromisoformat(iso) - EPOCH).days


@dataclass
class TargetSeries:
    target_id: str
    start_day: int  # epoch day of the first value
    precipitation: np.ndarray  # mm/day, NaN when missing
    temperature: np.ndarray  # degC, NaN when missing
    elevation: float
    latitude: float
    longitude: float

    @property
    def end_day(self) -> int:
        return self.start_day + len(self.precipitation) - 1


class WeatherStore:
    def __init__(self, series: dict[str, TargetSeries]):
        self.series = series

    def get(self, target_id: str) -> TargetSeries | None:
        return self.series.get(target_id)

    def save(self, path: Path) -> None:
        ids = sorted(self.series)
        lengths = np.array([len(self.series[i].precipitation) for i in ids], dtype=np.int64)
        np.savez_compressed(
            path,
            target_ids=np.array(ids),
            start_days=np.array([self.series[i].start_day for i in ids], dtype=np.int64),
            lengths=lengths,
            elevations=np.array([self.series[i].elevation for i in ids], dtype=np.float64),
            latitudes=np.array([self.series[i].latitude for i in ids], dtype=np.float64),
            longitudes=np.array([self.series[i].longitude for i in ids], dtype=np.float64),
            precipitation=np.concatenate([self.series[i].precipitation for i in ids]).astype(np.float32),
            temperature=np.concatenate([self.series[i].temperature for i in ids]).astype(np.float32),
        )

    @classmethod
    def load(cls, path: Path) -> "WeatherStore":
        # NpzFile decompresses on every key access: read each array exactly once.
        with np.load(path) as npz:
            data = {key: npz[key] for key in npz.files}
        precipitation = data["precipitation"].astype(np.float64)
        temperature = data["temperature"].astype(np.float64)
        series: dict[str, TargetSeries] = {}
        offset = 0
        for index, target_id in enumerate(data["target_ids"]):
            length = int(data["lengths"][index])
            series[str(target_id)] = TargetSeries(
                target_id=str(target_id),
                start_day=int(data["start_days"][index]),
                precipitation=precipitation[offset:offset + length],
                temperature=temperature[offset:offset + length],
                elevation=float(data["elevations"][index]),
                latitude=float(data["latitudes"][index]),
                longitude=float(data["longitudes"][index]),
            )
            offset += length
        return cls(series)


def _series_from_rows(target_id: str, rows: list[tuple[int, float | None, float | None]], elevation: float, latitude: float, longitude: float) -> TargetSeries:
    rows.sort()
    start = rows[0][0]
    length = rows[-1][0] - start + 1
    precipitation = np.full(length, np.nan)
    temperature = np.full(length, np.nan)
    for day, p, t in rows:
        if p is not None:
            precipitation[day - start] = p
        if t is not None:
            temperature[day - start] = t
    return TargetSeries(target_id, start, precipitation, temperature, elevation, latitude, longitude)


def convert_archive(archive_path: Path) -> WeatherStore:
    rows_by_target: dict[str, list[tuple[int, float | None, float | None]]] = {}
    meta: dict[str, tuple[float, float, float]] = {}
    with archive_path.open("r", encoding="utf-8") as handle:
        for line_number, line in enumerate(handle):
            row = json.loads(line)
            target_id = row["targetId"]
            rows_by_target.setdefault(target_id, []).append(
                (epoch_day(row["date"]), row.get("precipitation_sum"), row.get("temperature_2m_mean"))
            )
            if target_id not in meta:
                meta[target_id] = (
                    float(row.get("resolvedElevation") or 0.0),
                    float(row.get("resolvedLatitude") or 0.0),
                    float(row.get("resolvedLongitude") or 0.0),
                )
            if line_number % 2_000_000 == 0:
                print(f"read {line_number} rows", flush=True)
    return WeatherStore({
        target_id: _series_from_rows(target_id, rows, *meta[target_id])
        for target_id, rows in rows_by_target.items()
    })


def merge_gap_files(store: WeatherStore, gap_dir: Path) -> dict[str, float]:
    """Append gap-fetch payloads to the store. Returns overlap agreement statistics."""
    diffs: list[float] = []
    merged = 0
    for path in sorted(gap_dir.glob("*.json")):
        payload = json.loads(path.read_text())
        target_id = payload["targetId"]
        daily = payload.get("daily") or {}
        new_rows = [
            (epoch_day(day), p, t)
            for day, p, t in zip(daily.get("time", []), daily.get("precipitation_sum", []), daily.get("temperature_2m_mean", []))
        ]
        existing = store.get(target_id)
        if existing is None:
            store.series[target_id] = _series_from_rows(
                target_id, new_rows, float(payload.get("elevation") or 0.0),
                float(payload.get("latitude") or 0.0), float(payload.get("longitude") or 0.0),
            )
            merged += 1
            continue
        rows = [
            (existing.start_day + i, None if np.isnan(p) else float(p), None if np.isnan(t) else float(t))
            for i, (p, t) in enumerate(zip(existing.precipitation, existing.temperature))
        ]
        by_day = {day: index for index, (day, _, _) in enumerate(rows)}
        for day, p, t in new_rows:
            if day not in by_day:
                rows.append((day, p, t))
                continue
            index = by_day[day]
            _, old_p, old_t = rows[index]
            if p is not None and old_p is not None:
                diffs.append(abs(old_p - p))
            # fill days the store does not know yet (missing values), keep existing ones
            rows[index] = (day, old_p if old_p is not None else p, old_t if old_t is not None else t)
        store.series[target_id] = _series_from_rows(
            target_id, rows, existing.elevation, existing.latitude, existing.longitude
        )
        merged += 1
    return {
        "mergedTargets": merged,
        "overlapDays": len(diffs),
        "overlapMeanAbsPrecipDiffMm": float(np.mean(diffs)) if diffs else 0.0,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive-path", default="build/debit-pipeline/weather-archive/weather_daily_rows.jsonl")
    parser.add_argument("--gap-dir", action="append", default=[])
    parser.add_argument("--base-store", help="Existing store to merge gap files into instead of converting the archive")
    parser.add_argument("--output", default="build/debit-model/weather/era5_daily.npz")
    args = parser.parse_args()

    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    store = WeatherStore.load(Path(args.base_store)) if args.base_store else convert_archive(Path(args.archive_path))
    for gap_dir in args.gap_dir:
        print(json.dumps(merge_gap_files(store, Path(gap_dir))))
    store.save(output)
    missing = sum(int(np.isnan(s.precipitation).sum()) for s in store.series.values())
    print(f"wrote {output} targets={len(store.series)} missingPrecipDays={missing}")


if __name__ == "__main__":
    main()
