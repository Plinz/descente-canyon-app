"""Daily quotas of contributions, kept in SQLite so a restart does not reset them."""
from __future__ import annotations

import sqlite3
import time
from dataclasses import dataclass

DAY_S = 24 * 3600.0


@dataclass(frozen=True)
class Quota:
    limit: int
    window_s: float = DAY_S


DEFAULT_QUOTAS: dict[str, Quota] = {
    "attempt:key": Quota(20),
    "attempt:net": Quota(60),
    "upload:key": Quota(5),
    "upload:net": Quota(40),
    "vote:key": Quota(150),
    "vote:net": Quota(400),
    "delete:key": Quota(30),
    "proposal:key": Quota(10),
    "proposal:net": Quota(30),
    "feedback:key": Quota(10),
    "feedback:net": Quota(30),
    "share:key": Quota(40),
    "share:net": Quota(150),
    "share-update:key": Quota(300),
}


PRUNE_EVERY_S = 6 * 3600.0
"""How often the events past every window are deleted, along the way of a contribution."""


class RateLimiter:
    def __init__(self, quotas: dict[str, Quota] | None = None, prune_every_s: float = PRUNE_EVERY_S) -> None:
        self.quotas = quotas or DEFAULT_QUOTAS
        self._prune_every_s = prune_every_s
        self._pruned_at: float | None = None

    def allow(self, connection: sqlite3.Connection, checks: list[tuple[str, str]], now: float | None = None) -> bool:
        """Records one event for every (kind, subject) when all of them are under their quota.
        Must run inside the caller's transaction, which also deletes the old events now and then
        (best effort: a transaction rolled back keeps them until the next time)."""
        now = time.time() if now is None else now
        if self._pruned_at is None or now - self._pruned_at >= self._prune_every_s:
            self._pruned_at = now
            self.prune(connection, now=now)
        for kind, subject in checks:
            quota = self.quotas[kind]
            count = connection.execute(
                "SELECT COUNT(*) FROM rate_events WHERE kind = ? AND subject = ? AND at > ?",
                (kind, subject, now - quota.window_s),
            ).fetchone()[0]
            if count >= quota.limit:
                return False
        connection.executemany(
            "INSERT INTO rate_events(kind, subject, at) VALUES (?, ?, ?)",
            [(kind, subject, now) for kind, subject in checks],
        )
        return True

    def prune(self, connection: sqlite3.Connection, now: float | None = None) -> int:
        now = time.time() if now is None else now
        longest = max(quota.window_s for quota in self.quotas.values())
        return connection.execute("DELETE FROM rate_events WHERE at < ?", (now - 2 * longest,)).rowcount
