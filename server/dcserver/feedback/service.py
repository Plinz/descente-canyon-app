"""The idea box of the app: bug reports, ideas and change requests, and the operator's replies.

A message is sent with the installation key, like every contribution; the app adds the technical
details the person left attached (app and Android versions, device, language) and, when the person
is logged in to descente-canyon.com and left it attached, the account name and its e-mail address.
The author sees what became of their messages (`mine`): the status the operator gave and the reply,
never the keys, the network nor anything of other installations. The author can delete a message
(it is then gone from the server); a closed message is deleted a year after it was closed.
"""
from __future__ import annotations

import contextlib
import json
import re
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

KINDS = ("BUG", "IDEA", "CHANGE")
STATUSES = ("NEW", "READ", "PLANNED", "DONE", "DECLINED")
OPEN_STATUSES = ("NEW", "READ", "PLANNED")
CLOSED_STATUSES = ("DONE", "DECLINED")
TEXT_MIN = 10
TEXT_MAX = 4000
REPLY_MAX = 4000
TECHNICAL_FIELDS = ("appVersion", "versionCode", "android", "device", "language")
TECHNICAL_VALUE_MAX = 100
USERNAME_MAX = 100
EMAIL_MAX = 254
EMAIL_PATTERN = re.compile(r"^[^@\s]+@[^@\s]+\.[^@\s]+$")
MINE_LIMIT = 100
MAX_OPEN_PER_AUTHOR = 30
RETENTION_S = 365 * 24 * 3600.0
"""A closed message (done or declined) is deleted this long after it was closed."""
PURGE_EVERY_S = 6 * 3600.0
_CONTROL = re.compile(r"[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]")


class FeedbackError(Exception):
    def __init__(self, code: str, status: int, field: str | None = None) -> None:
        super().__init__(code)
        self.code = code
        self.status = status
        self.field = field


def _iso(moment: float) -> str:
    return datetime.fromtimestamp(moment, timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def clean_text(value: Any, field: str, maximum: int, minimum: int = 0) -> str:
    """A text as written, its line ends unified, control characters and trailing blanks dropped."""
    if not isinstance(value, str):
        raise FeedbackError("BAD_REQUEST", 400, field)
    text = _CONTROL.sub("", value.replace("\r\n", "\n").replace("\r", "\n"))
    text = "\n".join(line.rstrip() for line in text.split("\n"))
    text = re.sub(r"\n{3,}", "\n\n", text).strip()
    if len(text) < minimum:
        raise FeedbackError("TEXT_TOO_SHORT", 422, field)
    if len(text) > maximum:
        raise FeedbackError("TEXT_TOO_LONG", 422, field)
    return text


def _technical(value: Any) -> dict[str, str] | None:
    if value is None:
        return None
    if not isinstance(value, dict):
        raise FeedbackError("BAD_REQUEST", 400, "technical")
    result = {}
    for key in TECHNICAL_FIELDS:
        item = value.get(key)
        if item is None:
            continue
        if isinstance(item, bool) or not isinstance(item, (str, int)):
            raise FeedbackError("BAD_REQUEST", 400, "technical")
        text = " ".join(_CONTROL.sub("", str(item)).split())[:TECHNICAL_VALUE_MAX]
        if text:
            result[key] = text
    return result or None


def _contact(value: Any) -> tuple[str | None, str | None]:
    if value is None:
        return None, None
    if not isinstance(value, dict):
        raise FeedbackError("BAD_REQUEST", 400, "contact")
    username = value.get("username")
    email = value.get("email")
    if username is not None:
        if not isinstance(username, str):
            raise FeedbackError("BAD_REQUEST", 400, "contact")
        username = " ".join(_CONTROL.sub("", username).split())[:USERNAME_MAX] or None
    if email is not None:
        if not isinstance(email, str):
            raise FeedbackError("BAD_REQUEST", 400, "contact")
        email = email.strip()
        if not email:
            email = None
        elif len(email) > EMAIL_MAX or not EMAIL_PATTERN.match(email):
            raise FeedbackError("BAD_EMAIL", 422, "contact")
    return username, email


class FeedbackService:
    def __init__(
        self,
        database_path: Path,
        pepper: bytes,
        limiter: RateLimiter | None = None,
        clock: Callable[[], float] = time.time,
    ) -> None:
        self._connection = db.connect(database_path)
        self._lock = threading.Lock()
        self._pepper = pepper
        self._limiter = limiter or RateLimiter()
        self._clock = clock
        self._purged_at: float | None = None

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

    def _key(self, install_key: str | None) -> str:
        key_hash = netid.hash_install_key(self._pepper, install_key)
        if key_hash is None:
            raise FeedbackError("MISSING_KEY", 401)
        return key_hash

    # Sent from the app

    def submit(self, body: Any, install_key: str | None, ip: str) -> dict:
        key_hash = self._key(install_key)
        if not isinstance(body, dict):
            raise FeedbackError("BAD_REQUEST", 400)
        kind = body.get("kind")
        if kind not in KINDS:
            raise FeedbackError("BAD_KIND", 400, "kind")
        text = clean_text(body.get("text"), "text", TEXT_MAX, TEXT_MIN)
        technical = _technical(body.get("technical"))
        username, email = _contact(body.get("contact"))
        net_hash = netid.hash_network(self._pepper, ip)
        now = self._clock()
        with self._transaction() as connection:
            if self._purged_at is None or now - self._purged_at >= PURGE_EVERY_S:
                self._purged_at = now
                self._purge(connection, now)
            if connection.execute("SELECT 1 FROM blocked_authors WHERE author_key = ?", (key_hash,)).fetchone():
                raise FeedbackError("BLOCKED", 403)
            open_count = connection.execute(
                f"SELECT COUNT(*) FROM feedback WHERE author_key = ? AND status IN ({','.join('?' * len(OPEN_STATUSES))})",
                (key_hash, *OPEN_STATUSES),
            ).fetchone()[0]
            if open_count >= MAX_OPEN_PER_AUTHOR:
                raise FeedbackError("TOO_MANY_OPEN", 429)
            if not self._limiter.allow(connection, [("feedback:key", key_hash), ("feedback:net", net_hash)], now=now):
                raise FeedbackError("RATE_LIMITED", 429)
            feedback_id = "f" + secrets.token_urlsafe(9)
            connection.execute(
                """INSERT INTO feedback(id, kind, text, technical, contact_username, contact_email, status,
                   created_at, updated_at, author_key, author_net) VALUES (?, ?, ?, ?, ?, ?, 'NEW', ?, ?, ?, ?)""",
                (
                    feedback_id,
                    kind,
                    text,
                    json.dumps(technical, ensure_ascii=False, sort_keys=True) if technical else None,
                    username,
                    email,
                    _iso(now),
                    _iso(now),
                    key_hash,
                    net_hash,
                ),
            )
            return self._public(connection.execute("SELECT * FROM feedback WHERE id = ?", (feedback_id,)).fetchone())

    def mine(self, install_key: str | None) -> dict:
        key_hash = self._key(install_key)
        with self._read() as connection:
            rows = connection.execute(
                "SELECT * FROM feedback WHERE author_key = ? ORDER BY created_at DESC, rowid DESC LIMIT ?", (key_hash, MINE_LIMIT)
            ).fetchall()
            return {"feedback": [self._public(row) for row in rows]}

    def delete_mine(self, feedback_id: str, install_key: str | None) -> None:
        key_hash = self._key(install_key)
        with self._transaction() as connection:
            row = connection.execute("SELECT author_key FROM feedback WHERE id = ?", (feedback_id,)).fetchone()
            if row is None:
                raise FeedbackError("NOT_FOUND", 404)
            if row["author_key"] != key_hash:
                raise FeedbackError("NOT_AUTHOR", 403)
            connection.execute("DELETE FROM feedback WHERE id = ?", (feedback_id,))

    @staticmethod
    def _public(row: sqlite3.Row) -> dict:
        """What the author sees of a message: never the keys, the network or the contact kept."""
        return {
            "id": row["id"],
            "kind": row["kind"],
            "text": row["text"],
            "status": row["status"],
            "createdAt": row["created_at"],
            "updatedAt": row["updated_at"],
            "reply": row["reply"],
            "repliedAt": row["replied_at"],
        }

    # Review tool

    def messages(self, status: str | None = "OPEN", limit: int = 300) -> list[dict]:
        if status == "OPEN":
            statuses = OPEN_STATUSES
        elif status == "CLOSED":
            statuses = CLOSED_STATUSES
        elif status in STATUSES:
            statuses = (status,)
        else:
            statuses = STATUSES
        with self._read() as connection:
            rows = connection.execute(
                f"SELECT * FROM feedback WHERE status IN ({','.join('?' * len(statuses))}) ORDER BY created_at DESC, rowid DESC LIMIT ?",
                (*statuses, limit),
            ).fetchall()
            return [self._summary(row) for row in rows]

    @staticmethod
    def _summary(row: sqlite3.Row) -> dict:
        first_line = row["text"].split("\n", 1)[0]
        return {
            "id": row["id"],
            "kind": row["kind"],
            "excerpt": first_line[:120],
            "status": row["status"],
            "createdAt": row["created_at"],
            "updatedAt": row["updated_at"],
            "replied": row["reply"] is not None,
            "hasContact": bool(row["contact_email"] or row["contact_username"]),
        }

    def get(self, feedback_id: str) -> dict:
        with self._read() as connection:
            row = connection.execute("SELECT * FROM feedback WHERE id = ?", (feedback_id,)).fetchone()
            if row is None:
                raise FeedbackError("NOT_FOUND", 404)
            return self._full(connection, row)

    @staticmethod
    def _full(connection: sqlite3.Connection, row: sqlite3.Row) -> dict:
        counts = dict(
            connection.execute("SELECT status, COUNT(*) FROM feedback WHERE author_key = ? GROUP BY status", (row["author_key"],)).fetchall()
        )
        blocked = connection.execute("SELECT 1 FROM blocked_authors WHERE author_key = ?", (row["author_key"],)).fetchone() is not None
        return {
            **FeedbackService._public(row),
            "technical": json.loads(row["technical"]) if row["technical"] else None,
            "contact": {"username": row["contact_username"], "email": row["contact_email"]}
            if row["contact_username"] or row["contact_email"]
            else None,
            "author": row["author_key"][:8],
            "authorStats": {"counts": counts, "blocked": blocked},
        }

    def update(self, feedback_id: str, status: Any = None, reply: Any = None) -> dict:
        """A new status and/or reply; an empty reply removes the one given before."""
        if status is not None and status not in STATUSES:
            raise FeedbackError("BAD_STATUS", 400, "status")
        if reply is not None and not isinstance(reply, str):
            raise FeedbackError("BAD_REQUEST", 400, "reply")
        now = self._clock()
        with self._transaction() as connection:
            row = connection.execute("SELECT * FROM feedback WHERE id = ?", (feedback_id,)).fetchone()
            if row is None:
                raise FeedbackError("NOT_FOUND", 404)
            new_status = status or row["status"]
            new_reply, replied_at = row["reply"], row["replied_at"]
            if reply is not None:
                text = clean_text(reply, "reply", REPLY_MAX)
                if text != (row["reply"] or ""):
                    new_reply, replied_at = (text, _iso(now)) if text else (None, None)
            # A reply is an answer: the message was read at least.
            if new_status == "NEW" and new_reply:
                new_status = "READ"
            if (new_status, new_reply) != (row["status"], row["reply"]):
                connection.execute(
                    "UPDATE feedback SET status = ?, reply = ?, replied_at = ?, updated_at = ? WHERE id = ?",
                    (new_status, new_reply, replied_at, _iso(now), feedback_id),
                )
            return self._full(connection, connection.execute("SELECT * FROM feedback WHERE id = ?", (feedback_id,)).fetchone())

    def delete(self, feedback_id: str) -> None:
        with self._transaction() as connection:
            if connection.execute("DELETE FROM feedback WHERE id = ?", (feedback_id,)).rowcount == 0:
                raise FeedbackError("NOT_FOUND", 404)

    def block_author(self, feedback_id: str) -> int:
        """Blocks the installation that sent [feedback_id] (its proposals too); its open messages are declined."""
        now = _iso(self._clock())
        with self._transaction() as connection:
            row = connection.execute("SELECT author_key FROM feedback WHERE id = ?", (feedback_id,)).fetchone()
            if row is None:
                raise FeedbackError("NOT_FOUND", 404)
            connection.execute("INSERT OR IGNORE INTO blocked_authors(author_key, created_at) VALUES (?, ?)", (row["author_key"], now))
            return connection.execute(
                f"UPDATE feedback SET status = 'DECLINED', updated_at = ? WHERE author_key = ? AND status IN ({','.join('?' * len(OPEN_STATUSES))})",
                (now, row["author_key"], *OPEN_STATUSES),
            ).rowcount

    def unblock_author(self, feedback_id: str) -> bool:
        with self._transaction() as connection:
            row = connection.execute("SELECT author_key FROM feedback WHERE id = ?", (feedback_id,)).fetchone()
            if row is None:
                raise FeedbackError("NOT_FOUND", 404)
            return connection.execute("DELETE FROM blocked_authors WHERE author_key = ?", (row["author_key"],)).rowcount > 0

    def new_count(self) -> int:
        with self._read() as connection:
            return connection.execute("SELECT COUNT(*) FROM feedback WHERE status = 'NEW'").fetchone()[0]

    def purge(self) -> int:
        with self._transaction() as connection:
            return self._purge(connection, self._clock())

    @staticmethod
    def _purge(connection: sqlite3.Connection, now: float) -> int:
        """Deletes the messages closed for longer than [RETENTION_S]."""
        limit = _iso(now - RETENTION_S)
        return connection.execute(
            f"DELETE FROM feedback WHERE status IN ({','.join('?' * len(CLOSED_STATUSES))}) AND updated_at < ?",
            (*CLOSED_STATUSES, limit),
        ).rowcount
