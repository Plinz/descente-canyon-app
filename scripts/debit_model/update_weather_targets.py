"""Move the weather targets of canyons whose watershed changed, and give the new targets an ERA5 series.

The app and the training take the weather at the watershed bbox centre (``build_weather_target``),
so a corrected watershed moves the canyon to a new target. Its series is derived from the targets of
the store on the same ERA5 cell (union of their records): same precipitation, temperature moved along the 0.0065 degC/m
lapse rate that Open-Meteo applies between target elevations (measured on the store: median
0.0065, daily spread 0.05 degC). The cell and the elevation of the new target come from a one-week
probe of the archive API. A canyon whose new cell is absent from the store keeps its old target,
unless --fetch-missing-cells fetches the whole record (about 575 weighted API calls per target:
the free Open-Meteo quota allows a few per hour).

Run with PYTHONPATH=scripts/debit_model from the repository root.
"""
from __future__ import annotations

import argparse
import json
import sys
import time
from datetime import date, timedelta
from pathlib import Path

import numpy as np

from fetch_era5_gap import fetch
from weather_store import EPOCH, TargetSeries, WeatherStore

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "debits"))
from pipeline_lib import build_weather_target, load_canyon_lookup, load_geo_points_lookup, load_watershed_lookup  # noqa: E402

LAPSE_RATE_C_PER_M = 0.0065
PROBE_START = "2025-06-01"
PROBE_END = "2025-06-07"


def read_jsonl(path: Path) -> list[dict]:
    return [json.loads(line) for line in path.read_text().splitlines() if line.strip()]


def probe(target: dict, cache_dir: Path, delay_s: float) -> dict:
    cache_path = cache_dir / f"{target['targetId']}.json"
    if cache_path.exists():
        return json.loads(cache_path.read_text())
    for attempt in range(5):
        try:
            payload = fetch(target["latitude"], target["longitude"], PROBE_START, PROBE_END, 60.0)
            break
        except Exception as error:  # noqa: BLE001 - network errors are retried
            print(f"probe {target['targetId']} failed ({error!r}), retrying", flush=True)
            time.sleep(70.0 if "429" in repr(error) else 10.0 * (attempt + 1))
    else:
        raise SystemExit(f"Open-Meteo probe failed for {target['targetId']}")
    cache_dir.mkdir(parents=True, exist_ok=True)
    cache_path.write_text(json.dumps(payload))
    time.sleep(delay_s)
    return payload


def cell_series(target_id: str, references: list[TargetSeries], elevation: float) -> TargetSeries:
    """Series of a new target from the targets of its ERA5 cell, over the union of their records.

    The gap fetches extended only some targets, so the longest record of a cell can stop months before
    another one (seen 2026-10-29: picking the longest alone lost the reports of March to October 2026).
    Where records overlap, the closest elevation wins (smallest temperature shift).
    """
    start = min(series.start_day for series in references)
    length = max(series.end_day for series in references) - start + 1
    precipitation = np.full(length, np.nan)
    temperature = np.full(length, np.nan)
    for series in sorted(references, key=lambda s: abs(s.elevation - elevation)):
        window = slice(series.start_day - start, series.end_day - start + 1)
        missing = np.isnan(precipitation[window])
        precipitation[window][missing] = series.precipitation[missing]
        shifted = series.temperature + LAPSE_RATE_C_PER_M * (series.elevation - elevation)
        missing = np.isnan(temperature[window])
        temperature[window][missing] = shifted[missing]
    return TargetSeries(
        target_id=target_id,
        start_day=start,
        precipitation=precipitation,
        temperature=np.round(temperature, 1),
        elevation=elevation,
        latitude=references[0].latitude,
        longitude=references[0].longitude,
    )


def fetch_full(target: dict, start_day: int, end_day: int) -> dict:
    start = (EPOCH + timedelta(days=start_day)).isoformat()
    end = (EPOCH + timedelta(days=end_day)).isoformat()
    for attempt in range(5):
        try:
            return fetch(target["latitude"], target["longitude"], start, end, 300.0)
        except Exception as error:  # noqa: BLE001
            print(f"fetch {target['targetId']} failed ({error!r}), retrying", flush=True)
            time.sleep(70.0 if "429" in repr(error) else 10.0 * (attempt + 1))
    raise SystemExit(f"Open-Meteo fetch failed for {target['targetId']}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--targets", default="build/debit-pipeline/weather-planning/weather_targets.jsonl")
    parser.add_argument("--canyons", default="offline-data/full/room-import/canyons.json")
    parser.add_argument("--geo-points", default="offline-data/full/room-import/geo_points.json")
    parser.add_argument("--watersheds", default="offline-data/full/room-import/watersheds.json")
    parser.add_argument("--store", default="build/debit-model/weather/era5_daily.npz")
    parser.add_argument("--output-targets", required=True)
    parser.add_argument("--output-store", required=True)
    parser.add_argument("--probe-cache-dir", default="build/debit-model/weather/target-probes")
    parser.add_argument("--delay-s", type=float, default=1.0)
    parser.add_argument("--fetch-missing-cells", action="store_true")
    args = parser.parse_args()

    lines = read_jsonl(Path(args.targets))
    canyons = load_canyon_lookup(Path(args.canyons))
    geo_points = load_geo_points_lookup(Path(args.geo_points))
    watersheds = load_watershed_lookup(Path(args.watersheds))
    store = WeatherStore.load(Path(args.store))

    by_id = {line["targetId"]: line for line in lines}
    moved: list[tuple[int, str, dict]] = []
    for line in lines:
        for canyon_id in line["canyonIds"]:
            canyon = canyons.get(int(canyon_id))
            if canyon is None:
                continue
            target = build_weather_target(
                canyon=canyon,
                geo_points=geo_points.get(int(canyon_id), []),
                watershed=watersheds.get(int(canyon_id)),
            )
            if target is not None and target["targetId"] != line["targetId"]:
                moved.append((int(canyon_id), line["targetId"], target))

    by_cell: dict[tuple[float, float], list[TargetSeries]] = {}
    for series in store.series.values():
        by_cell.setdefault((round(series.latitude, 4), round(series.longitude, 4)), []).append(series)
    record_start = min(series.start_day for series in store.series.values())
    record_end = max(series.end_day for series in store.series.values())

    derived = fetched = 0
    targets_by_new_id = {target["targetId"]: target for _, _, target in moved}
    new_ids = sorted(set(targets_by_new_id) - set(store.series))
    for index, target_id in enumerate(new_ids, start=1):
        target = targets_by_new_id[target_id]
        payload = probe(target, Path(args.probe_cache_dir), args.delay_s)
        cell = (round(float(payload["latitude"]), 4), round(float(payload["longitude"]), 4))
        elevation = float(payload.get("elevation") or 0.0)
        references = by_cell.get(cell, [])
        if references:
            store.series[target_id] = cell_series(target_id, references, elevation)
            derived += 1
        elif args.fetch_missing_cells:
            full = fetch_full(target, record_start, record_end)
            daily = full["daily"]
            days = [(date.fromisoformat(day) - EPOCH).days for day in daily["time"]]
            store.series[target_id] = TargetSeries(
                target_id=target_id,
                start_day=days[0],
                precipitation=np.array([np.nan if v is None else v for v in daily["precipitation_sum"]], dtype=float),
                temperature=np.array([np.nan if v is None else v for v in daily["temperature_2m_mean"]], dtype=float),
                elevation=float(full.get("elevation") or elevation),
                latitude=float(full["latitude"]),
                longitude=float(full["longitude"]),
            )
            by_cell.setdefault(cell, []).append(store.series[target_id])
            fetched += 1
            time.sleep(args.delay_s)
        if index % 100 == 0:
            print(f"{index}/{len(new_ids)} targets (derived={derived} fetched={fetched})", flush=True)

    kept_old = [entry for entry in moved if entry[2]["targetId"] not in store.series]
    moved = [entry for entry in moved if entry[2]["targetId"] in store.series]
    for canyon_id, old_id, target in moved:
        old_line = by_id[old_id]
        old_line["canyonIds"] = [c for c in old_line["canyonIds"] if int(c) != canyon_id]
        old_line["canyonCount"] = len(old_line["canyonIds"])
        new_line = by_id.get(target["targetId"])
        if new_line is None:
            new_line = {**target, "canyonIds": [], "canyonCount": 0}
            by_id[target["targetId"]] = new_line
        new_line["canyonIds"] = sorted({*new_line["canyonIds"], canyon_id})
        new_line["canyonCount"] = len(new_line["canyonIds"])

    output_lines = sorted((line for line in by_id.values() if line["canyonIds"]), key=lambda line: line["canyonId"])
    for line in output_lines:
        line["canyonId"] = line["canyonIds"][0]
    output_targets = Path(args.output_targets)
    output_targets.parent.mkdir(parents=True, exist_ok=True)
    output_targets.write_text("".join(json.dumps(line, ensure_ascii=False) + "\n" for line in output_lines))
    Path(args.output_store).parent.mkdir(parents=True, exist_ok=True)
    store.save(Path(args.output_store))
    print(json.dumps({
        "movedCanyons": len(moved),
        "newTargets": len(new_ids),
        "keptOldTargetNoCell": len(kept_old),
        "derivedFromSameCell": derived,
        "fetchedFullRecord": fetched,
        "targets": len(output_lines),
        "storeTargets": len(store.series),
    }, indent=2))


if __name__ == "__main__":
    main()
