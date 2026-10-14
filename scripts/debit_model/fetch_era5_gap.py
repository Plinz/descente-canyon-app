"""Fetch ERA5 daily weather for weather targets over a date range (gap filling).

Writes one JSON file per target in the output directory; already fetched targets are skipped,
so the script can be relaunched after rate-limit failures.
"""
from __future__ import annotations

import argparse
import json
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

ARCHIVE_URL = "https://archive-api.open-meteo.com/v1/archive"
DAILY_VARIABLES = "precipitation_sum,temperature_2m_mean"


def fetch(latitude: float, longitude: float, start: str, end: str, timeout: float) -> dict:
    params = {
        "latitude": f"{latitude:.6f}",
        "longitude": f"{longitude:.6f}",
        "start_date": start,
        "end_date": end,
        "daily": DAILY_VARIABLES,
        "timezone": "auto",
        "models": "era5",
    }
    url = f"{ARCHIVE_URL}?{urllib.parse.urlencode(params)}"
    with urllib.request.urlopen(url, timeout=timeout) as response:
        return json.loads(response.read().decode("utf-8"))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--targets-path", required=True, help="JSONL with targetId, latitude, longitude")
    parser.add_argument("--target-ids-path", help="Optional text file restricting target ids (one per line)")
    parser.add_argument("--start", required=True)
    parser.add_argument("--end", required=True)
    parser.add_argument("--output-dir", required=True)
    parser.add_argument("--delay-s", type=float, default=1.5)
    parser.add_argument("--timeout-s", type=float, default=60.0)
    args = parser.parse_args()

    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    wanted = None
    if args.target_ids_path:
        wanted = {line.strip() for line in Path(args.target_ids_path).read_text().splitlines() if line.strip()}

    targets = [json.loads(line) for line in Path(args.targets_path).read_text().splitlines() if line.strip()]
    targets = [t for t in targets if wanted is None or t["targetId"] in wanted]
    done = failed = 0
    for target in targets:
        out = output_dir / f"{target['targetId']}.json"
        if out.exists():
            continue
        for attempt in range(5):
            try:
                payload = fetch(target["latitude"], target["longitude"], args.start, args.end, args.timeout_s)
                payload["targetId"] = target["targetId"]
                out.write_text(json.dumps(payload))
                done += 1
                break
            except urllib.error.HTTPError as error:
                wait = 70.0 if error.code == 429 else 10.0 * (attempt + 1)
                print(f"HTTP {error.code} for {target['targetId']}, waiting {wait}s", flush=True)
                time.sleep(wait)
            except Exception as error:  # noqa: BLE001 - network errors are retried
                print(f"error {error!r} for {target['targetId']}", flush=True)
                time.sleep(10.0 * (attempt + 1))
        else:
            failed += 1
        time.sleep(args.delay_s)
        if (done + failed) % 50 == 0:
            print(f"fetched={done} failed={failed}", flush=True)
    print(f"finished fetched={done} failed={failed}", flush=True)


if __name__ == "__main__":
    main()
