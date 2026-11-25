"""GPS points of every canyon, used to check that a track belongs to the canyon it is sent for.

The catalog job rewrites `canyon-reference.json` from each catalog it builds; the API reloads it
when the file changes. It can also be built once from any catalog.db (`dcserver canyon-reference`).
"""
from __future__ import annotations

import json
import sqlite3
import threading
from pathlib import Path

from ..manifest import utc_now_iso
from ..publish import atomic_write_bytes


def build_reference_from_catalog(catalog_db: Path) -> dict:
    connection = sqlite3.connect(f"file:{catalog_db}?mode=ro", uri=True)
    try:
        names = {row[0]: row[1] for row in connection.execute("SELECT id, nom FROM canyons")}
        points: dict[int, list[list[float]]] = {}
        for canyon_id, latitude, longitude in connection.execute(
            "SELECT canyonId, latitude, longitude FROM geo_points ORDER BY canyonId, id"
        ):
            points.setdefault(canyon_id, []).append([round(latitude, 6), round(longitude, 6)])
    finally:
        connection.close()
    return {
        "generatedAt": utc_now_iso(),
        "canyons": {str(canyon_id): {"name": names.get(canyon_id, ""), "points": points.get(canyon_id, [])} for canyon_id in names},
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
