"""List the automatic (hourly) Météo-France stations near canyons.

The hourly "latest" file of a department (``H_<dept>_latest-YYYY-YYYY.csv.gz``) holds the stations
that publish hourly rain (RR1). Only their code set is kept (``hourly_stations.json``); the files are
streamed and not stored.
"""
from __future__ import annotations

import argparse
import gzip
import json
import urllib.request
from pathlib import Path

import pandas as pd

URL = "https://meteofrance.s3.sbg.io.cloud.ovh.net/data/synchro_ftp/BASE/HOR/H_{dept}_latest-2025-2026.csv.gz"


def department_of(code: str) -> str:
    return code[:3] if code[:2] in ("97", "98") else code[:2]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--rain-cache", default="build/debit-model/meteofrance-cache/rain_stations_fr_ch.pkl")
    parser.add_argument("--output", default="build/debit-model/meteofrance-cache/hourly_stations.json")
    args = parser.parse_args()
    nearby = pd.read_pickle(args.rain_cache)["nearby"]
    codes = {code for stations in nearby.values() for code, _ in stations if code[:1].isdigit()}
    departments = sorted({department_of(c) for c in codes})
    print(f"departments to scan: {len(departments)}", flush=True)
    hourly: dict[str, list[str]] = {}
    for department in departments:
        stations: set[str] = set()
        try:
            with urllib.request.urlopen(URL.format(dept=department), timeout=300) as response:
                with gzip.open(response, "rt", encoding="utf-8", errors="replace") as lines:
                    next(lines)
                    for line in lines:
                        stations.add(line.split(";", 1)[0])
        except Exception as error:  # noqa: BLE001
            print(f"{department}: {error}", flush=True)
        hourly[department] = sorted(stations)
        print(f"{department}: {len(stations)} hourly stations", flush=True)
    Path(args.output).write_text(json.dumps(hourly))
    automatic = {s for values in hourly.values() for s in values}
    print(f"automatic stations near canyons: {len(codes & automatic)} / {len(codes)}", flush=True)


if __name__ == "__main__":
    main()
