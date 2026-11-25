"""Nightly catalog job.

The site's base takes no new canyon since 2019 and few sheets change (122 in four months in 2026),
so the job re-reads one slice of the sheets each night (every sheet once per DC_ROTATION_DAYS),
with the scraper of the app repository, then rebuilds catalog.db with the same generator as the APK
build. It publishes only a catalog whose content changed and that passes every check; otherwise the
previous one stays published and the report says why.
"""
from __future__ import annotations

import contextlib
import fcntl
import gzip
import hashlib
import importlib.util
import json
import os
import shutil
import sqlite3
import subprocess
import sys
import tempfile
from collections.abc import Callable, Iterable
from dataclasses import dataclass
from datetime import date, datetime, timezone
from pathlib import Path
from types import ModuleType
from typing import Any
from urllib.error import HTTPError

from .. import manifest
from ..config import Settings
from ..publish import atomic_write_bytes, prune_files, publication_lock, publish_file
from ..tracks.canyons import build_reference_from_catalog, write_reference

SCRAPER = Path("scripts/app/scrape_descente_canyon_samples.py")
GENERATOR = Path("scripts/app/generate_prepackaged_room_db.py")
SCHEMAS = Path("core/data/schemas/fr.descentecanyon.app.data.local.catalog.CatalogDatabase")
ROOM_IMPORT = Path("offline-data/full/room-import")
REPO_CANYONS = Path("offline-data/full/canyons")
CHAINS = Path("data-overlay/canyon-chains.json")
RECOVERED_POINTS = Path("data-overlay/recovered-geo-points.json")

MAX_FORMAT_FAILURE_SHARE = 0.05
MAX_NETWORK_FAILURE_SHARE = 0.30
MIN_KEPT_SHARE = {"canyons": 0.98, "geo_points": 0.97, "regulation_texts": 0.95}
IGNORED_TABLES = {"room_master_table", "sqlite_sequence", "android_metadata", "sqlite_stat1"}


class JobAborted(RuntimeError):
    """The job stopped without publishing (the reason is a short code for the report)."""

    def __init__(self, reason: str, detail: str) -> None:
        super().__init__(f"{reason}: {detail}")
        self.reason = reason
        self.detail = detail


@dataclass(frozen=True)
class ScrapeResult:
    scraped: list[int]
    changed: list[int]
    format_failures: list[int]
    network_failures: list[tuple[int, str]]


def load_scraper(repo: Path, user_agent: str, delay_s: float) -> ModuleType:
    spec = importlib.util.spec_from_file_location("dc_site_scraper", repo / SCRAPER)
    if spec is None or spec.loader is None:
        raise JobAborted("REPO_INCOMPLETE", f"{SCRAPER} not found")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    module.USER_AGENT = user_agent
    module.REQUEST_DELAY_SECONDS = delay_s
    return module


def rotation_slice(ids: Iterable[int], today: date, period_days: int) -> list[int]:
    ordered = sorted(set(ids))
    return ordered[today.toordinal() % period_days :: period_days]


def _scraped_at(document: dict[str, Any]) -> str:
    return str(document.get("scrapedAt") or "")


def strip_volatile(document: dict[str, Any]) -> dict[str, Any]:
    return {key: value for key, value in document.items() if key != "scrapedAt"}


def _read_json(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def current_canyon_files(repo: Path, scraped_dir: Path) -> dict[int, Path]:
    """For every canyon, the newest of the repository file and the file scraped by the server."""
    chosen: dict[int, Path] = {}
    for path in (repo / REPO_CANYONS).glob("*.json"):
        if path.stem.isdigit():
            chosen[int(path.stem)] = path
    if scraped_dir.exists():
        for path in scraped_dir.glob("*.json"):
            if not path.stem.isdigit():
                continue
            canyon_id = int(path.stem)
            existing = chosen.get(canyon_id)
            if existing is None or _scraped_at(_read_json(path)) >= _scraped_at(_read_json(existing)):
                chosen[canyon_id] = path
    return chosen


def scrape_slice(scraper: ModuleType, ids: list[int], files: dict[int, Path], scraped_dir: Path) -> ScrapeResult:
    scraped: list[int] = []
    changed: list[int] = []
    format_failures: list[int] = []
    network_failures: list[tuple[int, str]] = []
    scraped_dir.mkdir(parents=True, exist_ok=True)
    for canyon_id in ids:
        scraper.FETCH_CACHE.clear()  # bounds memory; the regulation cache is shared on purpose
        try:
            document = scraper.scrape_canyon(canyon_id)
        except HTTPError as exc:
            network_failures.append((canyon_id, f"HTTP {exc.code}"))
            continue
        except Exception as exc:  # noqa: BLE001 - any failure leaves the previous copy in place
            network_failures.append((canyon_id, type(exc).__name__))
            continue
        name = (((document.get("canyon") or {}).get("identity") or {}).get("nom") or "").strip()
        if not name:
            # A page without a canyon name means the layout changed: never keep it.
            format_failures.append(canyon_id)
            continue
        previous = files.get(canyon_id)
        if previous is None or strip_volatile(_read_json(previous)) != strip_volatile(document):
            changed.append(canyon_id)
        target = scraped_dir / f"{canyon_id}.json"
        atomic_write_bytes(target, json.dumps(document, ensure_ascii=False, indent=2).encode("utf-8"))
        files[canyon_id] = target
        scraped.append(canyon_id)
    return ScrapeResult(scraped, changed, format_failures, network_failures)


def check_scrape(result: ScrapeResult, requested: int) -> None:
    if requested == 0:
        return
    if len(result.format_failures) > max(3, MAX_FORMAT_FAILURE_SHARE * requested):
        raise JobAborted("SITE_FORMAT_CHANGED", f"{len(result.format_failures)} pages without a canyon name")
    if len(result.network_failures) > MAX_NETWORK_FAILURE_SHARE * requested:
        raise JobAborted("SITE_UNREACHABLE", f"{len(result.network_failures)}/{requested} canyons not read")


def _iso_to_ms(value: str) -> int | None:
    try:
        return int(datetime.fromisoformat(value.replace("Z", "+00:00")).timestamp() * 1000)
    except ValueError:
        return None


def build_room_import(scraper: ModuleType, repo: Path, files: dict[int, Path], work: Path) -> Path:
    """Room-import tables from the chosen canyon files; the other tables (watersheds, members...)
    come from the repository as they are."""
    full = work / "full"
    canyon_dir = full / "canyons"
    canyon_dir.mkdir(parents=True)
    for canyon_id, path in files.items():
        os.symlink(path.resolve(), canyon_dir / f"{canyon_id}.json")
    shutil.copytree(repo / ROOM_IMPORT, full / "room-import")
    canyons = scraper.load_canyons_from_output(full)
    records = scraper.build_room_import_records(canyons)
    # The build stamps every row with "now": keep the real reading time of each sheet instead, so
    # the app refreshes a sheet from the site only when the server copy itself is old.
    read_at = {item["canyon"]["id"]: _iso_to_ms(_scraped_at(item)) for item in canyons}
    for row in records["tables"]["canyons"]:
        row["lastUpdated"] = read_at.get(row["id"]) or row["lastUpdated"]
    scraper.write_room_import_dataset(full, records)
    return full / "room-import"


def latest_schema(repo: Path) -> tuple[Path, dict[str, Any]]:
    candidates = [p for p in (repo / SCHEMAS).glob("*.json") if p.stem.isdigit()]
    if not candidates:
        raise JobAborted("REPO_INCOMPLETE", f"no Room schema in {SCHEMAS}")
    path = max(candidates, key=lambda p: int(p.stem))
    return path, _read_json(path)


def generate_catalog(repo: Path, room_import: Path, schema_path: Path, output: Path) -> None:
    command = [
        sys.executable,
        "-I",
        str(repo / GENERATOR),
        "--room-import-dir",
        str(room_import),
        "--schema",
        str(schema_path),
        "--output",
        str(output),
        "--canyon-chains",
        str(repo / CHAINS),
        "--recovered-geo-points",
        str(repo / RECOVERED_POINTS),
    ]
    completed = subprocess.run(command, capture_output=True, text=True, timeout=900, check=False)
    if completed.returncode != 0:
        tail = (completed.stderr or completed.stdout)[-1500:]
        raise JobAborted("GENERATOR_FAILED", tail)


def catalog_stats(db_path: Path, schema: dict[str, Any]) -> dict[str, Any]:
    """Checks that Room will open the file as this schema, and counts its rows."""
    connection = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True)
    try:
        version = connection.execute("PRAGMA user_version").fetchone()[0]
        expected_version = schema["database"]["version"]
        if version != expected_version:
            raise JobAborted("CATALOG_INVALID", f"user_version {version}, expected {expected_version}")
        row = connection.execute("SELECT identity_hash FROM room_master_table WHERE id = 42").fetchone()
        expected_hash = schema["database"]["identityHash"]
        if row is None or row[0] != expected_hash:
            raise JobAborted("CATALOG_INVALID", "Room identity hash differs from the schema")
        integrity = connection.execute("PRAGMA integrity_check").fetchone()[0]
        if integrity != "ok":
            raise JobAborted("CATALOG_INVALID", f"integrity_check: {integrity}")
        tables = [r[0] for r in connection.execute("SELECT name FROM sqlite_master WHERE type = 'table'")]
        counts = {
            table: connection.execute(f'SELECT COUNT(*) FROM "{table}"').fetchone()[0]
            for table in tables
            if table not in IGNORED_TABLES
        }
        blank = connection.execute("SELECT COUNT(*) FROM canyons WHERE TRIM(COALESCE(nom, '')) = ''").fetchone()[0]
        if blank:
            raise JobAborted("CATALOG_INVALID", f"{blank} canyons without a name")
    finally:
        connection.close()
    return {"schemaVersion": expected_version, "identityHash": expected_hash, "counts": counts}


def check_against_previous(stats: dict[str, Any], previous: dict[str, Any] | None) -> None:
    if not previous:
        return
    for table, share in MIN_KEPT_SHARE.items():
        before = previous.get("counts", {}).get(table, 0)
        after = stats["counts"].get(table, 0)
        if before and after < share * before:
            raise JobAborted("CATALOG_SHRANK", f"{table}: {after} rows, previously {before}")


def _blob_text(value: object) -> str:
    if isinstance(value, (bytes, bytearray, memoryview)):
        return bytes(value).hex()
    raise TypeError(f"unexpected value {type(value).__name__}")


def content_fingerprints(db_path: Path) -> tuple[str, dict[int, str]]:
    """Hash of the whole content without the reading times, and one hash per canyon row."""
    connection = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True)
    try:
        digest = hashlib.sha256()
        per_canyon: dict[int, str] = {}
        tables = sorted(r[0] for r in connection.execute("SELECT name FROM sqlite_master WHERE type = 'table'"))
        for table in tables:
            if table in IGNORED_TABLES:
                continue
            columns = [r[1] for r in connection.execute(f'PRAGMA table_info("{table}")')]
            if table == "canyons":
                columns = [c for c in columns if c != "lastUpdated"]
            selected = ", ".join(f'"{c}"' for c in columns)
            digest.update(f"\x00{table}\x00".encode())
            for row in connection.execute(f'SELECT {selected} FROM "{table}" ORDER BY {selected}'):
                encoded = json.dumps(row, ensure_ascii=False, separators=(",", ":"), default=_blob_text).encode("utf-8")
                digest.update(encoded)
                if table == "canyons":
                    per_canyon[row[columns.index("id")]] = hashlib.sha256(encoded).hexdigest()[:16]
    finally:
        connection.close()
    return digest.hexdigest(), per_canyon


def _gzip(source: Path, target: Path) -> None:
    with source.open("rb") as raw, target.open("wb") as out:
        with gzip.GzipFile(fileobj=out, mode="wb", compresslevel=9, mtime=0) as packed:
            shutil.copyfileobj(raw, packed, 1 << 20)


@contextlib.contextmanager
def _job_lock(path: Path):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a+") as handle:
        try:
            fcntl.flock(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as exc:
            raise JobAborted("ALREADY_RUNNING", "another catalog job holds the lock") from exc
        try:
            yield
        finally:
            fcntl.flock(handle.fileno(), fcntl.LOCK_UN)


def _state_path(settings: Settings) -> Path:
    return settings.data_dir / "catalog" / "state.json"


def run(
    settings: Settings,
    *,
    ids: list[int] | None = None,
    limit: int | None = None,
    today: date | None = None,
    publish: bool = True,
    scraper_loader: Callable[[Path, str, float], ModuleType] = load_scraper,
) -> dict[str, Any]:
    """Runs the job and returns its report (also written to the reports folder)."""
    started = datetime.now(timezone.utc)
    report: dict[str, Any] = {"job": "catalog", "startedAt": started.isoformat(), "published": False}
    try:
        with _job_lock(settings.data_dir / "catalog.lock"):
            report.update(_run_locked(settings, ids=ids, limit=limit, today=today or started.date(), publish=publish, scraper_loader=scraper_loader))
    except JobAborted as exc:
        report.update({"aborted": exc.reason, "detail": exc.detail})
    report["finishedAt"] = datetime.now(timezone.utc).isoformat()
    settings.reports_dir.mkdir(parents=True, exist_ok=True)
    report_path = settings.reports_dir / f"catalog-{started.strftime('%Y%m%dT%H%M%SZ')}.json"
    atomic_write_bytes(report_path, json.dumps(report, ensure_ascii=False, indent=2).encode("utf-8"))
    return report


def _run_locked(
    settings: Settings,
    *,
    ids: list[int] | None,
    limit: int | None,
    today: date,
    publish: bool,
    scraper_loader: Callable[[Path, str, float], ModuleType],
) -> dict[str, Any]:
    repo = settings.repo_dir
    for required in (SCRAPER, GENERATOR, ROOM_IMPORT, CHAINS, RECOVERED_POINTS):
        if not (repo / required).exists():
            raise JobAborted("REPO_INCOMPLETE", f"{required} is missing in {repo}")
    scraper = scraper_loader(repo, settings.user_agent, settings.request_delay_s)
    files = current_canyon_files(repo, settings.scraped_canyons_dir)
    if not files:
        raise JobAborted("REPO_INCOMPLETE", f"no canyon file in {REPO_CANYONS}")

    selection = ids if ids is not None else rotation_slice(files.keys(), today, settings.rotation_days)
    if limit is not None:
        selection = selection[:limit]
    result = scrape_slice(scraper, selection, files, settings.scraped_canyons_dir)
    check_scrape(result, len(selection))
    report: dict[str, Any] = {
        "requested": len(selection),
        "scraped": len(result.scraped),
        "changedCanyons": result.changed,
        "formatFailures": result.format_failures,
        "networkFailures": [{"id": i, "error": e} for i, e in result.network_failures],
    }

    schema_path, schema = latest_schema(repo)
    state_path = _state_path(settings)
    state = _read_json(state_path) if state_path.exists() else {}
    with tempfile.TemporaryDirectory(prefix="dc-catalog-", dir=settings.data_dir) as tmp:
        work = Path(tmp)
        room_import = build_room_import(scraper, repo, files, work)
        db_path = work / "catalog.db"
        generate_catalog(repo, room_import, schema_path, db_path)
        stats = catalog_stats(db_path, schema)
        same_schema = state.get("stats", {}).get("identityHash") == stats["identityHash"]
        check_against_previous(stats, state.get("stats") if same_schema else None)
        fingerprint, per_canyon = content_fingerprints(db_path)
        canyon_hashes = {str(k): v for k, v in per_canyon.items()}
        previous_rows: dict[str, str] = state.get("canyonHashes", {})
        if previous_rows:
            touched = {k for k, v in canyon_hashes.items() if previous_rows.get(k) != v} | (previous_rows.keys() - canyon_hashes.keys())
            report["catalogChanges"] = sorted(int(k) for k in touched)
        report["counts"] = stats["counts"]
        if same_schema and state.get("fingerprint") == fingerprint:
            report["unchanged"] = True
            return report
        if not publish:
            report["dryRun"] = True
            return report

        packed = work / "catalog.db.gz"
        _gzip(db_path, packed)
        data_version = manifest.utc_now_iso()
        with publication_lock(settings.lock_path):
            published = publish_file(settings.v1_dir, packed, ".db.gz")
            manifest_state = manifest.load_state(settings)
            manifest_state["catalogs"] = [
                {
                    "schemaVersion": stats["schemaVersion"],
                    "identityHash": stats["identityHash"],
                    "dataVersion": data_version,
                    "path": published["path"],
                    "sha256": published["sha256"],
                    "size": published["size"],
                    "uncompressedSize": db_path.stat().st_size,
                    "canyonCount": stats["counts"].get("canyons", 0),
                }
            ]
            manifest.publish(settings, manifest_state)
            prune_files(settings.v1_dir, manifest.referenced_files(manifest_state))
        write_reference(settings.canyon_reference_path, build_reference_from_catalog(db_path))
        new_state = {
            "fingerprint": fingerprint,
            "stats": stats,
            "dataVersion": data_version,
            "file": published,
            "canyonHashes": canyon_hashes,
        }
        atomic_write_bytes(state_path, json.dumps(new_state, ensure_ascii=False).encode("utf-8"))
        report.update({"published": True, "dataVersion": data_version, "file": published})
        return report
