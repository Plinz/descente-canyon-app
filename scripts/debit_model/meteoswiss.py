"""MeteoSwiss daily precipitation (``rre150d0``, 06-06 UTC like Météo-France RR) as rain gauges.

Swiss stations join the Météo-France gauges in a single network: the rain gauge features
(``meteofrance.features_for``) use the closest gauges whatever their country.
"""
from __future__ import annotations

import csv
import io
import json
import math
from datetime import datetime
from pathlib import Path

import numpy as np
import pandas as pd

import features as F
import meteofrance as M

START_DAY = F.to_epoch_day(datetime(2004, 1, 1).date())


def parse_daily(raw: bytes) -> dict[int, float]:
    values: dict[int, float] = {}
    if not raw:
        return values
    for row in csv.DictReader(io.StringIO(raw.decode("latin-1")), delimiter=";"):
        text = (row.get("rre150d0") or "").strip()
        if not text:
            continue
        try:
            day = F.to_epoch_day(datetime.strptime(row["reference_timestamp"][:10], "%d.%m.%Y").date())
            values[day] = float(text)
        except (KeyError, ValueError):
            continue
    return values


def load_series(directory: Path) -> tuple[dict, dict]:
    stations = json.loads((directory / "stations.json").read_text())
    series, meta = {}, {}
    for station in stations:
        values: dict[int, float] = {}
        for period in ("historical", "recent"):
            path = directory / f"{station['code']}_{period}.csv"
            if path.exists():
                values.update(parse_daily(path.read_bytes()))
        values = {d: v for d, v in values.items() if d >= START_DAY}
        if len(values) < 365:
            continue
        start = min(values)
        array = np.full(max(values) - start + 1, np.nan)
        for d, v in values.items():
            array[d - start] = v
        series[station["code"]] = (start, array)
        meta[station["code"]] = (station["lat"], station["lon"], station["alt"])
    return series, meta


def combined_network(france_cache: Path, swiss_dir: Path, points: dict[int, tuple[float, float]], output_cache: Path):
    """Merge Météo-France and MeteoSwiss gauges and recompute the nearby lists of every canyon."""
    if output_cache.exists():
        payload = pd.read_pickle(output_cache)
        return payload["series"], payload["nearby"]
    france = pd.read_pickle(france_cache)
    series = dict(france["series"])
    meta = dict(france["meta"])
    swiss_series, swiss_meta = load_series(swiss_dir)
    series.update(swiss_series)
    meta.update(swiss_meta)
    codes = list(meta)
    lat_s = np.radians(np.array([meta[c][0] for c in codes]))
    lon_s = np.radians(np.array([meta[c][1] for c in codes]))
    nearby = {}
    for canyon_id, (lat, lon) in points.items():
        la, lo = math.radians(lat), math.radians(lon)
        a = np.sin((lat_s - la) / 2) ** 2 + math.cos(la) * np.cos(lat_s) * np.sin((lon_s - lo) / 2) ** 2
        distance = 6371.0 * 2 * np.arcsin(np.sqrt(np.clip(a, 0, 1)))
        order = np.argsort(distance)
        nearby[canyon_id] = [(codes[i], round(float(distance[i]), 3)) for i in order[: M.MAX_STATIONS] if distance[i] <= M.RADIUS_KM]
    output_cache.parent.mkdir(parents=True, exist_ok=True)
    pd.to_pickle({"series": series, "nearby": nearby, "meta": meta}, output_cache)
    print(f"combined gauges: {len(series)} ({len(swiss_series)} Swiss)", flush=True)
    return series, nearby


def build_frame(base: pd.DataFrame, france_cache: Path, swiss_dir: Path, points, output_cache: Path) -> pd.DataFrame:
    series, nearby = combined_network(france_cache, swiss_dir, points, output_cache)
    rows = [M.features_for(int(c), int(d), series, nearby) for c, d in zip(base["canyonId"], base["day"])]
    return pd.DataFrame(rows, index=base.index).astype(float)
