"""Shared links: what a person shares from the app (an outing, a stay, a list of canyons), kept under
a short random code until a little after the day it is about.

The code is the only key to read it: no listing, no search. The author (the installation key that
created it, kept as an HMAC) can change it, so the same link shows the new times, or delete it.
Expired links are deleted along the way.
"""
from __future__ import annotations

import contextlib
import json
import secrets
import sqlite3
import threading
import time
from collections.abc import Callable, Iterator
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
from typing import Any

from .. import db, netid
from ..ratelimit import RateLimiter
from . import content

CODE_ALPHABET = "23456789abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ"
"""No 0/O, 1/l/I: a code read aloud or copied by hand stays right."""
CODE_LENGTH = 9
KEEP_AFTER_DAY = timedelta(days=30)
"""A dated outing or a stay is kept this long after its last day (the logbook, a late look)."""
KEEP_UNDATED = timedelta(days=365)
"""A route without date or a list, after its last change."""
MIN_KEEP = timedelta(days=7)
MAX_KEEP = timedelta(days=400)
PRUNE_EVERY_S = 6 * 3600.0


class ShareError(Exception):
    def __init__(self, code: str, status: int, field: str | None = None) -> None:
        super().__init__(code)
        self.code = code
        self.status = status
        self.field = field


def _iso(moment: datetime) -> str:
    return moment.astimezone(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def _parse_iso(value: str) -> datetime:
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


def expiry(last_day: date | None, now: datetime) -> datetime:
    if last_day is None:
        until = now + KEEP_UNDATED
    else:
        until = datetime.combine(last_day, datetime.min.time(), tzinfo=timezone.utc) + timedelta(days=1) + KEEP_AFTER_DAY
    return min(max(until, now + MIN_KEEP), now + MAX_KEEP)


class ShareService:
    def __init__(
        self,
        database_path: Path,
        pepper: bytes,
        known_canyon: Callable[[int], bool],
        public_url: str,
        limiter: RateLimiter | None = None,
        clock: Callable[[], float] = time.time,
    ) -> None:
        self._connection = db.connect(database_path)
        self._lock = threading.Lock()
        self._pepper = pepper
        self._known = known_canyon
        self._public_url = public_url.rstrip("/")
        self._limiter = limiter or RateLimiter()
        self._clock = clock
        self._pruned_at: float | None = None

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

    def _now(self) -> datetime:
        return datetime.fromtimestamp(self._clock(), timezone.utc)

    def _checked(self, body: Any) -> tuple[str, dict, date | None]:
        if not isinstance(body, dict):
            raise ShareError("BAD_REQUEST", 400)
        kind = body.get("kind")
        if kind not in content.KINDS:
            raise ShareError("BAD_KIND", 400)
        payload = body.get("payload")
        size = len(json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8"))
        if size > content.MAX_PAYLOAD_BYTES:
            raise ShareError("TOO_LARGE", 413)
        try:
            last_day = content.last_day(kind, payload, self._now().date(), self._known)
        except content.ShareContentError as exc:
            raise ShareError("INVALID_PAYLOAD", 400, exc.field) from exc
        return kind, payload, last_day

    def _json(self, row: sqlite3.Row, *, with_payload: bool) -> dict:
        result = {
            "code": row["code"],
            "kind": row["kind"],
            "version": row["version"],
            "url": f"{self._public_url}/s/{row['code']}",
            "updatedAt": row["updated_at"],
            "expiresAt": row["expires_at"],
        }
        if with_payload:
            result["payload"] = json.loads(row["payload"])
        return result

    def _maybe_prune(self, connection: sqlite3.Connection) -> None:
        moment = self._clock()
        if self._pruned_at is None or moment - self._pruned_at >= PRUNE_EVERY_S:
            self._pruned_at = moment
            connection.execute("DELETE FROM shares WHERE expires_at < ?", (_iso(self._now()),))

    def create(self, body: Any, install_key: str | None, ip: str) -> dict:
        key_hash = netid.hash_install_key(self._pepper, install_key)
        if key_hash is None:
            raise ShareError("MISSING_KEY", 401)
        kind, payload, last_day = self._checked(body)
        net_hash = netid.hash_network(self._pepper, ip)
        now = self._now()
        with self._transaction() as connection:
            if not self._limiter.allow(connection, [("share:key", key_hash), ("share:net", net_hash)], now=self._clock()):
                raise ShareError("RATE_LIMITED", 429)
            self._maybe_prune(connection)
            for _ in range(8):
                code = "".join(secrets.choice(CODE_ALPHABET) for _ in range(CODE_LENGTH))
                if connection.execute("SELECT 1 FROM shares WHERE code = ?", (code,)).fetchone() is None:
                    break
            else:  # pragma: no cover - 55^9 codes
                raise ShareError("BUSY", 503)
            connection.execute(
                "INSERT INTO shares(code, kind, payload, version, created_at, updated_at, expires_at, author_key, author_net)"
                " VALUES (?, ?, ?, 1, ?, ?, ?, ?, ?)",
                (
                    code,
                    kind,
                    json.dumps(payload, ensure_ascii=False, separators=(",", ":")),
                    _iso(now),
                    _iso(now),
                    _iso(expiry(last_day, now)),
                    key_hash,
                    net_hash,
                ),
            )
            row = connection.execute("SELECT * FROM shares WHERE code = ?", (code,)).fetchone()
        return self._json(row, with_payload=False)

    def update(self, code: str, body: Any, install_key: str | None, ip: str) -> dict:
        """The same link, new content: only its author can, and the version goes up for the readers."""
        key_hash = netid.hash_install_key(self._pepper, install_key)
        if key_hash is None:
            raise ShareError("MISSING_KEY", 401)
        kind, payload, last_day = self._checked(body)
        now = self._now()
        with self._transaction() as connection:
            row = self._live_row(connection, code)
            if row["author_key"] != key_hash:
                raise ShareError("NOT_AUTHOR", 403)
            if row["kind"] != kind:
                raise ShareError("BAD_KIND", 400)
            if not self._limiter.allow(connection, [("share-update:key", key_hash)], now=self._clock()):
                raise ShareError("RATE_LIMITED", 429)
            serialized = json.dumps(payload, ensure_ascii=False, separators=(",", ":"))
            if serialized != row["payload"]:
                connection.execute(
                    "UPDATE shares SET payload = ?, version = version + 1, updated_at = ?, expires_at = ? WHERE code = ?",
                    (serialized, _iso(now), _iso(expiry(last_day, now)), code),
                )
            row = connection.execute("SELECT * FROM shares WHERE code = ?", (code,)).fetchone()
        return self._json(row, with_payload=False)

    def get(self, code: str) -> dict:
        with self._lock:
            row = self._live_row(self._connection, code)
        return self._json(row, with_payload=True)

    def delete(self, code: str, install_key: str | None) -> None:
        key_hash = netid.hash_install_key(self._pepper, install_key)
        if key_hash is None:
            raise ShareError("MISSING_KEY", 401)
        with self._transaction() as connection:
            row = self._live_row(connection, code)
            if row["author_key"] != key_hash:
                raise ShareError("NOT_AUTHOR", 403)
            connection.execute("DELETE FROM shares WHERE code = ?", (code,))

    def takedown(self, code: str) -> bool:
        """Operator action: deletes a link whoever shared it."""
        with self._transaction() as connection:
            return connection.execute("DELETE FROM shares WHERE code = ?", (code,)).rowcount > 0

    def prune(self) -> int:
        with self._transaction() as connection:
            return connection.execute("DELETE FROM shares WHERE expires_at < ?", (_iso(self._now()),)).rowcount

    def _live_row(self, connection: sqlite3.Connection, code: str) -> sqlite3.Row:
        if len(code) != CODE_LENGTH or any(char not in CODE_ALPHABET for char in code):
            raise ShareError("NOT_FOUND", 404)
        row = connection.execute("SELECT * FROM shares WHERE code = ?", (code,)).fetchone()
        if row is None or _parse_iso(row["expires_at"]) < self._now():
            raise ShareError("NOT_FOUND", 404)
        return row
