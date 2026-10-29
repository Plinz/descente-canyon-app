"""Build the v3 debit training dataset.

Output (``--output-dir``):
* ``base.pkl``: one row per usable observation with label, keys, time, static and weather/hydro
  features. History features are *not* included because massif/region priors depend on which
  observations are known (per walk-forward fold); ``history.py`` adds them.
* ``canyon_static.json``: static feature values per canyon (also exported to the app).
* ``canyon_history.pkl``: (epochDay, rank) report lists per canyon.
* ``metadata.json``.
"""
from __future__ import annotations

import argparse
import json
import math
import time
from collections import defaultdict
from datetime import date
from pathlib import Path

import numpy as np
import pandas as pd

import features as F
import hydro
import meteofrance as M
import meteoswiss as W
import neighbors as N
from label_quality import is_message_only_high_report
from weather_store import WeatherStore

DEFAULT_OBSERVATIONS = "build/debit-pipeline/descente-refresh-2026-10-13/valid_debit_observations.jsonl"
# History mirrors what the app scrapes: every published report (valid, uncertain and invalid).
DEFAULT_HISTORY_OBSERVATIONS = "build/debit-pipeline/descente-refresh-2026-10-13/all_debit_observations.jsonl"
# Canyons that became forbidden no longer publish their reports; their past reports (previous scrape)
# remain genuine labels and are shipped as lookup history (the app cannot scrape them either).
DEFAULT_ARCHIVED_OBSERVATIONS = "build/debit-pipeline/descente-refresh-2026-06-05-reviewed/valid_debit_observations.jsonl"
DEFAULT_ARCHIVED_HISTORY = "build/debit-pipeline/descente-refresh-2026-06-05-reviewed/all_debit_observations.jsonl"


def with_archived(current: list[dict], archived: list[dict]) -> tuple[list[dict], list[int]]:
    """Add archived reports of canyons that have no report at all in the current scrape."""
    present = {row["canyonId"] for row in current}
    restored = sorted({row["canyonId"] for row in archived} - present)
    restored_set = set(restored)
    return current + [row for row in archived if row["canyonId"] in restored_set], restored
DEFAULT_CANYONS = "offline-data/full/room-import/canyons.json"
DEFAULT_STATIC = "build/debit-model/source/canyon_static_descriptors-2026-10-29.json"
DEFAULT_TARGETS = "build/debit-pipeline/weather-planning-2026-10-29/weather_targets.jsonl"
DEFAULT_WEATHER = "build/debit-model/weather/era5_daily-2026-10-29.npz"


def load_observations(path: Path) -> list[dict]:
    """Reports usable as labels/history: known level and date, message-only high reports removed
    (unless a manual review validated them)."""
    rows = []
    with path.open("r", encoding="utf-8") as handle:
        for line in handle:
            row = json.loads(line)
            level = row.get("niveau")
            if level not in F.RANK_BY_LEVEL or not row.get("date"):
                continue
            if not row.get("manualOverride") and is_message_only_high_report(
                F.RANK_BY_LEVEL[level], row.get("isDescended"), row.get("comment")
            ):
                continue
            rows.append({
                "observationId": row["observationId"],
                "canyonId": int(row["canyonId"]),
                "day": F.to_epoch_day(date.fromisoformat(row["date"])),
                "rank": F.RANK_BY_LEVEL[level],
                "isDescended": row.get("isDescended"),
            })
    return rows


def canyon_targets(path: Path) -> dict[int, str]:
    mapping: dict[int, str] = {}
    for line in path.read_text().splitlines():
        if line.strip():
            target = json.loads(line)
            for canyon_id in target["canyonIds"]:
                mapping[int(canyon_id)] = target["targetId"]
    return mapping


def build_static(canyons: dict[int, dict], static: dict[str, dict], targets: dict[int, str], store: WeatherStore) -> dict[int, dict]:
    """Static features for every known canyon (climatology filled later)."""
    output = {}
    for canyon_id, canyon in canyons.items():
        series = store.get(targets[canyon_id]) if canyon_id in targets else None
        output[canyon_id] = F.static_feature_values(
            static.get(str(canyon_id), {}),
            canyon,
            series.latitude if series else None,
            series.longitude if series else None,
            series.elevation if series else None,
            None,
        )
    return output


def compute_climatology(canyon_ids: list[int], static_values: dict[int, dict], targets: dict[int, str], store: WeatherStore) -> dict[int, dict]:
    rows = [c for c in canyon_ids if c in targets and store.get(targets[c]) is not None]
    series = [store.get(targets[c]) for c in rows]
    start = min(s.start_day for s in series)
    end = max(s.end_day for s in series)
    days = end - start + 1
    n = len(rows)
    print(f"climatology: {n} canyons x {days} days", flush=True)
    precipitation = np.full((n, days), np.nan)
    temperature = np.full((n, days), np.nan)
    valid_from = np.zeros(n, dtype=np.int64)
    for index, s in enumerate(series):
        offset = s.start_day - start
        precipitation[index, offset:offset + len(s.precipitation)] = s.precipitation
        temperature[index, offset:offset + len(s.temperature)] = s.temperature
        valid_from[index] = offset + hydro.WINDOW_DAYS
    p, t, _ = hydro.fill_window(precipitation, temperature)
    statics = [static_values[c] for c in rows]
    stats = F.climatology_from_series(
        p, t,
        np.full(n, start, dtype=np.int64),
        valid_from,
        np.array([s.latitude for s in series]),
        np.array([s.elevation for s in series]),
        np.array([F.band_elevations(v) for v in statics]),
        np.array([float(v["hydroSoilCapacityMm"]) for v in statics]),
    )
    return {canyon_id: entry for canyon_id, entry in zip(rows, stats) if entry is not None}


def history_lists(observations: list[dict]) -> dict[int, list[tuple[int, int]]]:
    lists: dict[int, list[tuple[int, int]]] = defaultdict(list)
    for row in observations:
        lists[row["canyonId"]].append((row["day"], row["rank"]))
    for values in lists.values():
        values.sort()
    return dict(lists)


def last_report_before(history: list[tuple[int, int]], day: int) -> float:
    """Days since the latest report strictly before ``day`` (NaN if none)."""
    import bisect
    index = bisect.bisect_left(history, (day, -1))
    if index == 0:
        return math.nan
    return float(day - history[index - 1][0])


def build_rows(observations, canyons, static_values, targets, store, histories, batch_size: int = 20000):
    usable = []
    for row in observations:
        canyon = canyons.get(row["canyonId"])
        target_id = targets.get(row["canyonId"])
        if canyon is None or target_id is None or store.get(target_id) is None:
            continue
        usable.append(row)
    print(f"usable observations with weather target: {len(usable)} / {len(observations)}", flush=True)

    frames = []
    dropped = 0
    window = hydro.WINDOW_DAYS
    for start in range(0, len(usable), batch_size):
        batch = usable[start:start + batch_size]
        n = len(batch)
        precipitation = np.full((n, window), np.nan)
        temperature = np.full((n, window), np.nan)
        for index, row in enumerate(batch):
            series = store.get(targets[row["canyonId"]])
            first = row["day"] - window + 1 - series.start_day
            lo, hi = max(first, 0), min(first + window, len(series.precipitation))
            if hi > lo:
                precipitation[index, lo - first:hi - first] = series.precipitation[lo:hi]
                temperature[index, lo - first:hi - first] = series.temperature[lo:hi]
        p, t, valid_days = hydro.fill_window(precipitation, temperature)
        keep = valid_days >= hydro.MIN_VALID_DAYS
        # the target day itself must be observed (it carries the day-0 rain)
        keep &= ~np.isnan(precipitation[:, -1])
        dropped += int((~keep).sum())
        batch = [row for row, k in zip(batch, keep) if k]
        p, t = p[keep], t[keep]
        if not batch:
            continue
        statics = [static_values[row["canyonId"]] for row in batch]
        series_list = [store.get(targets[row["canyonId"]]) for row in batch]
        log_mean = np.array([[s.get(f"hydroLog{name}Mean") or np.nan for name in F.RESERVOIR_NAMES] for s in statics], dtype=float)
        log_std = np.array([[s.get(f"hydroLog{name}Std") or np.nan for name in F.RESERVOIR_NAMES] for s in statics], dtype=float)
        since = np.array([last_report_before(histories[row["canyonId"]], row["day"]) for row in batch])
        hydro_values = F.hydro_features(
            p, t,
            np.array([row["day"] for row in batch], dtype=np.int64),
            np.array([s.latitude for s in series_list]),
            np.array([s.elevation for s in series_list]),
            np.array([F.band_elevations(s) for s in statics]),
            np.array([float(s["hydroSoilCapacityMm"]) for s in statics]),
            since, log_mean, log_std,
        )
        frame = pd.DataFrame({name: np.asarray(values, dtype=float) for name, values in hydro_values.items()})
        frame["observationId"] = [row["observationId"] for row in batch]
        frame["canyonId"] = [row["canyonId"] for row in batch]
        frame["day"] = [row["day"] for row in batch]
        frame["rank"] = [row["rank"] for row in batch]
        frame["isDescended"] = [row["isDescended"] for row in batch]
        frame["massif"] = [canyons[row["canyonId"]].get("massif") or "__UNKNOWN__" for row in batch]
        frame["region"] = [canyons[row["canyonId"]].get("region") or "__UNKNOWN__" for row in batch]
        time_values = [F.time_features(F.from_epoch_day(row["day"])) for row in batch]
        for name in F.TIME_FEATURES:
            frame[name] = [values[name] for values in time_values]
        for name in F.STATIC_FEATURES:
            frame[name] = [F.finalize_value(s.get(name)) for s in statics]
        frames.append(frame)
        print(f"rows {start + n}/{len(usable)} dropped={dropped}", flush=True)
    data = pd.concat(frames, ignore_index=True)
    return data, dropped


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--observations", default=DEFAULT_OBSERVATIONS)
    parser.add_argument("--history-observations", default=DEFAULT_HISTORY_OBSERVATIONS)
    parser.add_argument("--archived-observations", default=DEFAULT_ARCHIVED_OBSERVATIONS)
    parser.add_argument("--archived-history", default=DEFAULT_ARCHIVED_HISTORY)
    parser.add_argument("--geo-points", default="offline-data/full/room-import/geo_points.json")
    parser.add_argument("--meteofrance-dir", default="build/debit-model/meteofrance")
    parser.add_argument("--meteofrance-cache", default="build/debit-model/meteofrance-cache/stations.pkl")
    parser.add_argument("--meteoswiss-dir", default="build/debit-model/meteoswiss")
    parser.add_argument("--rain-cache", default="build/debit-model/meteofrance-cache/rain_stations_fr_ch.pkl")
    parser.add_argument("--hourly-stations", default="build/debit-model/meteofrance-cache/hourly_stations.json")
    parser.add_argument("--canyons", default=DEFAULT_CANYONS)
    parser.add_argument("--static", default=DEFAULT_STATIC)
    parser.add_argument("--targets", default=DEFAULT_TARGETS)
    parser.add_argument("--weather", default=DEFAULT_WEATHER)
    parser.add_argument("--output-dir", default="build/debit-model/dataset")
    args = parser.parse_args()

    started = time.time()
    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    observations, restored = with_archived(load_observations(Path(args.observations)), load_observations(Path(args.archived_observations)))
    print(f"restored archived reports for {len(restored)} canyons without published reports: {restored}", flush=True)
    canyons = {int(row["id"]): row for row in json.loads(Path(args.canyons).read_text())}
    static = json.loads(Path(args.static).read_text())
    targets = canyon_targets(Path(args.targets))
    store = WeatherStore.load(Path(args.weather))
    print(f"loaded {len(observations)} observations, {len(canyons)} canyons, {len(store.series)} weather targets", flush=True)

    static_values = build_static(canyons, static, targets, store)
    climatology = compute_climatology(sorted(canyons), static_values, targets, store)
    for canyon_id, entry in climatology.items():
        static_values[canyon_id].update(entry)
    print(f"climatology for {len(climatology)} canyons ({time.time() - started:.0f}s)", flush=True)

    history_rows, _ = with_archived(load_observations(Path(args.history_observations)), load_observations(Path(args.archived_history)))
    histories = history_lists(history_rows)
    data, dropped = build_rows(observations, canyons, static_values, targets, store, histories)
    points = N.canyon_points(Path(args.geo_points), Path(args.targets))
    if not Path(args.meteofrance_cache).exists():
        import meteofrance as M0
        M0.load_stations(Path(args.meteofrance_dir), points, Path(args.meteofrance_cache))
    hourly_codes = {code for codes in json.loads(Path(args.hourly_stations).read_text()).values() for code in codes}

    def realtime(lag: int) -> pd.DataFrame:
        """Real-time features known ``lag`` days before the morning of J (see features.py)."""
        frame = pd.DataFrame({"observationId": data["observationId"].to_numpy()})
        neighbor_values = N.build_frame(data, [Path(args.history_observations)], Path(args.geo_points), Path(args.targets), np.full(len(data), lag))
        shifted = data[["canyonId", "day"]].copy()
        shifted["day"] = shifted["day"] - lag - 1
        # Météo-France + MeteoSwiss gauges as one network (closest gauges whatever the country).
        rain_values = W.build_frame(shifted, Path(args.meteofrance_cache), Path(args.meteoswiss_dir), points, Path(args.rain_cache))
        series, nearby = W.combined_network(Path(args.meteofrance_cache), Path(args.meteoswiss_dir), points, Path(args.rain_cache))
        auto_rows = [M.auto_features(int(c), int(d) - lag, series, nearby, hourly_codes) for c, d in zip(data["canyonId"], data["day"])]
        auto_values = pd.DataFrame(auto_rows)
        for name in F.NEIGHBOR_FEATURES:
            frame[name] = neighbor_values[name].to_numpy()
        for name in F.RAIN_FEATURES:
            frame[name] = rain_values[name].to_numpy()
        for name in F.AUTO_RAIN_FEATURES:
            frame[name] = auto_values[name].astype(float).to_numpy()
        frame["infoLagDays"] = float(lag)
        return frame

    current = realtime(0)
    for name in F.REALTIME_FEATURES + F.INFO_FEATURES:
        data[name] = current[name].to_numpy()
    print(f"real-time features added ({time.time() - started:.0f}s)", flush=True)
    for lag in (1, 2):
        realtime(lag).to_pickle(output_dir / f"realtime_lag{lag}.pkl")
        print(f"lag {lag} real-time features ({time.time() - started:.0f}s)", flush=True)
    data.to_pickle(output_dir / "base.pkl")
    pd.to_pickle(histories, output_dir / "canyon_history.pkl")
    (output_dir / "canyon_static.json").write_text(json.dumps({str(k): v for k, v in static_values.items()}))
    (output_dir / "metadata.json").write_text(json.dumps({
        "observations": args.observations,
        "observationCount": len(observations),
        "rowCount": int(len(data)),
        "droppedForWeather": dropped,
        "climatologyCanyonCount": len(climatology),
        "firstDay": str(F.from_epoch_day(int(data["day"].min()))),
        "lastDay": str(F.from_epoch_day(int(data["day"].max()))),
        "seconds": round(time.time() - started),
    }, indent=2))
    print(f"wrote {len(data)} rows to {output_dir} in {time.time() - started:.0f}s")


if __name__ == "__main__":
    main()
