"""Proposals of new canyons and of changes to them, their review by the operator, the revisions.

Anyone using the app can propose a new canyon or a change to a canyon added in the app (never to a
sheet of the site, which stays the source of its own canyons). Nothing is published before the
operator approves it in the review tool (`dcserver admin`); every approved state of a sheet is kept
as a revision, so a sheet can go back to any earlier version. The catalog job then puts the
published sheets into the catalog, where the app shows them like the site's canyons.
"""
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
from typing import Any

from .. import db, netid
from ..ratelimit import RateLimiter
from . import content as sheets
from .content import FIRST_APP_CANYON_ID, ContentError

LICENCE = "CC0-1.0"
"""Texts and points are given to the public domain, like the shared tracks."""
MAX_PENDING_PER_AUTHOR = 20
MINE_LIMIT = 100
REJECTION_REASONS = ("DUPLICATE", "EXISTS_ON_SITE", "FORBIDDEN", "INCOMPLETE", "INCORRECT", "NOT_A_CANYON", "OTHER", "BLOCKED")
NOTE_MAX = 500


class ProposalError(Exception):
    def __init__(self, code: str, status: int, field: str | None = None) -> None:
        super().__init__(code)
        self.code = code
        self.status = status
        self.field = field


def _iso(moment: float) -> str:
    return datetime.fromtimestamp(moment, timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def _note(value: Any) -> str | None:
    if value is None:
        return None
    if not isinstance(value, str):
        raise ProposalError("BAD_NOTE", 400)
    text = " ".join(value.split())
    return text[:NOTE_MAX] or None


class CanyonService:
    def __init__(
        self,
        database_path: Path,
        pepper: bytes,
        limiter: RateLimiter | None = None,
        clock: Callable[[], float] = time.time,
        id_floor: Callable[[], int] = lambda: FIRST_APP_CANYON_ID - 1,
        locate: Callable[[float, float], dict[str, str] | None] = lambda latitude, longitude: None,
    ) -> None:
        """[id_floor] gives the highest id already used elsewhere (the repository's copy of the
        sheets): a new canyon is numbered above it even if this database was lost. [locate] gives
        the place (pays, region...) of the catalog canyon nearest to a point, None far from all."""
        self._connection = db.connect(database_path)
        self._lock = threading.Lock()
        self._pepper = pepper
        self._limiter = limiter or RateLimiter()
        self._clock = clock
        self._id_floor = id_floor
        self._locate = locate

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

    @contextlib.contextmanager
    def _read(self) -> Iterator[sqlite3.Connection]:
        with self._lock:
            yield self._connection

    # Proposals sent from the app

    def submit(self, body: Any, install_key: str | None, ip: str) -> dict:
        key_hash = netid.hash_install_key(self._pepper, install_key)
        if key_hash is None:
            raise ProposalError("MISSING_KEY", 401)
        if not isinstance(body, dict):
            raise ProposalError("BAD_REQUEST", 400)
        if body.get("licence") != LICENCE:
            raise ProposalError("LICENCE_REQUIRED", 400)
        kind = body.get("kind")
        canyon_id = body.get("canyonId")
        if kind not in ("NEW", "EDIT"):
            raise ProposalError("BAD_KIND", 400)
        if kind == "NEW" and canyon_id is not None:
            raise ProposalError("BAD_REQUEST", 400)
        if kind == "EDIT" and (isinstance(canyon_id, bool) or not isinstance(canyon_id, int)):
            raise ProposalError("BAD_REQUEST", 400)
        sheet = self._checked(body.get("content"), publishing=False)
        net_hash = netid.hash_network(self._pepper, ip)
        now = self._clock()
        with self._transaction() as connection:
            if connection.execute("SELECT 1 FROM blocked_authors WHERE author_key = ?", (key_hash,)).fetchone():
                raise ProposalError("BLOCKED", 403)
            base_revision = None
            if kind == "EDIT":
                if canyon_id < FIRST_APP_CANYON_ID:
                    raise ProposalError("NOT_EDITABLE", 403)
                row = connection.execute("SELECT current_revision, status FROM app_canyons WHERE id = ?", (canyon_id,)).fetchone()
                if row is None or row["status"] != "PUBLISHED":
                    raise ProposalError("UNKNOWN_CANYON", 404)
                base_revision = row["current_revision"]
                if self._content(connection, canyon_id, base_revision) == sheet:
                    raise ProposalError("NO_CHANGE", 422)
            pending = connection.execute(
                "SELECT COUNT(*) FROM canyon_proposals WHERE author_key = ? AND status = 'PENDING'", (key_hash,)
            ).fetchone()[0]
            if pending >= MAX_PENDING_PER_AUTHOR:
                raise ProposalError("TOO_MANY_PENDING", 429)
            if not self._limiter.allow(connection, [("proposal:key", key_hash), ("proposal:net", net_hash)], now=now):
                raise ProposalError("RATE_LIMITED", 429)
            proposal_id = "p" + secrets.token_urlsafe(9)
            connection.execute(
                """INSERT INTO canyon_proposals(id, kind, canyon_id, base_revision, content, status, created_at,
                   author_key, author_net) VALUES (?, ?, ?, ?, ?, 'PENDING', ?, ?, ?)""",
                (proposal_id, kind, canyon_id, base_revision, sheets.canonical(sheet), _iso(now), key_hash, net_hash),
            )
            row = connection.execute("SELECT * FROM canyon_proposals WHERE id = ?", (proposal_id,)).fetchone()
            return self._public(row)

    def mine(self, install_key: str | None) -> dict:
        key_hash = netid.hash_install_key(self._pepper, install_key)
        if key_hash is None:
            raise ProposalError("MISSING_KEY", 401)
        with self._read() as connection:
            rows = connection.execute(
                "SELECT * FROM canyon_proposals WHERE author_key = ? ORDER BY created_at DESC, rowid DESC LIMIT ?", (key_hash, MINE_LIMIT)
            ).fetchall()
            return {"proposals": [self._public(row) for row in rows]}

    def withdraw(self, proposal_id: str, install_key: str | None) -> None:
        key_hash = netid.hash_install_key(self._pepper, install_key)
        if key_hash is None:
            raise ProposalError("MISSING_KEY", 401)
        with self._transaction() as connection:
            row = connection.execute("SELECT author_key, status FROM canyon_proposals WHERE id = ?", (proposal_id,)).fetchone()
            if row is None:
                raise ProposalError("NOT_FOUND", 404)
            if row["author_key"] != key_hash:
                raise ProposalError("NOT_AUTHOR", 403)
            if row["status"] != "PENDING":
                raise ProposalError("ALREADY_DECIDED", 409)
            connection.execute(
                "UPDATE canyon_proposals SET status = 'WITHDRAWN', decided_at = ? WHERE id = ?", (_iso(self._clock()), proposal_id)
            )

    @staticmethod
    def _public(row: sqlite3.Row) -> dict:
        """What the author sees of a proposal (never the keys or the network)."""
        sheet = json.loads(row["content"])
        return {
            "id": row["id"],
            "kind": row["kind"],
            "canyonId": row["canyon_id"],
            "name": sheet.get("nom", ""),
            "status": row["status"],
            "createdAt": row["created_at"],
            "decidedAt": row["decided_at"],
            "reason": row["reason"],
            "note": row["note"],
        }

    # Review by the operator

    def proposals(self, status: str | None = "PENDING", limit: int = 200) -> list[dict]:
        with self._read() as connection:
            if status:
                rows = connection.execute(
                    "SELECT * FROM canyon_proposals WHERE status = ? ORDER BY created_at DESC, rowid DESC LIMIT ?", (status, limit)
                ).fetchall()
            else:
                rows = connection.execute(
                    "SELECT * FROM canyon_proposals WHERE status != 'PENDING' ORDER BY COALESCE(decided_at, created_at) DESC LIMIT ?",
                    (limit,),
                ).fetchall()
            return [self._summary(connection, row) for row in rows]

    def _summary(self, connection: sqlite3.Connection, row: sqlite3.Row) -> dict:
        sheet = json.loads(row["content"])
        summary = {
            **self._public(row),
            "baseRevision": row["base_revision"],
            "revision": row["revision"],
            "author": row["author_key"][:12],
            "points": sheet.get("points", []),
        }
        if row["kind"] == "EDIT" and row["canyon_id"] is not None:
            current = connection.execute("SELECT current_revision FROM app_canyons WHERE id = ?", (row["canyon_id"],)).fetchone()
            summary["currentRevision"] = current["current_revision"] if current else None
        return summary

    def proposal(self, proposal_id: str) -> dict:
        with self._read() as connection:
            row = connection.execute("SELECT * FROM canyon_proposals WHERE id = ?", (proposal_id,)).fetchone()
            if row is None:
                raise ProposalError("NOT_FOUND", 404)
            sheet = json.loads(row["content"])
            result = {**self._summary(connection, row), "content": sheet}
            current = None
            if row["kind"] == "EDIT":
                canyon = connection.execute("SELECT * FROM app_canyons WHERE id = ?", (row["canyon_id"],)).fetchone()
                if canyon is not None:
                    current = self._content(connection, canyon["id"], canyon["current_revision"])
                    base = self._content(connection, canyon["id"], row["base_revision"]) if row["base_revision"] else None
                    result["current"] = current
                    result["canyonStatus"] = canyon["status"]
                    # Changes made by this proposal, and whether others were published since it was written.
                    result["changes"] = sheets.diff(base, sheet)
                    result["diffWithCurrent"] = sheets.diff(current, sheet)
            else:
                result["changes"] = sheets.diff(None, sheet)
            author = row["author_key"]
            counts = {
                status: count
                for status, count in connection.execute(
                    "SELECT status, COUNT(*) FROM canyon_proposals WHERE author_key = ? GROUP BY status", (author,)
                )
            }
            blocked = connection.execute("SELECT 1 FROM blocked_authors WHERE author_key = ?", (author,)).fetchone() is not None
            result["authorStats"] = {"counts": counts, "blocked": blocked}
            same_network = connection.execute(
                "SELECT COUNT(DISTINCT author_key) FROM canyon_proposals WHERE author_net = ?", (row["author_net"],)
            ).fetchone()[0]
            result["authorStats"]["authorsOnSameNetwork"] = same_network
            # Other proposals near this one (a canyon proposed twice).
            others = []
            for other in connection.execute(
                "SELECT * FROM canyon_proposals WHERE id != ? AND status IN ('PENDING', 'APPROVED') ORDER BY created_at DESC LIMIT 500",
                (proposal_id,),
            ):
                distance = _nearest(sheet.get("points", []), json.loads(other["content"]).get("points", []))
                if distance is not None and distance < 5_000:
                    others.append({**self._public(other), "distanceM": round(distance)})
            result["nearbyProposals"] = sorted(others, key=lambda o: o["distanceM"])[:20]
            return result

    def approve(self, proposal_id: str, content: Any | None = None, note: Any = None) -> dict:
        """Publishes the proposal, as written or as corrected by the operator ([content])."""
        note_text = _note(note)
        now = self._clock()
        with self._transaction() as connection:
            row = connection.execute("SELECT * FROM canyon_proposals WHERE id = ?", (proposal_id,)).fetchone()
            if row is None:
                raise ProposalError("NOT_FOUND", 404)
            if row["status"] != "PENDING":
                raise ProposalError("ALREADY_DECIDED", 409)
            sheet = self._checked(content if content is not None else json.loads(row["content"]))
            if row["kind"] == "NEW":
                canyon_id = self._new_canyon(connection, sheet, now, "PROPOSAL", proposal_id, note_text)
                revision = 1
            else:
                canyon_id = row["canyon_id"]
                revision = self._new_revision(connection, canyon_id, sheet, now, "PROPOSAL", proposal_id, note_text)
            connection.execute(
                "UPDATE canyon_proposals SET status = 'APPROVED', decided_at = ?, canyon_id = ?, revision = ?, note = ? WHERE id = ?",
                (_iso(now), canyon_id, revision, note_text, proposal_id),
            )
            return self._canyon(connection, canyon_id)

    def reject(self, proposal_id: str, reason: Any, note: Any = None) -> dict:
        if reason not in REJECTION_REASONS:
            raise ProposalError("BAD_REASON", 400)
        note_text = _note(note)
        with self._transaction() as connection:
            row = connection.execute("SELECT status FROM canyon_proposals WHERE id = ?", (proposal_id,)).fetchone()
            if row is None:
                raise ProposalError("NOT_FOUND", 404)
            if row["status"] != "PENDING":
                raise ProposalError("ALREADY_DECIDED", 409)
            connection.execute(
                "UPDATE canyon_proposals SET status = 'REJECTED', decided_at = ?, reason = ?, note = ? WHERE id = ?",
                (_iso(self._clock()), reason, note_text, proposal_id),
            )
            return self._public(connection.execute("SELECT * FROM canyon_proposals WHERE id = ?", (proposal_id,)).fetchone())

    def block_author(self, proposal_id: str) -> int:
        """Refuses every later proposal of the author of [proposal_id] and their pending ones; returns
        how many pending proposals were rejected."""
        now = _iso(self._clock())
        with self._transaction() as connection:
            row = connection.execute("SELECT author_key FROM canyon_proposals WHERE id = ?", (proposal_id,)).fetchone()
            if row is None:
                raise ProposalError("NOT_FOUND", 404)
            connection.execute(
                "INSERT INTO blocked_authors(author_key, created_at) VALUES (?, ?) ON CONFLICT(author_key) DO NOTHING",
                (row["author_key"], now),
            )
            return connection.execute(
                "UPDATE canyon_proposals SET status = 'REJECTED', decided_at = ?, reason = 'BLOCKED' WHERE author_key = ? AND status = 'PENDING'",
                (now, row["author_key"]),
            ).rowcount

    def unblock_author(self, proposal_id: str) -> bool:
        with self._transaction() as connection:
            row = connection.execute("SELECT author_key FROM canyon_proposals WHERE id = ?", (proposal_id,)).fetchone()
            if row is None:
                raise ProposalError("NOT_FOUND", 404)
            return connection.execute("DELETE FROM blocked_authors WHERE author_key = ?", (row["author_key"],)).rowcount > 0

    # The canyons added in the app

    def canyons(self) -> list[dict]:
        with self._read() as connection:
            rows = connection.execute("SELECT * FROM app_canyons ORDER BY id").fetchall()
            result = []
            for row in rows:
                sheet = self._content(connection, row["id"], row["current_revision"])
                pending = connection.execute(
                    "SELECT COUNT(*) FROM canyon_proposals WHERE canyon_id = ? AND status = 'PENDING'", (row["id"],)
                ).fetchone()[0]
                result.append(
                    {
                        "id": row["id"],
                        "name": sheet.get("nom", ""),
                        "pays": sheet.get("pays"),
                        "departement": sheet.get("departement"),
                        "status": row["status"],
                        "revision": row["current_revision"],
                        "createdAt": row["created_at"],
                        "updatedAt": row["updated_at"],
                        "pendingProposals": pending,
                        "points": sheet.get("points", []),
                    }
                )
            return result

    def canyon(self, canyon_id: int) -> dict:
        with self._read() as connection:
            return self._canyon(connection, canyon_id)

    def _canyon(self, connection: sqlite3.Connection, canyon_id: int) -> dict:
        row = connection.execute("SELECT * FROM app_canyons WHERE id = ?", (canyon_id,)).fetchone()
        if row is None:
            raise ProposalError("UNKNOWN_CANYON", 404)
        revisions = [
            {
                "revision": r["revision"],
                "createdAt": r["created_at"],
                "origin": r["origin"],
                "proposalId": r["proposal_id"],
                "note": r["note"],
            }
            for r in connection.execute(
                "SELECT revision, created_at, origin, proposal_id, note FROM app_canyon_revisions WHERE canyon_id = ? ORDER BY revision DESC",
                (canyon_id,),
            )
        ]
        pending = [
            self._public(p)
            for p in connection.execute(
                "SELECT * FROM canyon_proposals WHERE canyon_id = ? AND status = 'PENDING' ORDER BY created_at", (canyon_id,)
            )
        ]
        return {
            "id": row["id"],
            "status": row["status"],
            "revision": row["current_revision"],
            "createdAt": row["created_at"],
            "updatedAt": row["updated_at"],
            "content": self._content(connection, canyon_id, row["current_revision"]),
            "revisions": revisions,
            "pendingProposals": pending,
        }

    def revision(self, canyon_id: int, revision: int) -> dict:
        with self._read() as connection:
            sheet = self._content(connection, canyon_id, revision)
            if sheet is None:
                raise ProposalError("NOT_FOUND", 404)
            current = connection.execute("SELECT current_revision FROM app_canyons WHERE id = ?", (canyon_id,)).fetchone()
            current_sheet = self._content(connection, canyon_id, current["current_revision"])
            return {"canyonId": canyon_id, "revision": revision, "content": sheet, "diffWithCurrent": sheets.diff(current_sheet, sheet)}

    def create(self, content: Any, note: Any = None) -> dict:
        """A canyon added by the operator directly."""
        sheet = self._checked(content)
        with self._transaction() as connection:
            canyon_id = self._new_canyon(connection, sheet, self._clock(), "ADMIN", None, _note(note))
            return self._canyon(connection, canyon_id)

    def edit(self, canyon_id: int, content: Any, note: Any = None) -> dict:
        sheet = self._checked(content)
        with self._transaction() as connection:
            self._new_revision(connection, canyon_id, sheet, self._clock(), "ADMIN", None, _note(note))
            return self._canyon(connection, canyon_id)

    def restore(self, canyon_id: int, revision: int, note: Any = None) -> dict:
        """Publishes again the sheet of an earlier [revision], as a new revision."""
        with self._transaction() as connection:
            sheet = self._content(connection, canyon_id, revision)
            if sheet is None:
                raise ProposalError("NOT_FOUND", 404)
            self._new_revision(connection, canyon_id, sheet, self._clock(), "RESTORE", None, _note(note) or f"Retour à la version {revision}")
            return self._canyon(connection, canyon_id)

    def set_hidden(self, canyon_id: int, hidden: bool) -> dict:
        """A hidden canyon leaves the catalog (and the phones at their next update); its id stays taken."""
        with self._transaction() as connection:
            updated = connection.execute(
                "UPDATE app_canyons SET status = ?, updated_at = ? WHERE id = ?",
                ("HIDDEN" if hidden else "PUBLISHED", _iso(self._clock()), canyon_id),
            ).rowcount
            if not updated:
                raise ProposalError("UNKNOWN_CANYON", 404)
            return self._canyon(connection, canyon_id)

    # Publication

    def export(self) -> dict:
        """The published sheets, as the catalog generator reads them (--app-canyons)."""
        with self._read() as connection:
            canyons = [
                {
                    "id": row["id"],
                    "revision": row["current_revision"],
                    "updatedAt": row["updated_at"],
                    "content": self._content(connection, row["id"], row["current_revision"]),
                }
                for row in connection.execute("SELECT * FROM app_canyons WHERE status = 'PUBLISHED' ORDER BY id")
            ]
            hidden = [row[0] for row in connection.execute("SELECT id FROM app_canyons WHERE status = 'HIDDEN' ORDER BY id")]
        return {"format": 1, "licence": LICENCE, "canyons": canyons, "hidden": hidden}

    def pending_count(self) -> int:
        with self._read() as connection:
            return connection.execute("SELECT COUNT(*) FROM canyon_proposals WHERE status = 'PENDING'").fetchone()[0]

    # Helpers

    def _checked(self, content: Any, publishing: bool = True) -> dict:
        """The normalised sheet, its blank place fields taken from the canyon nearest to its point.
        A published sheet needs its country (the zone filters of the app go by it): when the point is
        far from every canyon, the operator writes it."""
        try:
            sheet = sheets.normalize(content)
        except ContentError as exc:
            raise ProposalError(f"INVALID_{exc.code}", 422, exc.field) from exc
        point = sheets.anchor(sheet)
        if point is not None and any(not sheet.get(field) for field in sheets.PLACE_FIELDS):
            sheet = sheets.with_place(sheet, self._locate(point["latitude"], point["longitude"]))
        if publishing and not sheet.get("pays"):
            raise ProposalError("INVALID_REQUIRED", 422, "pays")
        return sheet

    @staticmethod
    def _content(connection: sqlite3.Connection, canyon_id: int, revision: int | None) -> dict | None:
        if revision is None:
            return None
        row = connection.execute(
            "SELECT content FROM app_canyon_revisions WHERE canyon_id = ? AND revision = ?", (canyon_id, revision)
        ).fetchone()
        return json.loads(row["content"]) if row else None

    def _new_canyon(
        self, connection: sqlite3.Connection, sheet: dict, now: float, origin: str, proposal_id: str | None, note: str | None
    ) -> int:
        highest = connection.execute("SELECT MAX(id) FROM app_canyons").fetchone()[0] or 0
        canyon_id = max(FIRST_APP_CANYON_ID - 1, highest, self._id_floor()) + 1
        stamp = _iso(now)
        connection.execute(
            "INSERT INTO app_canyons(id, status, current_revision, created_at, updated_at) VALUES (?, 'PUBLISHED', 1, ?, ?)",
            (canyon_id, stamp, stamp),
        )
        connection.execute(
            "INSERT INTO app_canyon_revisions(canyon_id, revision, content, created_at, origin, proposal_id, note) VALUES (?, 1, ?, ?, ?, ?, ?)",
            (canyon_id, sheets.canonical(sheet), stamp, origin, proposal_id, note),
        )
        return canyon_id

    def _new_revision(
        self,
        connection: sqlite3.Connection,
        canyon_id: int,
        sheet: dict,
        now: float,
        origin: str,
        proposal_id: str | None,
        note: str | None,
    ) -> int:
        row = connection.execute("SELECT current_revision FROM app_canyons WHERE id = ?", (canyon_id,)).fetchone()
        if row is None:
            raise ProposalError("UNKNOWN_CANYON", 404)
        if self._content(connection, canyon_id, row["current_revision"]) == sheet:
            raise ProposalError("NO_CHANGE", 422)
        highest = connection.execute("SELECT MAX(revision) FROM app_canyon_revisions WHERE canyon_id = ?", (canyon_id,)).fetchone()[0]
        revision = highest + 1
        stamp = _iso(now)
        connection.execute(
            "INSERT INTO app_canyon_revisions(canyon_id, revision, content, created_at, origin, proposal_id, note) VALUES (?, ?, ?, ?, ?, ?, ?)",
            (canyon_id, revision, sheets.canonical(sheet), stamp, origin, proposal_id, note),
        )
        connection.execute("UPDATE app_canyons SET current_revision = ?, updated_at = ? WHERE id = ?", (revision, stamp, canyon_id))
        return revision


def _nearest(points: list[dict], others: list[dict]) -> float | None:
    distances = [
        sheets.distance_m(a["latitude"], a["longitude"], b["latitude"], b["longitude"]) for a in points for b in others
    ]
    return min(distances) if distances else None
