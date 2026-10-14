"""Download MeteoSwiss open data (OGD, no key): daily precipitation of stations near canyons.

Networks: ``ogd-smn`` (SwissMetNet) and ``ogd-smn-precip`` (precipitation network). For every
station within ``--radius-km`` of a canyon, ``*_d_historical.csv`` and ``*_d_recent.csv``.
Station codes are prefixed by network: ``CHS_<abbr>`` (smn) and ``CHP_<abbr>`` (smn-precip).
"""
from __future__ import annotations

import argparse
import csv
import io
import json
import math
import time
import urllib.request
from pathlib import Path

BASE = "https://data.geo.admin.ch"
NETWORKS = {"CHS": "ogd-smn", "CHP": "ogd-smn-precip"}


def fetch(url: str) -> bytes:
    for attempt in range(4):
        try:
            with urllib.request.urlopen(url, timeout=120) as response:
                return response.read()
        except Exception as error:  # noqa: BLE001
            if "404" in str(error):
                return b""
            time.sleep(5 * (attempt + 1))
    return b""


def stations(network: str) -> list[dict]:
    raw = fetch(f"{BASE}/ch.meteoschweiz.{network}/{network}_meta_stations.csv").decode("latin-1")
    rows = list(csv.DictReader(io.StringIO(raw), delimiter=";"))
    return rows


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--geo-points", default="offline-data/full/room-import/geo_points.json")
    parser.add_argument("--output-dir", default="build/debit-model/meteoswiss")
    parser.add_argument("--radius-km", type=float, default=25.0)
    args = parser.parse_args()
    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    points = [(p["latitude"], p["longitude"]) for p in json.loads(Path(args.geo_points).read_text()) if p.get("latitude") is not None]

    selected = []
    for prefix, network in NETWORKS.items():
        for row in stations(network):
            try:
                lat = float(row["station_coordinates_wgs84_lat"])
                lon = float(row["station_coordinates_wgs84_lon"])
            except (KeyError, ValueError):
                continue
            close = any(
                abs(lat - p[0]) < 0.3 and abs(lon - p[1]) < 0.4
                and 6371.0 * 2 * math.asin(math.sqrt(math.sin(math.radians(p[0] - lat) / 2) ** 2 + math.cos(math.radians(lat)) * math.cos(math.radians(p[0])) * math.sin(math.radians(p[1] - lon) / 2) ** 2)) <= args.radius_km
                for p in points
            )
            if close:
                selected.append({
                    "code": f"{prefix}_{row['station_abbr']}",
                    "network": network,
                    "abbr": row["station_abbr"],
                    "lat": lat,
                    "lon": lon,
                    "alt": float(row.get("station_height_masl") or 0.0),
                })
    (output_dir / "stations.json").write_text(json.dumps(selected, indent=1))
    print(f"stations near canyons: {len(selected)}", flush=True)
    for number, station in enumerate(selected, start=1):
        for period in ("historical", "recent"):
            target = output_dir / f"{station['code']}_{period}.csv"
            if target.exists():
                continue
            abbr = station["abbr"].lower()
            data = fetch(f"{BASE}/ch.meteoschweiz.{station['network']}/{abbr}/{station['network']}_{abbr}_d_{period}.csv")
            target.write_bytes(data)
            time.sleep(0.2)
        if number % 25 == 0:
            print(f"[{number}/{len(selected)}]", flush=True)
    print("done", flush=True)


if __name__ == "__main__":
    main()
