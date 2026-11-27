from __future__ import annotations

from dcserver import db
from dcserver.ratelimit import DAY_S, Quota, RateLimiter


def count(connection) -> int:
    return connection.execute("SELECT COUNT(*) FROM rate_events").fetchone()[0]


def test_quota_counts_the_events_of_its_window(tmp_path):
    connection = db.connect(tmp_path / "db.sqlite3")
    limiter = RateLimiter({"vote:key": Quota(2)})
    now = 1_790_000_000.0
    assert limiter.allow(connection, [("vote:key", "a")], now=now)
    assert limiter.allow(connection, [("vote:key", "a")], now=now + 1)
    assert not limiter.allow(connection, [("vote:key", "a")], now=now + 2)
    assert limiter.allow(connection, [("vote:key", "b")], now=now + 3)
    assert limiter.allow(connection, [("vote:key", "a")], now=now + DAY_S + 1)


def test_old_events_are_deleted_along_the_way(tmp_path):
    """The events past every window are deleted now and then: the table does not grow forever."""
    connection = db.connect(tmp_path / "db.sqlite3")
    limiter = RateLimiter({"vote:key": Quota(100)}, prune_every_s=3600)
    start = 1_790_000_000.0
    for index in range(5):
        assert limiter.allow(connection, [("vote:key", "a")], now=start + index)
    assert count(connection) == 5
    # Within the pruning interval, nothing is deleted even past the windows.
    assert limiter.allow(connection, [("vote:key", "a")], now=start + 1800)
    assert count(connection) == 6
    # Three days later: the old events go, the new one stays.
    assert limiter.allow(connection, [("vote:key", "a")], now=start + 3 * DAY_S)
    assert count(connection) == 1
