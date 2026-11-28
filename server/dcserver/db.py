"""SQLite storage of the community tracks, their votes, the canyons added in the app and their
proposals, the messages of the idea box, the links shared from the app, and the rate-limit events."""
from __future__ import annotations

import sqlite3
from pathlib import Path

MIGRATIONS: list[str] = [
    # 1: tracks, votes, rate events
    """
    CREATE TABLE tracks (
        id TEXT PRIMARY KEY,
        canyon_id INTEGER NOT NULL,
        role TEXT NOT NULL,
        created_at TEXT NOT NULL,
        recorded_month TEXT,
        geometry TEXT NOT NULL,
        geometry_hash TEXT NOT NULL,
        point_count INTEGER NOT NULL,
        length_m INTEGER NOT NULL,
        ascent_m INTEGER,
        descent_m INTEGER,
        min_lon REAL NOT NULL,
        min_lat REAL NOT NULL,
        max_lon REAL NOT NULL,
        max_lat REAL NOT NULL,
        author_key TEXT NOT NULL,
        author_net TEXT NOT NULL
    );
    CREATE INDEX tracks_by_canyon ON tracks(canyon_id);
    CREATE UNIQUE INDEX tracks_unique_geometry ON tracks(canyon_id, geometry_hash);
    CREATE TABLE votes (
        track_id TEXT NOT NULL REFERENCES tracks(id) ON DELETE CASCADE,
        voter_key TEXT NOT NULL,
        voter_net TEXT NOT NULL,
        value INTEGER NOT NULL CHECK (value IN (-1, 1)),
        updated_at TEXT NOT NULL,
        PRIMARY KEY (track_id, voter_key)
    );
    CREATE TABLE rate_events (
        kind TEXT NOT NULL,
        subject TEXT NOT NULL,
        at REAL NOT NULL
    );
    CREATE INDEX rate_events_lookup ON rate_events(kind, subject, at);
    """,
    # 2: canyons added in the app, their revisions, the proposals waiting for the operator
    """
    CREATE TABLE app_canyons (
        id INTEGER PRIMARY KEY,
        status TEXT NOT NULL CHECK (status IN ('PUBLISHED', 'HIDDEN')),
        current_revision INTEGER NOT NULL,
        created_at TEXT NOT NULL,
        updated_at TEXT NOT NULL
    );
    CREATE TABLE app_canyon_revisions (
        canyon_id INTEGER NOT NULL REFERENCES app_canyons(id),
        revision INTEGER NOT NULL,
        content TEXT NOT NULL,
        created_at TEXT NOT NULL,
        origin TEXT NOT NULL CHECK (origin IN ('PROPOSAL', 'ADMIN', 'RESTORE')),
        proposal_id TEXT,
        note TEXT,
        PRIMARY KEY (canyon_id, revision)
    );
    CREATE TABLE canyon_proposals (
        id TEXT PRIMARY KEY,
        kind TEXT NOT NULL CHECK (kind IN ('NEW', 'EDIT')),
        canyon_id INTEGER,
        base_revision INTEGER,
        content TEXT NOT NULL,
        status TEXT NOT NULL CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN')),
        created_at TEXT NOT NULL,
        decided_at TEXT,
        revision INTEGER,
        reason TEXT,
        note TEXT,
        author_key TEXT NOT NULL,
        author_net TEXT NOT NULL
    );
    CREATE INDEX canyon_proposals_by_status ON canyon_proposals(status, created_at);
    CREATE INDEX canyon_proposals_by_author ON canyon_proposals(author_key, created_at);
    CREATE TABLE blocked_authors (
        author_key TEXT PRIMARY KEY,
        created_at TEXT NOT NULL
    );
    """,
    # 3: messages of the "idea or bug" box and the operator's replies
    """
    CREATE TABLE feedback (
        id TEXT PRIMARY KEY,
        kind TEXT NOT NULL CHECK (kind IN ('BUG', 'IDEA', 'CHANGE')),
        text TEXT NOT NULL,
        technical TEXT,
        contact_username TEXT,
        contact_email TEXT,
        status TEXT NOT NULL CHECK (status IN ('NEW', 'READ', 'PLANNED', 'DONE', 'DECLINED')),
        reply TEXT,
        replied_at TEXT,
        created_at TEXT NOT NULL,
        updated_at TEXT NOT NULL,
        author_key TEXT NOT NULL,
        author_net TEXT NOT NULL
    );
    CREATE INDEX feedback_by_status ON feedback(status, created_at);
    CREATE INDEX feedback_by_author ON feedback(author_key, created_at)
    """,
    # 4: links shared from the app (outing, stay, list of canyons), kept until a while after their day
    """
    CREATE TABLE shares (
        code TEXT PRIMARY KEY,
        kind TEXT NOT NULL CHECK (kind IN ('OUTING', 'STAY', 'LIST')),
        payload TEXT NOT NULL,
        version INTEGER NOT NULL,
        created_at TEXT NOT NULL,
        updated_at TEXT NOT NULL,
        expires_at TEXT NOT NULL,
        author_key TEXT NOT NULL,
        author_net TEXT NOT NULL
    );
    CREATE INDEX shares_by_expiry ON shares(expires_at);
    CREATE INDEX shares_by_author ON shares(author_key, created_at)
    """,
]


def connect(path: Path) -> sqlite3.Connection:
    path.parent.mkdir(parents=True, exist_ok=True)
    connection = sqlite3.connect(path, timeout=10, isolation_level=None, check_same_thread=False)
    connection.row_factory = sqlite3.Row
    connection.execute("PRAGMA journal_mode=WAL")
    connection.execute("PRAGMA foreign_keys=ON")
    connection.execute("PRAGMA busy_timeout=10000")
    migrate(connection)
    return connection


def migrate(connection: sqlite3.Connection) -> None:
    current = connection.execute("PRAGMA user_version").fetchone()[0]
    for version, script in enumerate(MIGRATIONS[current:], start=current + 1):
        # executescript() would commit on its own: run the statements one by one in one transaction.
        connection.execute("BEGIN IMMEDIATE")
        try:
            for statement in [part.strip() for part in script.split(";") if part.strip()]:
                connection.execute(statement)
            connection.execute(f"PRAGMA user_version={version}")
            connection.execute("COMMIT")
        except BaseException:
            connection.execute("ROLLBACK")
            raise
