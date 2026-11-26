"""GPS points of every canyon, used to check that a track belongs to the canyon it is sent for, and
its place (country, region...), given to a proposed canyon placed near it.

The catalog job rewrites `canyon-reference.json` from each catalog it builds; the API reloads it
when the file changes. It can also be built once from any catalog.db (`dcserver canyon-reference`).
"""
from __future__ import annotations

import json
import math
import sqlite3
import threading
from pathlib import Path

from ..canyons.content import PLACE_FIELDS, distance_m
from ..manifest import utc_now_iso
from ..publish import atomic_write_bytes

PLACE_MAX_DISTANCE_M = 25_000
"""Beyond this, the nearest canyon says little of where a new one is: the operator fills the place."""


def build_reference_from_catalog(catalog_db: Path) -> dict:
    connection = sqlite3.connect(f"file:{catalog_db}?mode=ro", uri=True)
    try:
        rows = connection.execute(
            f"SELECT id, nom, isForbidden, sourceType, {', '.join(PLACE_FIELDS)} FROM canyons"
        ).fetchall()
        names = {row[0]: row[1] for row in rows}
        flags = {row[0]: (bool(row[2]), row[3] == "APP") for row in rows}
        places = {row[0]: {field: value for field, value in zip(PLACE_FIELDS, row[4:]) if value} for row in rows}
        points: dict[int, list[list[float]]] = {}
        for canyon_id, latitude, longitude in connection.execute(
            "SELECT canyonId, latitude, longitude FROM geo_points ORDER BY canyonId, id"
        ):
            points.setdefault(canyon_id, []).append([round(latitude, 6), round(longitude, 6)])
    finally:
        connection.close()
    return {
        "generatedAt": utc_now_iso(),
        "canyons": {
            str(canyon_id): {
                "name": names.get(canyon_id, ""),
                "points": points.get(canyon_id, []),
                "forbidden": flags[canyon_id][0],
                "app": flags[canyon_id][1],
                "place": places[canyon_id],
            }
            for canyon_id in names
        },
    }


def write_reference(path: Path, reference: dict) -> None:
    atomic_write_bytes(path, json.dumps(reference, ensure_ascii=False, separators=(",", ":")).encode("utf-8"))


class CanyonReference:
    """Thread-safe view of the reference file, reloaded when its modification time changes."""

    def __init__(self, path: Path) -> None:
        self._path = path
        self._lock = threading.Lock()
        self._mtime: float | None = None
        self._points: dict[int, list[tuple[float, float]]] = {}
        self._entries: dict[int, dict] = {}

    def _refresh(self) -> None:
        try:
            mtime = self._path.stat().st_mtime
        except FileNotFoundError:
            return
        if mtime == self._mtime:
            return
        data = json.loads(self._path.read_text(encoding="utf-8"))
        self._points = {
            int(canyon_id): [(float(lat), float(lon)) for lat, lon in entry.get("points", [])]
            for canyon_id, entry in data.get("canyons", {}).items()
        }
        self._entries = {int(canyon_id): entry for canyon_id, entry in data.get("canyons", {}).items()}
        self._mtime = mtime

    def exists(self, canyon_id: int) -> bool:
        with self._lock:
            self._refresh()
            return canyon_id in self._points

    def points(self, canyon_id: int) -> list[tuple[float, float]] | None:
        """None for an unknown canyon, an empty list for a canyon without GPS points."""
        with self._lock:
            self._refresh()
            return self._points.get(canyon_id)

    def entries(self) -> dict[int, dict]:
        """Every canyon of the catalog: name, points ([lat, lon]), forbidden, app (added in the app), place."""
        with self._lock:
            self._refresh()
            return self._entries

    def place_near(self, latitude: float, longitude: float, max_distance_m: float = PLACE_MAX_DISTANCE_M) -> dict[str, str] | None:
        """The place (pays, region, departement, massif, bassin) of the catalog canyon with a point
        nearest to ([latitude], [longitude]) within [max_distance_m], None if there is none."""
        lat_span = max_distance_m / 111_000
        lon_span = lat_span / max(0.1, math.cos(math.radians(latitude)))
        best: tuple[float, dict] | None = None
        for entry in self.entries().values():
            place = entry.get("place")
            if not place or not place.get("pays"):
                continue
            for lat, lon in entry.get("points", []):
                if abs(lat - latitude) > lat_span or abs(lon - longitude) > lon_span:
                    continue
                distance = distance_m(latitude, longitude, lat, lon)
                if distance <= max_distance_m and (best is None or distance < best[0]):
                    best = (distance, place)
        return dict(best[1]) if best else None
