"""Météo-France weather warnings (vigilance) archive: morning bulletin of every day since Nov 2022.

Output (CSV): day, issue time, department code, phenomenon (2 pluie-inondation, 3 orages,
4 crues, 5 neige-verglas), color for J and J+1 (1 green, 2 yellow, 3 orange, 4 red).
"""
from __future__ import annotations

import csv
import json
import sys
import time
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

BASE = "https://meteofrance.s3.sbg.io.cloud.ovh.net/data/vigilance"
PHENOMENA = {"2": "rain", "3": "storm", "4": "flood", "5": "snow"}


def get(url: str, attempts: int = 4) -> bytes | None:
    for attempt in range(attempts):
        try:
            with urllib.request.urlopen(url, timeout=60) as response:
                return response.read()
        except Exception as error:
            if "404" in str(error):
                return None
            time.sleep(3 * (attempt + 1))
    return None


def morning_bulletins(tree: dict) -> list[tuple[str, str]]:
    """(YYYY/MM/DD, HHMMSS) of the first bulletin of each day that has the map JSON."""
    picks = []
    for year, months in tree.items():
        for month, days in months.items():
            for day, issues in days.items():
                candidates = sorted(t for t, files in issues.items() if "CDP_CARTE_EXTERNE.json" in files and t >= "030000")
                if candidates:
                    picks.append((f"{year}/{month}/{day}", candidates[0]))
    return sorted(picks)


def parse(payload: dict, day: str, issue: str) -> list[list]:
    rows = []
    for period in payload["product"]["periods"]:
        echeance = period["echeance"]  # "J" or "J1"
        for domain in period["timelaps"]["domain_ids"]:
            for item in domain.get("phenomenon_items", []):
                name = PHENOMENA.get(str(item["phenomenon_id"]))
                if name:
                    rows.append([day.replace("/", "-"), issue, echeance, domain["domain_id"], name, int(item["phenomenon_max_color_id"])])
    return rows


def main():
    out = Path(sys.argv[1])
    tree = json.loads(get(f"{BASE}/vigilance-hexagone-tree.json"))
    picks = morning_bulletins(tree)
    print(f"{len(picks)} days from {picks[0][0]} to {picks[-1][0]}", flush=True)

    def work(pick):
        day, issue = pick
        raw = get(f"{BASE}/metropole/{day}/{issue}/CDP_CARTE_EXTERNE.json")
        if raw is None:
            return []
        try:
            return parse(json.loads(raw), day, issue)
        except Exception as error:
            print(f"{day} {issue}: parse error {error!r}", flush=True)
            return []

    with out.open("w", newline="") as handle, ThreadPoolExecutor(max_workers=6) as pool:
        writer = csv.writer(handle)
        writer.writerow(["day", "issue", "echeance", "department", "phenomenon", "color"])
        for index, rows in enumerate(pool.map(work, picks)):
            writer.writerows(rows)
            if index % 200 == 0:
                print(f"{index}/{len(picks)}", flush=True)
    print("done", flush=True)


if __name__ == "__main__":
    main()
