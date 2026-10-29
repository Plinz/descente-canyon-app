"""Writes data-overlay/recovered-geo-points.json: points of canyons without any on the site, once checked.

The points come from an extra-points file of the watershed pipeline (OpenCanyon, coordinates in the topo
text, or placed on the OpenStreetMap stream at the topo altitudes). Only the canyons whose recomputed
watershed was accepted by eye (visual-checks.json of the run: verdict starting with "ok") are kept: the
basin then confirms that the points sit on the canyon's stream.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

SOURCES = {
    "opencanyon.org (CC BY-NC-SA 4.0)": "OPENCANYON",
    "descente-canyon topo text": "TOPO",
}
# Titles worth showing (a place, an option); the others only repeat the kind of point.
TOPO_TITLES = {"Arrivée Al Ulyah (topo)", "Parking d'arrivée, confluence (topo)", "Option Hulaylat (topo)", "Option Mheibis (topo)"}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--extra-points-json", type=Path, required=True)
    parser.add_argument("--visual-checks", type=Path, required=True, help="visual-checks.json of the run that used the points")
    parser.add_argument("--room-import-dir", type=Path, default=Path("offline-data/full/room-import"))
    parser.add_argument("--output", type=Path, default=Path("data-overlay/recovered-geo-points.json"))
    return parser.parse_args()


def source_of(point: dict) -> str:
    if point["source"] in SOURCES:
        return SOURCES[point["source"]]
    if point["source"].startswith("derived:"):
        return "ESTIMATED"
    raise SystemExit(f"Unknown point source {point['source']!r}")


def main() -> int:
    args = parse_args()
    checked = json.loads(args.visual_checks.read_text(encoding="utf-8"))["checked"]
    accepted = {int(canyon_id) for canyon_id, verdict in checked.items() if verdict.startswith("ok")}
    site = {int(point["canyonId"]) for point in json.loads((args.room_import_dir / "geo_points.json").read_text(encoding="utf-8"))}
    rows = []
    for point in json.loads(args.extra_points_json.read_text(encoding="utf-8")):
        canyon_id = int(point["canyonId"])
        if canyon_id not in accepted or canyon_id in site:
            continue
        source = source_of(point)
        rows.append(
            {
                "canyonId": canyon_id,
                "type": point["type"],
                "latitude": point["latitude"],
                "longitude": point["longitude"],
                "source": source,
                "title": point["label"].removesuffix(" (topo)") if point["label"] in TOPO_TITLES else None,
                "label": point["label"],
                "reference": point.get("sourceUrl"),
            }
        )
    rows.sort(key=lambda row: (row["canyonId"], row["type"], row["latitude"], row["longitude"]))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(rows, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    print(json.dumps({"points": len(rows), "canyons": len({row["canyonId"] for row in rows})}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
