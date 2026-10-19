"""Per-canyon flow-regulation descriptors from the OSM extracts (dams, weirs, canals, penstocks).

Catchment polygons: build/watersheds/batch-run-*/watershed_polygons.geojson. OSM lines:
build/watersheds/osm-regulation/<country>/regulation_features.gpkg (GeoPackage, LINESTRING).
Output: JSON {canyonId: {regDamCount, regWeirCount, regCanalKm, regPenstockKm, regPipelineKm, regAny,
regNearestKm}} where regNearestKm is the distance from the canyon point to the closest feature in its
catchment.
"""
from __future__ import annotations

import json
import math
import sqlite3
import struct
import sys
from pathlib import Path

import numpy as np
from shapely import wkb
from shapely.geometry import Point, shape
from shapely.strtree import STRtree
from shapely.validation import make_valid

REPO = Path(__file__).resolve().parents[3]
ENVELOPE_BYTES = {0: 0, 1: 32, 2: 48, 3: 48, 4: 64}


def gpkg_geometry(blob: bytes):
    flags = blob[3]
    envelope = ENVELOPE_BYTES[(flags >> 1) & 0x07]
    return wkb.loads(bytes(blob[8 + envelope:]))


def kind(waterway, barrier, man_made, other_tags) -> str | None:
    tags = other_tags or ""
    if waterway in ("dam",) or barrier == "dam":
        return "dam"
    if waterway in ("weir", "sluice_gate") or barrier in ("weir",):
        return "weir"
    if waterway == "pressurised" or '"usage"=>"penstock"' in tags or "penstock" in tags:
        return "penstock"
    if waterway == "canal":
        return "canal"
    if man_made == "pipeline" and ('"substance"=>"water"' in tags or '"usage"=>"hydro"' in tags):
        return "pipeline"
    return None


def length_km(geom, lat: float) -> float:
    # degrees -> km (local scale), good enough for small catchments
    return geom.length * 111.32 * math.sqrt((math.cos(math.radians(lat)) ** 2 + 1) / 2)


def main():
    out = Path(sys.argv[1])
    sys.path.insert(0, str(REPO / "scripts/debit_model"))
    import neighbors as N
    points = N.canyon_points(REPO / "offline-data/full/room-import/geo_points.json", REPO / "build/debit-pipeline/weather-planning/weather_targets.jsonl")
    polygons = {}
    for path in sorted((REPO / "build/watersheds").glob("batch-run-*/watershed_polygons.geojson")):
        for feature in json.loads(path.read_text())["features"]:
            canyon = int(feature["properties"]["canyonId"])
            if canyon not in polygons and feature.get("geometry"):
                polygons[canyon] = make_valid(shape(feature["geometry"]))
    print(f"{len(polygons)} catchments", flush=True)
    geoms, kinds = [], []
    for gpkg in sorted((REPO / "build/watersheds/osm-regulation").glob("*/regulation_features.gpkg")):
        con = sqlite3.connect(gpkg)
        count = 0
        for blob, waterway, barrier, man_made, other in con.execute("select GEOMETRY, waterway, barrier, man_made, other_tags from regulation_features"):
            k = kind(waterway, barrier, man_made, other)
            if k is None or blob is None:
                continue
            try:
                geoms.append(gpkg_geometry(blob))
            except Exception:
                continue
            kinds.append(k)
            count += 1
        print(f"{gpkg.parent.name}: {count} regulation lines", flush=True)
    tree = STRtree(geoms)
    kinds = np.array(kinds)
    result = {}
    for canyon, polygon in polygons.items():
        lat = polygon.centroid.y
        hits = tree.query(polygon, predicate="intersects")
        entry = {"regDamCount": 0, "regWeirCount": 0, "regCanalKm": 0.0, "regPenstockKm": 0.0, "regPipelineKm": 0.0}
        nearest = None
        point = Point(points[canyon][1], points[canyon][0]) if canyon in points else polygon.centroid
        for index in hits:
            k = kinds[index]
            inside = geoms[index].intersection(polygon)
            if k == "dam":
                entry["regDamCount"] += 1
            elif k == "weir":
                entry["regWeirCount"] += 1
            elif k == "canal":
                entry["regCanalKm"] += length_km(inside, lat)
            elif k == "penstock":
                entry["regPenstockKm"] += length_km(inside, lat)
            elif k == "pipeline":
                entry["regPipelineKm"] += length_km(inside, lat)
            distance = point.distance(geoms[index]) * 111.32
            nearest = distance if nearest is None else min(nearest, distance)
        entry["regAny"] = float(any(entry[k] for k in ("regDamCount", "regWeirCount", "regPenstockKm", "regPipelineKm")))
        entry["regNearestKm"] = round(nearest, 3) if nearest is not None else None
        for k in ("regCanalKm", "regPenstockKm", "regPipelineKm"):
            entry[k] = round(entry[k], 3)
        result[canyon] = entry
    out.write_text(json.dumps(result))
    present = sum(1 for v in result.values() if v["regAny"])
    print(f"{len(result)} canyons, {present} with dams/weirs/penstocks/pipelines in their catchment", flush=True)


if __name__ == "__main__":
    main()
