"""Checks that a watershed outlet sits on the canyon's own stream.

Without --run: audits the app watersheds (room import) against the current catalog points and lists
the suspicious canyons. With --run: compares a batch run (e.g. --entry-guided-outlet) with the app
watersheds and decides, canyon by canyon, which new result replaces the current one.
"""

from __future__ import annotations

import argparse
import json
import math
import shutil
import sys
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any

from shapely.geometry import Point, shape
from shapely.ops import transform as shapely_transform

ROOM_IMPORT_DIR = Path("offline-data/full/room-import")
DEFAULT_REFERENCE_RUN = Path("build/watersheds/batch-run-world")
DEFAULT_REVIEW_FILE = Path("watershed-review/watershed-review.json")

ENTRY_OUTSIDE_TOLERANCE_M = 60.0
OUTLET_ABOVE_START_TOLERANCE_M = 40.0
OUTLET_BELOW_BOTTOM_TOLERANCE_M = 400.0
OUTLET_FAR_FROM_POINTS_M = 800.0
# Below the channel threshold of the batch: a slope or a gully, not a canyon stream.
TINY_BASIN_KM2 = 0.05

# Flags that mean the outlet is on another stream. "outlet_far_from_canyon_points" alone is not one
# when the outlet was placed by hand in the review tool.
STRONG_FLAGS = {
    "entry_outside_basin",
    "outlet_above_canyon_start",
    "outlet_far_below_canyon_bottom",
    "basin_below_canyon_start",
    "tiny_basin",
}


def load_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, data: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def local_metric_projection(latitude: float, longitude: float):
    kx = 111_320.0 * math.cos(math.radians(latitude))
    ky = 110_540.0

    def project(x, y, z=None):
        return ((x - longitude) * kx, (y - latitude) * ky)

    return project


def parse_geometry(value: Any) -> dict[str, Any] | None:
    if isinstance(value, dict):
        return value
    if isinstance(value, str):
        try:
            return json.loads(value)
        except json.JSONDecodeError:
            # Older room-import rows store the geometry as a Python repr.
            return json.loads(value.replace("'", '"'))
    return None


def outlet_of(run_result: dict[str, Any]) -> dict[str, Any]:
    candidate = run_result.get("bestHydroProxyCandidate") or {}
    evaluation = candidate.get("evaluation") or {}
    return {
        "latitude": evaluation.get("snapped_latitude"),
        "longitude": evaluation.get("snapped_longitude"),
        "elevationM": evaluation.get("elevation_m"),
        "pointType": candidate.get("pointType"),
        "forcedByReview": bool(candidate.get("forcedByReview")),
        "entryGuided": candidate.get("entryGuided") is not None,
    }


def basin_max_elevation(run_result: dict[str, Any]) -> float | None:
    value = (run_result.get("watershed") or {}).get("basinMaxElevationM")
    if value is None:
        value = (run_result.get("descriptors") or {}).get("basinMaxElevationM")
    return value


def outlet_flags(
    *,
    canyon: dict[str, Any],
    points: list[dict[str, Any]],
    geometry: dict[str, Any] | None,
    outlet: dict[str, Any],
    area_km2: float | None,
    basin_max_elevation_m: float | None,
) -> tuple[list[str], dict[str, Any]]:
    flags: list[str] = []
    info: dict[str, Any] = {}
    latitude, longitude = outlet.get("latitude"), outlet.get("longitude")
    if latitude is None or longitude is None:
        return ["no_outlet"], info
    project = local_metric_projection(latitude, longitude)
    entries = [point for point in points if point.get("type") == "ENTREE"]
    canyon_points = [point for point in points if point.get("type") in ("ENTREE", "SORTIE")]

    if geometry is not None and entries:
        polygon = shapely_transform(project, shape(geometry))
        outside_m = min(polygon.distance(Point(project(p["longitude"], p["latitude"]))) for p in entries)
        info["entryOutsideM"] = round(outside_m, 1)
        if outside_m > ENTRY_OUTSIDE_TOLERANCE_M:
            flags.append("entry_outside_basin")
    if canyon_points:
        nearest_m = min(Point(project(p["longitude"], p["latitude"])).distance(Point(0.0, 0.0)) for p in canyon_points)
        info["outletToCanyonPointM"] = round(nearest_m)
        if nearest_m > OUTLET_FAR_FROM_POINTS_M and not outlet.get("forcedByReview"):
            flags.append("outlet_far_from_canyon_points")

    start = canyon.get("altitudeDepart")
    drop = canyon.get("denivele")
    elevation = outlet.get("elevationM")
    if isinstance(start, int) and elevation is not None:
        if elevation > start + OUTLET_ABOVE_START_TOLERANCE_M:
            flags.append("outlet_above_canyon_start")
        if isinstance(drop, int) and elevation < start - drop - OUTLET_BELOW_BOTTOM_TOLERANCE_M:
            flags.append("outlet_far_below_canyon_bottom")
    if isinstance(start, int) and basin_max_elevation_m is not None and basin_max_elevation_m < start - OUTLET_ABOVE_START_TOLERANCE_M:
        flags.append("basin_below_canyon_start")
    if area_km2 is not None and area_km2 < TINY_BASIN_KM2:
        flags.append("tiny_basin")
    return flags, info


def strong(flags: list[str]) -> list[str]:
    return [flag for flag in flags if flag in STRONG_FLAGS or flag == "outlet_far_from_canyon_points"]


def write_accepted_run(run_dir: Path, target_dir: Path, canyon_ids: list[int]) -> None:
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    from run_catchment_batch import aggregate_results

    canyons_dir = target_dir / "canyons"
    shutil.rmtree(canyons_dir, ignore_errors=True)
    canyons_dir.mkdir(parents=True)
    for canyon_id in canyon_ids:
        shutil.copy2(run_dir / "canyons" / f"{canyon_id}.json", canyons_dir / f"{canyon_id}.json")
    for log_name in ("errors.log", "source_resolution.log"):
        if (run_dir / log_name).exists():
            shutil.copy2(run_dir / log_name, target_dir / log_name)
    aggregate_results(target_dir)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--room-import-dir", type=Path, default=ROOM_IMPORT_DIR)
    parser.add_argument("--reference-run", type=Path, default=DEFAULT_REFERENCE_RUN, help="Run that produced the app watersheds (outlet points).")
    parser.add_argument("--review-file", type=Path, default=DEFAULT_REVIEW_FILE)
    parser.add_argument("--extra-points-json", type=Path, help="Points used by the run on top of the catalog ones.")
    parser.add_argument("--run", type=Path, help="Batch run to compare with the app watersheds.")
    parser.add_argument("--output", type=Path, required=True, help="JSON report.")
    parser.add_argument("--accepted-ids", type=Path, help="With --run: file listing the canyons whose new result is kept.")
    parser.add_argument(
        "--write-accepted-run",
        type=Path,
        help="With --run: copies the kept canyons into this run dir and aggregates it, ready for package_results.py --scope overlay.",
    )
    parser.add_argument(
        "--visual-checks",
        type=Path,
        help="With --run: visual-checks.json of the run ({\"checked\": {canyonId: verdict}}); the verdicts decide instead of the flags.",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    canyons = {int(row["id"]): row for row in load_json(args.room_import_dir / "canyons.json")}
    points: dict[int, list[dict[str, Any]]] = defaultdict(list)
    extra_points = load_json(args.extra_points_json) if args.extra_points_json else []
    for point in [*load_json(args.room_import_dir / "geo_points.json"), *extra_points]:
        points[int(point["canyonId"])].append(point)
    app_rows = {int(row["canyonId"]): row for row in load_json(args.room_import_dir / "watersheds.json")}
    reviews = {int(row["canyonId"]): row for row in load_json(args.review_file)} if args.review_file.exists() else {}
    visual_checks = load_json(args.visual_checks)["checked"] if args.visual_checks else None

    def current(canyon_id: int) -> dict[str, Any] | None:
        row = app_rows.get(canyon_id)
        reference_path = args.reference_run / "canyons" / f"{canyon_id}.json"
        if row is None or not reference_path.exists():
            return None
        reference = load_json(reference_path)
        flags, info = outlet_flags(
            canyon=canyons[canyon_id],
            points=points[canyon_id],
            geometry=parse_geometry(row.get("geometry")),
            outlet=outlet_of(reference),
            area_km2=row.get("upstreamCatchmentAreaKm2"),
            basin_max_elevation_m=basin_max_elevation(reference),
        )
        return {"areaKm2": row.get("upstreamCatchmentAreaKm2"), "flags": flags, **info}

    report: list[dict[str, Any]] = []
    accepted: list[int] = []
    if args.run is None:
        for canyon_id in sorted(app_rows):
            if canyon_id not in canyons:
                continue
            state = current(canyon_id)
            if state is None or not state["flags"]:
                continue
            report.append(
                {
                    "canyonId": canyon_id,
                    "name": canyons[canyon_id].get("nom"),
                    "review": (reviews.get(canyon_id) or {}).get("status"),
                    **state,
                }
            )
        print(json.dumps(Counter(flag for item in report for flag in item["flags"]), indent=2))
    else:
        for path in sorted((args.run / "canyons").glob("*.json")):
            result = load_json(path)
            canyon_id = int(result["canyonId"])
            canyon = canyons.get(canyon_id)
            if canyon is None:
                continue
            before = current(canyon_id)
            watershed = result.get("watershed") or {}
            item: dict[str, Any] = {
                "canyonId": canyon_id,
                "name": canyon.get("nom"),
                "country": canyon.get("pays"),
                "review": (reviews.get(canyon_id) or {}).get("status"),
                "status": result.get("status", "ok"),
                "watershedStatus": result.get("watershedStatus"),
                "before": before,
            }
            if result.get("watershedStatus") == "built" and watershed.get("geometry"):
                outlet = outlet_of(result)
                flags, info = outlet_flags(
                    canyon=canyon,
                    points=points[canyon_id],
                    geometry=watershed["geometry"],
                    outlet=outlet,
                    area_km2=watershed.get("selectedUpstreamCatchmentAreaKm2"),
                    basin_max_elevation_m=basin_max_elevation(result),
                )
                item["after"] = {
                    "areaKm2": watershed.get("selectedUpstreamCatchmentAreaKm2"),
                    "flags": flags,
                    "pointType": outlet["pointType"],
                    "entryGuided": outlet["entryGuided"],
                    **info,
                }
                if before is None:
                    item["decision"] = "added"
                elif len(strong(flags)) < len(strong(before["flags"])):
                    item["decision"] = "replaced"
                else:
                    item["decision"] = "kept_current"
            else:
                item["decision"] = "kept_current" if before is not None else "still_missing"
            if visual_checks is not None:
                # The eye decides: "ok ..." keeps the new result, "rejected ..." the current one, and a
                # canyon not checked yet (or waiting for a rerun, "fix ...") is left out.
                verdict = visual_checks.get(str(canyon_id))
                item["visualCheck"] = verdict
                if verdict is None or not (verdict.startswith("ok") or verdict.startswith("rejected")):
                    item["decision"] = "unchecked"
                elif verdict.startswith("rejected") or result.get("watershedStatus") != "built":
                    item["decision"] = "kept_current" if before is not None else "still_missing"
                else:
                    item["decision"] = "added" if before is None else "replaced"
            if item["decision"] in ("added", "replaced"):
                accepted.append(canyon_id)
            report.append(item)
        print(json.dumps(Counter(item["decision"] for item in report), indent=2))
        if args.accepted_ids:
            args.accepted_ids.parent.mkdir(parents=True, exist_ok=True)
            args.accepted_ids.write_text("".join(f"{canyon_id}\n" for canyon_id in accepted), encoding="utf-8")
        if args.write_accepted_run:
            write_accepted_run(args.run, args.write_accepted_run, accepted)
    write_json(args.output, report)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
