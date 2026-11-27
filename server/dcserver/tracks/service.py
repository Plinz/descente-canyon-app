"""Community tracks: intake, votes, deletion, listing. No moderator: automatic checks reject what
cannot be a track of the canyon, and the votes rank, endorse or discredit the rest."""
from __future__ import annotations

import contextlib
import json
import secrets
import sqlite3
import threading
import time
from collections.abc import Callable, Iterator
from datetime import datetime, timezone
from pathlib import Path

from .. import db, netid
from ..ratelimit import RateLimiter
from . import geometry, standing
from .canyons import CanyonReference
from .gpx import GpxError, parse_gpx

ROLES = ("APPROACH", "RETURN", "ESCAPE", "FULL")
"""Approach walk, way back, escape route, or the whole outing (approach, descent, return)."""

SIMPLIFY_TOLERANCE_M = 4.0
MAX_STORED_POINTS = 5_000
MIN_LENGTH_M = 100
MAX_LENGTH_M = 40_000
NEAR_CANYON_M = 3_000
"""At least one point of the track within this distance of a GPS point of the canyon."""
MAX_AWAY_M = 15_000
"""No point farther than this from every GPS point of the canyon (a drive from home is not a track)."""


class TrackError(Exception):
    def __init__(self, code: str, status: int) -> None:
        super().__init__(code)
        self.code = code
        self.status = status


def _iso(moment: float) -> str:
    return datetime.fromtimestamp(moment, timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


class TrackService:
    def __init__(
        self,
        database_path: Path,
        pepper: bytes,
        canyons: CanyonReference,
        limiter: RateLimiter | None = None,
        rules: standing.Rules = standing.Rules(),
        clock: Callable[[], float] = time.time,
    ) -> None:
        self._connection = db.connect(database_path)
        self._lock = threading.Lock()
        self._pepper = pepper
        self._canyons = canyons
        self._limiter = limiter or RateLimiter()
        self._rules = rules
        self._clock = clock

    def close(self) -> None:
        with self._lock:
            self._connection.close()

    @contextlib.contextmanager
    def _transaction(self) -> Iterator[sqlite3.Connection]:
        with self._lock:
            self._connection.execute("BEGIN IMMEDIATE")
            try:
                yield self._connection
            except BaseException:
                self._connection.execute("ROLLBACK")
                raise
            self._connection.execute("COMMIT")

    # Reading

    def list_for_canyon(self, canyon_id: int) -> dict:
        if not self._canyons.exists(canyon_id):
            raise TrackError("UNKNOWN_CANYON", 404)
        with self._lock:
            rows = self._connection.execute("SELECT * FROM tracks WHERE canyon_id = ?", (canyon_id,)).fetchall()
            votes = self._votes_of([row["id"] for row in rows])
        tracks = [self._to_json(row, votes.get(row["id"], [])) for row in rows]
        self._mark_references(tracks)
        # Newest first among equal ranks: sort by date, then (stable sort) by rank.
        tracks.sort(key=lambda t: t["createdAt"], reverse=True)
        tracks.sort(key=lambda t: (t["standing"] == standing.DISCREDITED, not t["isReference"], -t["score"], -t["up"]))
        return {"canyonId": canyon_id, "tracks": tracks}

    def _votes_of(self, track_ids: list[str]) -> dict[str, list[tuple[str, int]]]:
        if not track_ids:
            return {}
        placeholders = ",".join("?" * len(track_ids))
        result: dict[str, list[tuple[str, int]]] = {}
        for row in self._connection.execute(
            f"SELECT track_id, voter_net, value FROM votes WHERE track_id IN ({placeholders})", track_ids
        ):
            result.setdefault(row["track_id"], []).append((row["voter_net"], row["value"]))
        return result

    def _to_json(self, row: sqlite3.Row, votes: list[tuple[str, int]]) -> dict:
        counts = standing.effective_counts(votes, self._rules)
        return {
            "id": row["id"],
            "canyonId": row["canyon_id"],
            "role": row["role"],
            "createdAt": row["created_at"],
            "recordedMonth": row["recorded_month"],
            "lengthM": row["length_m"],
            "ascentM": row["ascent_m"],
            "descentM": row["descent_m"],
            "pointCount": row["point_count"],
            "bbox": [row["min_lon"], row["min_lat"], row["max_lon"], row["max_lat"]],
            "geometry": json.loads(row["geometry"]),
            "up": counts.up,
            "down": counts.down,
            "standing": standing.standing(counts, self._rules),
            "score": round(standing.score(counts), 4),
            "isReference": False,
        }

    @staticmethod
    def _mark_references(tracks: list[dict]) -> None:
        best: dict[str, dict] = {}
        for track in tracks:
            if track["standing"] != standing.ENDORSED:
                continue
            current = best.get(track["role"])
            if current is None or (track["score"], track["up"], track["createdAt"]) > (
                current["score"],
                current["up"],
                current["createdAt"],
            ):
                best[track["role"]] = track
        for track in best.values():
            track["isReference"] = True

    def _track_json(self, connection: sqlite3.Connection, track_id: str) -> dict:
        row = connection.execute("SELECT * FROM tracks WHERE id = ?", (track_id,)).fetchone()
        votes = [(r["voter_net"], r["value"]) for r in connection.execute("SELECT voter_net, value FROM votes WHERE track_id = ?", (track_id,))]
        track = self._to_json(row, votes)
        # Reference status depends on the other tracks of the canyon.
        siblings = [
            self._to_json(other, [(r["voter_net"], r["value"]) for r in connection.execute("SELECT voter_net, value FROM votes WHERE track_id = ?", (other["id"],))])
            for other in connection.execute("SELECT * FROM tracks WHERE canyon_id = ? AND id != ?", (row["canyon_id"], track_id))
        ]
        self._mark_references([track, *siblings])
        return track

    # Writing

    def _require_key(self, install_key: str | None) -> str:
        key_hash = netid.hash_install_key(self._pepper, install_key)
        if key_hash is None:
            raise TrackError("MISSING_KEY", 401)
        return key_hash

    def upload(self, canyon_id: int, role: str, data: bytes, install_key: str | None, ip: str) -> dict:
        key_hash = self._require_key(install_key)
        if role not in ROLES:
            raise TrackError("BAD_ROLE", 400)
        references = self._canyons.points(canyon_id)
        if references is None:
            raise TrackError("UNKNOWN_CANYON", 404)
        if not references:
            raise TrackError("UNLOCATED_CANYON", 422)
        net_hash = netid.hash_network(self._pepper, ip)
        # Rejected files count too: reading a hostile 2 MB file costs CPU.
        with self._transaction() as connection:
            if not self._limiter.allow(connection, [("attempt:key", key_hash), ("attempt:net", net_hash)], now=self._clock()):
                raise TrackError("RATE_LIMITED", 429)
        try:
            parsed = parse_gpx(data)
        except GpxError as exc:
            raise TrackError(exc.code, 422) from exc

        segments = self._simplified(parsed.segments)
        length = geometry.length_m(segments)
        if length < MIN_LENGTH_M:
            raise TrackError("TOO_SHORT", 422)
        if length > MAX_LENGTH_M:
            raise TrackError("TOO_LONG", 422)
        all_points = [point for segment in parsed.segments for point in segment]
        if geometry.nearest_distance_m(all_points, references) > NEAR_CANYON_M:
            raise TrackError("TOO_FAR", 422)
        if geometry.farthest_distance_m([p for s in segments for p in s], references) > MAX_AWAY_M:
            raise TrackError("TOO_FAR", 422)

        coordinates = geometry.rounded(segments)
        # Elevation gain on every point: the simplification only looks at horizontal positions.
        elevation = geometry.elevation_gain(parsed.segments)
        min_lon, min_lat, max_lon, max_lat = geometry.bbox(segments)
        track_id = "t" + secrets.token_urlsafe(9)
        now = self._clock()
        with self._transaction() as connection:
            if not self._limiter.allow(connection, [("upload:key", key_hash), ("upload:net", net_hash)], now=now):
                raise TrackError("RATE_LIMITED", 429)
            try:
                connection.execute(
                    """INSERT INTO tracks(id, canyon_id, role, created_at, recorded_month, geometry, geometry_hash,
                       point_count, length_m, ascent_m, descent_m, min_lon, min_lat, max_lon, max_lat, author_key, author_net)
                       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                    (
                        track_id,
                        canyon_id,
                        role,
                        _iso(now),
                        parsed.recorded_month,
                        json.dumps(coordinates, separators=(",", ":")),
                        geometry.geometry_hash(coordinates),
                        sum(len(segment) for segment in coordinates),
                        round(length),
                        elevation[0] if elevation else None,
                        elevation[1] if elevation else None,
                        min_lon,
                        min_lat,
                        max_lon,
                        max_lat,
                        key_hash,
                        net_hash,
                    ),
                )
            except sqlite3.IntegrityError as exc:
                raise TrackError("DUPLICATE", 409) from exc
            return self._track_json(connection, track_id)

    @staticmethod
    def _simplified(segments: list[list[geometry.Point]]) -> list[list[geometry.Point]]:
        tolerance = SIMPLIFY_TOLERANCE_M
        while True:
            result = [geometry.simplify(segment, tolerance) for segment in segments]
            if sum(len(segment) for segment in result) <= MAX_STORED_POINTS:
                return result
            tolerance *= 2

    def vote(self, track_id: str, value: int, install_key: str | None, ip: str) -> dict:
        key_hash = self._require_key(install_key)
        if value not in (-1, 0, 1):
            raise TrackError("BAD_VOTE", 400)
        now = self._clock()
        net_hash = netid.hash_network(self._pepper, ip)
        with self._transaction() as connection:
            row = connection.execute("SELECT author_key FROM tracks WHERE id = ?", (track_id,)).fetchone()
            if row is None:
                raise TrackError("NOT_FOUND", 404)
            if row["author_key"] == key_hash:
                raise TrackError("OWN_TRACK", 403)
            if not self._limiter.allow(connection, [("vote:key", key_hash), ("vote:net", net_hash)], now=now):
                raise TrackError("RATE_LIMITED", 429)
            if value == 0:
                connection.execute("DELETE FROM votes WHERE track_id = ? AND voter_key = ?", (track_id, key_hash))
            else:
                connection.execute(
                    """INSERT INTO votes(track_id, voter_key, voter_net, value, updated_at) VALUES (?, ?, ?, ?, ?)
                       ON CONFLICT(track_id, voter_key) DO UPDATE SET value = excluded.value,
                       voter_net = excluded.voter_net, updated_at = excluded.updated_at""",
                    (track_id, key_hash, net_hash, value, _iso(now)),
                )
            return self._track_json(connection, track_id)

    def delete(self, track_id: str, install_key: str | None) -> None:
        key_hash = self._require_key(install_key)
        with self._transaction() as connection:
            row = connection.execute("SELECT author_key FROM tracks WHERE id = ?", (track_id,)).fetchone()
            if row is None:
                raise TrackError("NOT_FOUND", 404)
            if row["author_key"] != key_hash:
                raise TrackError("NOT_AUTHOR", 403)
            if not self._limiter.allow(connection, [("delete:key", key_hash)], now=self._clock()):
                raise TrackError("RATE_LIMITED", 429)
            connection.execute("DELETE FROM tracks WHERE id = ?", (track_id,))

    def takedown(self, track_id: str) -> bool:
        """Removal by the operator after a legal notice (the only manual action there is)."""
        with self._transaction() as connection:
            return connection.execute("DELETE FROM tracks WHERE id = ?", (track_id,)).rowcount > 0
