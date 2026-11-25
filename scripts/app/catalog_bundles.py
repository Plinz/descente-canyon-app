"""Per-canyon bundles of the catalog and their hashes.

The app's server publishes one bundle per canyon (every row of that canyon in every catalog table,
plus the bibliography and regulation texts it refers to) under the hash of its content, and an index
of the hashes. The app compares the index with the hashes of its own catalog (shipped as an asset,
computed here from the APK's catalog.db) and applies the bundles that differ inside the open
database: a canyon that became forbidden shows at once, without waiting for an app update or a
restart.

The hash is the first 32 hex digits of the SHA-256 of the bundle's canonical JSON. The reading time
of the sheet (canyons.lastUpdated) stays out of it, so re-reading an unchanged sheet changes nothing,
and the point ids too (they are numbered by the generator and change from one build to the next).
"""
from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import sqlite3
from collections import defaultdict
from collections.abc import Iterator
from dataclasses import dataclass
from pathlib import Path

BUNDLE_SCHEMA = 1
HASH_DIGITS = 32

# (table, column holding the canyon id) of the rows that belong to one canyon.
PER_CANYON_TABLES = (
    ("canyons", "id"),
    ("geo_points", "canyonId"),
    ("canyon_bibliography", "canyonId"),
    ("canyon_regulations", "canyonId"),
    ("canyon_tracks", "canyonId"),
    ("watersheds", "canyonId"),
    ("search_index", "id"),
    ("canyon_chains", "fromCanyonId"),
)
EXCLUDED_COLUMNS = {"canyons": {"lastUpdated"}, "geo_points": {"id"}}
SHARED_TABLES = ("forum_users",)


@dataclass(frozen=True)
class Bundle:
    canyon_id: int
    hash: str
    data: bytes  # canonical JSON
    read_at_ms: int | None


def canonical(payload: object) -> bytes:
    return json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")


def content_hash(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()[:HASH_DIGITS]


def _rows(connection: sqlite3.Connection, table: str) -> list[dict]:
    excluded = EXCLUDED_COLUMNS.get(table, set())
    cursor = connection.execute(f'SELECT * FROM "{table}"')
    names = [d[0] for d in cursor.description]
    return [{n: v for n, v in zip(names, row) if n not in excluded} for row in cursor]


def _sorted(rows: list[dict]) -> list[dict]:
    return sorted(rows, key=lambda row: canonical(row))


def schema_of(connection: sqlite3.Connection) -> tuple[int, str]:
    version = connection.execute("PRAGMA user_version").fetchone()[0]
    identity = connection.execute("SELECT identity_hash FROM room_master_table WHERE id = 42").fetchone()[0]
    return version, identity


def canyon_bundles(db_path: Path) -> Iterator[Bundle]:
    connection = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True)
    try:
        by_table: dict[str, dict[int, list[dict]]] = {}
        for table, key in PER_CANYON_TABLES:
            grouped: dict[int, list[dict]] = defaultdict(list)
            for row in _rows(connection, table):
                grouped[int(row[key])].append(row)
            by_table[table] = grouped
        bibliography = {row["id"]: row for row in _rows(connection, "bibliography_entries")}
        regulations = {row["id"]: row for row in _rows(connection, "regulation_texts")}
        read_at = {row[0]: row[1] for row in connection.execute("SELECT id, lastUpdated FROM canyons")}
    finally:
        connection.close()
    for canyon_id in sorted(by_table["canyons"]):
        tables = {table: _sorted(by_table[table].get(canyon_id, [])) for table, _ in PER_CANYON_TABLES}
        bibliography_ids = sorted({row["bibliographyId"] for row in tables["canyon_bibliography"]})
        regulation_ids = sorted({row["regulationId"] for row in tables["canyon_regulations"]})
        tables["bibliography_entries"] = [bibliography[i] for i in bibliography_ids if i in bibliography]
        tables["regulation_texts"] = [regulations[i] for i in regulation_ids if i in regulations]
        data = canonical({"bundle": BUNDLE_SCHEMA, "canyonId": canyon_id, "tables": tables})
        yield Bundle(canyon_id, content_hash(data), data, read_at.get(canyon_id))


def shared_table(db_path: Path, table: str) -> tuple[str, bytes]:
    connection = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True)
    try:
        data = canonical({"bundle": BUNDLE_SCHEMA, "table": table, "rows": _sorted(_rows(connection, table))})
    finally:
        connection.close()
    return content_hash(data), data


def catalog_hashes(db_path: Path) -> dict:
    """The hashes of a whole catalog: what the APK ships and what the server's index lists."""
    connection = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True)
    try:
        version, identity = schema_of(connection)
    finally:
        connection.close()
    return {
        "bundleSchema": BUNDLE_SCHEMA,
        "schemaVersion": version,
        "identityHash": identity,
        "canyons": {str(b.canyon_id): b.hash for b in canyon_bundles(db_path)},
        "shared": {table: shared_table(db_path, table)[0] for table in SHARED_TABLES},
    }


def gzip_bytes(data: bytes) -> bytes:
    return gzip.compress(data, compresslevel=9, mtime=0)


def main() -> None:
    parser = argparse.ArgumentParser(description="Write the per-canyon hashes of a catalog.db (asset of the APK).")
    parser.add_argument("--catalog-db", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    hashes = catalog_hashes(args.catalog_db)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(hashes, separators=(",", ":"), sort_keys=True), encoding="utf-8")
    print(f"{len(hashes['canyons'])} canyon hashes written to {args.output}")


if __name__ == "__main__":
    main()
