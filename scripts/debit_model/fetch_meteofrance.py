"""Download Météo-France daily climatological data (open data, no key) for all departments.

Files: ``Q_<dept>_previous-1950-2024_RR-T-Vent.csv.gz`` (history) and
``Q_<dept>_latest-2025-2026_RR-T-Vent.csv.gz`` (updated every morning, used by the app at runtime).
"""
from __future__ import annotations

import argparse
import json
import time
import urllib.request
from pathlib import Path

DATASET_API = "https://www.data.gouv.fr/api/1/datasets/donnees-climatologiques-de-base-quotidiennes/"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", default="build/debit-model/meteofrance")
    parser.add_argument("--kind", default="RR-T-Vent", choices=["RR-T-Vent", "autres-parametres"])
    args = parser.parse_args()
    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    with urllib.request.urlopen(DATASET_API, timeout=120) as response:
        dataset = json.loads(response.read().decode("utf-8"))
    resources = [
        r for r in dataset["resources"]
        if r["title"].endswith(f"_{args.kind}") and ("1950-" in r["title"] or "2025-" in r["title"])
    ]
    (output_dir / f"resources_{args.kind}.json").write_text(json.dumps([{k: r.get(k) for k in ("title", "url", "last_modified", "filesize")} for r in resources], indent=1))
    for number, resource in enumerate(resources, start=1):
        target = output_dir / resource["url"].rsplit("/", 1)[-1]
        if target.exists() and target.stat().st_size == (resource.get("filesize") or -1):
            continue
        for attempt in range(4):
            try:
                urllib.request.urlretrieve(resource["url"], target)
                break
            except Exception as error:  # noqa: BLE001
                print(f"retry {attempt} {resource['url']}: {error}", flush=True)
                time.sleep(10 * (attempt + 1))
        if number % 20 == 0:
            print(f"[{number}/{len(resources)}]", flush=True)
    print("done", flush=True)


if __name__ == "__main__":
    main()
