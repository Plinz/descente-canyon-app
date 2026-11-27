"""Debit estimates job: every canyon, four times a day, published as one signed file.

Each run:
1. reads the site's "derniers débits" grid (one page) and the debit pages of the canyons with a new
   report, plus a slice of the pages never read (the reports since the lookups were built);
2. refreshes the forecasts (and the recent archive) of the grid cells already usable;
3. reads the rain gauges near the canyons (Météo-France daily and hourly files, MeteoSwiss), once a
   day for all of them;
4. estimates J0, J+1 and J+2 of every canyon whose window is complete, with the app's model and
   feature path, and keeps the first estimate of each day to check it against the reports that
   come in (live check, published with the estimates);
5. publishes the estimates (gzipped JSON, referenced by the signed manifest);
6. then locates the new canyons and fills the windows of the new cells within the Open-Meteo budget
   (which can take an hour or two the first days), and estimates and publishes again when cells
   became usable.

The app shows these estimates first (lists, map, canyon page) and computes on the phone only what the
file lacks: a canyon not estimated yet, or with a report newer than the estimate.
"""
from __future__ import annotations

import contextlib
import fcntl
import gzip
import json
import math
import sqlite3
import sys
import tempfile
import time
import urllib.error
import urllib.request
from collections.abc import Callable
from dataclasses import dataclass, field
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
from typing import Any
from zoneinfo import ZoneInfo

from .. import manifest
from ..canyons.content import FIRST_APP_CANYON_ID
from ..config import Settings
from ..publish import atomic_write_bytes, prune_files, publication_lock, publish_file
from . import gauges as G
from . import reports as RP
from . import weather as W
from .model import DEBIT_MODEL_DIR, CanyonInputs, ModelRunner, weather_target

SCHEMA = 1
PIPELINE_DIR = Path("scripts/debits")
LIVE_CHECK_DAYS = 90
ESTIMATE_BATCH = 256


class JobAborted(RuntimeError):
    def __init__(self, reason: str, detail: str) -> None:
        super().__init__(f"{reason}: {detail}")
        self.reason = reason
        self.detail = detail


@dataclass
class Sources:
    """Network access of the job (replaced by fakes in the tests)."""

    fetch_json: W.FetchJson
    open_url: Callable[[str], Any]
    fetch_text: Callable[[str], str]
    clock: Callable[[], float] = time.time
    sleep: Callable[[float], None] = time.sleep


def default_sources(settings: Settings) -> Sources:
    def fetch_json(url: str, params: dict) -> dict:
        return W.http_fetch_json(url, params, settings.user_agent)

    def open_url(url: str):
        return G.http_stream(url, settings.user_agent)

    def fetch_text(url: str) -> str:
        request = urllib.request.Request(url, headers={"User-Agent": settings.user_agent})
        with urllib.request.urlopen(request, timeout=60) as response:
            return response.read().decode("utf-8", errors="replace")

    return Sources(fetch_json, open_url, fetch_text)


@dataclass
class CatalogCanyon:
    canyon_id: int
    canyon: dict[str, Any]
    target: tuple[float, float, str] | None


def load_catalog(path: Path) -> dict[int, CatalogCanyon]:
    """The canyons of the last catalog the server built (data/catalog/current.db)."""
    if not path.exists():
        raise JobAborted("NO_CATALOG", f"{path} is missing: run the catalog job first")
    connection = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
    try:
        watersheds = {
            row[0]: (row[1], row[2], row[3], row[4])
            for row in connection.execute(
                "SELECT canyonId, bboxMinLongitude, bboxMinLatitude, bboxMaxLongitude, bboxMaxLatitude FROM watersheds "
                "WHERE bboxMinLongitude IS NOT NULL AND bboxMaxLatitude IS NOT NULL"
            )
        }
        points: dict[int, list[tuple[str, float, float]]] = {}
        for canyon_id, kind, latitude, longitude in connection.execute("SELECT canyonId, type, latitude, longitude FROM geo_points"):
            if latitude is not None and longitude is not None:
                points.setdefault(canyon_id, []).append((kind, latitude, longitude))
        canyons = {}
        for canyon_id, region, massif, altitude, denivele, longueur, cascade in connection.execute(
            # The canyons added in the app have no reports (the site does not know them): no estimate yet.
            "SELECT id, region, massif, altitudeDepart, denivele, longueur, cascadeMax FROM canyons WHERE id < ?",
            (FIRST_APP_CANYON_ID,),
        ):
            canyons[canyon_id] = CatalogCanyon(
                canyon_id,
                {"region": region, "massif": massif, "altitudeDepart": altitude, "denivele": denivele, "longueur": longueur, "cascadeMax": cascade},
                weather_target(canyon_id, watersheds.get(canyon_id), points.get(canyon_id, [])),
            )
        return canyons
    finally:
        connection.close()


class LiveCheck:
    """The first estimate made for each canyon, day and horizon, scored against the reports."""

    def __init__(self, path: Path) -> None:
        self.connection = sqlite3.connect(path, isolation_level=None)
        self.connection.execute(
            "CREATE TABLE IF NOT EXISTS estimate (canyon_id INTEGER NOT NULL, day INTEGER NOT NULL, horizon INTEGER NOT NULL, "
            "rank INTEGER NOT NULL, level TEXT NOT NULL, made_at REAL NOT NULL, PRIMARY KEY (canyon_id, day, horizon))"
        )

    def close(self) -> None:
        self.connection.close()

    def record(self, canyon_id: int, estimates, made_at: float) -> None:
        self.connection.executemany(
            "INSERT OR IGNORE INTO estimate VALUES (?, ?, ?, ?, ?, ?)",
            [(canyon_id, W.epoch_day(e.date), e.horizon, e.rank, e.level, made_at) for e in estimates],
        )

    def score(self, reports_of: Callable[[int], list[tuple[int, int]]], today: date) -> dict[str, Any]:
        first = W.epoch_day(today) - LIVE_CHECK_DAYS
        self.connection.execute("DELETE FROM estimate WHERE day < ?", (first - 30,))
        by_canyon: dict[int, list[tuple[int, int, int, str]]] = {}
        for canyon_id, day, horizon, rank, level in self.connection.execute(
            "SELECT canyon_id, day, horizon, rank, level FROM estimate WHERE day BETWEEN ? AND ?", (first, W.epoch_day(today))
        ):
            by_canyon.setdefault(canyon_id, []).append((day, horizon, rank, level))
        totals = {h: dict.fromkeys(("reports", "exact", "withinOne", "highReports", "highCaught", "highShown", "highShownRight"), 0) for h in (0, 1, 2)}
        for canyon_id, rows in by_canyon.items():
            reported: dict[int, int] = {}
            for day, rank in reports_of(canyon_id):
                reported[day] = min(rank, reported.get(day, rank))
            for day, horizon, rank, level in rows:
                if day not in reported:
                    continue
                truth = reported[day]
                stats = totals[horizon]
                stats["reports"] += 1
                stats["exact"] += int(rank == truth)
                stats["withinOne"] += int(abs(rank - truth) <= 1)
                stats["highReports"] += int(truth >= 3)
                stats["highCaught"] += int(truth >= 3 and level == "HIGH")
                stats["highShown"] += int(level == "HIGH")
                stats["highShownRight"] += int(level == "HIGH" and truth >= 3)
        return {"days": LIVE_CHECK_DAYS, "since": W.from_epoch_day(first).isoformat(), "byHorizon": [{"horizon": h, **totals[h]} for h in (0, 1, 2)]}


@contextlib.contextmanager
def _job_lock(path: Path):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a+") as handle:
        try:
            fcntl.flock(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as exc:
            raise JobAborted("ALREADY_RUNNING", "another predictions job holds the lock") from exc
        try:
            yield
        finally:
            fcntl.flock(handle.fileno(), fcntl.LOCK_UN)


def run(settings: Settings, *, publish: bool = True, ids: list[int] | None = None, sources: Sources | None = None) -> dict[str, Any]:
    sources = sources or default_sources(settings)
    started = datetime.fromtimestamp(sources.clock(), timezone.utc)
    report: dict[str, Any] = {"job": "predictions", "startedAt": started.isoformat(), "published": False}
    try:
        with _job_lock(settings.data_dir / "predictions.lock"):
            report.update(_run_locked(settings, publish=publish, ids=ids, sources=sources))
    except JobAborted as exc:
        report.update({"aborted": exc.reason, "detail": exc.detail})
    report["finishedAt"] = datetime.fromtimestamp(sources.clock(), timezone.utc).isoformat()
    settings.reports_dir.mkdir(parents=True, exist_ok=True)
    path = settings.reports_dir / f"predictions-{started.strftime('%Y%m%dT%H%M%SZ')}.json"
    atomic_write_bytes(path, json.dumps(report, ensure_ascii=False, indent=2).encode("utf-8"))
    return report


@dataclass
class _Context:
    settings: Settings
    sources: Sources
    runner: ModelRunner
    catalog: dict[int, CatalogCanyon]
    store: W.WeatherStore
    meteo: W.OpenMeteo
    reports: RP.ReportStore
    report: dict[str, Any] = field(default_factory=dict)


def _run_locked(settings: Settings, *, publish: bool, ids: list[int] | None, sources: Sources) -> dict[str, Any]:
    repo = settings.repo_dir
    for required in (DEBIT_MODEL_DIR / "runtime_reference.py", PIPELINE_DIR / "pipeline_lib.py", Path("modele_statistique/thresholds.json")):
        if not (repo / required).exists():
            raise JobAborted("REPO_INCOMPLETE", f"{required} is missing in {repo}")
    catalog = load_catalog(settings.data_dir / "catalog" / "current.db")
    if ids is not None:
        wanted = set(ids)
        catalog = {i: c for i, c in catalog.items() if i in wanted}
    runner = ModelRunner(repo)
    work_dir = settings.data_dir / "predictions"
    store = W.WeatherStore(work_dir / "weather.sqlite3")
    meteo = W.OpenMeteo(
        store, sources.fetch_json, settings.openmeteo_daily_calls, settings.openmeteo_seconds_per_call,
        settings.openmeteo_api_key, clock=sources.clock, sleep=sources.sleep,
    )
    context = _Context(settings, sources, runner, catalog, store, meteo, RP.ReportStore(settings.data_dir))
    live = LiveCheck(work_dir / "live-check.sqlite3")
    try:
        now = sources.clock()
        today_utc = datetime.fromtimestamp(now, timezone.utc).date()
        report = context.report
        report["modelVersion"] = runner.version
        _read_reports(context, today_utc)
        weather = _WeatherUpdate(context, today_utc)
        weather.refresh()
        # The estimates of the canyons already usable go out first: filling the new cells can take
        # an hour or two of the Open-Meteo budget, and the morning estimates must not wait for it.
        entries = _estimate_and_publish(context, live, today_utc, publish)
        if weather.fill():
            entries = _estimate_and_publish(context, live, today_utc, publish)
        weather.write_report()
        report["estimated"] = len(entries)
        store.prune(today_utc)
        if not entries:
            report["unpublished"] = "NO_ESTIMATE"
        elif not publish:
            report["dryRun"] = True
        return report
    finally:
        live.close()
        store.close()


# 1. Reports


def _pipeline_modules(repo: Path):
    for directory in (repo / PIPELINE_DIR, repo / DEBIT_MODEL_DIR):
        if str(directory) not in sys.path:
            sys.path.insert(0, str(directory))
    import features
    import label_quality
    import pipeline_lib

    return pipeline_lib, label_quality, features


def _read_reports(context: _Context, today_utc: date) -> None:
    report, store, sources = context.report, context.reports, context.sources
    previous = set(store.latest_grid())
    try:
        cells = RP.parse_grid(sources.fetch_text(RP.GRID_URL))
    except (OSError, urllib.error.URLError, ValueError) as error:
        cells = []
        report["gridError"] = repr(error)[:300]
    if cells:
        store.save_grid(cells, today_utc)
    report["gridCells"] = len(cells)
    fresh = sorted({canyon_id for canyon_id, day, rank in cells if (canyon_id, day, rank) not in previous and canyon_id in context.catalog})
    never_read = [
        canyon_id for canyon_id in _shipped_order(context) if store.page(canyon_id) is None and canyon_id not in fresh
    ]
    to_read = (fresh + never_read)[: context.settings.debit_pages_per_run]
    modules = _pipeline_modules(context.settings.repo_dir)
    read, failures = 0, []
    for index, canyon_id in enumerate(to_read):
        if index:
            sources.sleep(context.settings.request_delay_s)
        try:
            html = sources.fetch_text(RP.DEBIT_PAGE_URL.format(id=canyon_id))
            read_at = datetime.fromtimestamp(sources.clock(), timezone.utc).isoformat()
            store.save_page(canyon_id, RP.parse_debit_page(*modules, canyon_id, html), read_at)
            read += 1
        except Exception as error:  # noqa: BLE001 - one page never stops the run
            failures.append({"id": canyon_id, "error": repr(error)[:200]})
    report["debitPages"] = {"newReports": len(fresh), "read": read, "failures": failures[:20], "neverRead": max(len(never_read) - max(len(to_read) - len(fresh), 0), 0)}


def _shipped_order(context: _Context) -> list[int]:
    """Canyons with reports in the lookups, the most recent first (the pages worth reading first)."""
    last: dict[int, int] = {}
    for canyon_id, entry in context.runner.lookups["canyons"].items():
        days = [int(round(v)) // 10 for k, v in entry.items() if k.startswith("h") and k[1:].isdigit()]
        if days and int(canyon_id) in context.catalog:
            last[int(canyon_id)] = max(days)
    return sorted(last, key=lambda c: (-last[c], c))


# 2. Weather


def _priority(context: _Context, today_utc: date) -> list[int]:
    """Canyons by interest: recent reports on the grid first, then recent reports in the lookups."""
    recent = W.epoch_day(today_utc) - RP.RECENT_DAYS
    activity: dict[int, int] = {}
    for canyon_id, days in context.reports.grid_cells().items():
        activity[canyon_id] = sum(1 for day in days if day >= recent)
    shipped = {canyon_id: index for index, canyon_id in enumerate(_shipped_order(context))}
    return sorted(context.catalog, key=lambda c: (-activity.get(c, 0), shipped.get(c, len(shipped)), c))


class _WeatherUpdate:
    """The weather of the cells: [refresh] keeps the usable cells current (their forecasts first),
    [fill] then adds the new canyons and cells within what the later runs of the day keep."""

    def __init__(self, context: _Context, today_utc: date) -> None:
        self.context = context
        self.today_utc = today_utc
        self.stats: dict[str, Any] = {"locate": 0, "forecast": 0, "archiveRefresh": 0, "archiveFill": 0, "mismatches": [], "errors": []}
        self.order: list[int] = []
        self.to_locate: list[int] = []
        self.complete: list[str] = []

    def _attempt(self, step: str, action: Callable[[], None]) -> bool:
        """Runs one call; False when the budget of the day is spent (the caller stops)."""
        try:
            action()
            self.stats[step] += 1
        except W.BudgetExhausted:
            return False
        except W.CellMismatch as error:
            self.stats["mismatches"].append(str(error)[:200])
        except Exception as error:  # noqa: BLE001 - a cell never stops the run
            self.stats["errors"].append(f"{step}: {error!r}"[:200])
        return True

    def _save(self, cell: str, source: str, point: W.Located, fetch: Callable[[], dict]) -> None:
        self.context.store.save_daily(cell, source, fetch(), point.elevation, self.context.sources.clock())

    def refresh(self) -> None:
        context, store, meteo = self.context, self.context.store, self.context.meteo
        now = context.sources.clock()
        self.order = _priority(context, self.today_utc)
        for canyon_id in self.order:
            target = context.catalog[canyon_id].target
            if target is not None and store.set_target(canyon_id, target[0], target[1]):
                self.to_locate.append(canyon_id)
        store.forget_canyons({c for c in context.catalog if context.catalog[c].target is not None})
        store.drop_unused_cells()
        max_age = context.settings.forecast_max_age_h * 3600
        self.complete = [cell for cell in _cells_in_order(store, self.order) if store.archive_complete(cell, self.today_utc)]
        # Forecasts of the cells already usable first: they keep every published estimate fresh.
        for cell in self.complete:
            age = store.forecast_age(cell, now)
            if age is None or age >= max_age:
                point = store.representative(cell)
                if point and not self._attempt("forecast", lambda c=cell, p=point: self._save(c, "F", p, lambda: meteo.forecast(p))):
                    return
        for cell in self.complete:
            span = store.archive_range(cell, self.today_utc, now)
            point = store.representative(cell)
            if span and point and not self._attempt(
                "archiveRefresh", lambda c=cell, p=point, s=span: self._save(c, "A", p, lambda: meteo.archive(p, *s))
            ):
                return

    def fill(self) -> int:
        """Locates the new canyons and fills the cells not usable yet; returns how many became usable."""
        context, store, meteo = self.context, self.context.store, self.context.meteo
        now = context.sources.clock()
        reserve = context.settings.openmeteo_reserve
        max_age = context.settings.forecast_max_age_h * 3600
        for canyon_id in self.to_locate:
            located = store.located(canyon_id)
            if not self._attempt(
                "locate", lambda c=located: store.save_location(c.canyon_id, meteo.locate(c.latitude, c.longitude, reserve), context.sources.clock())
            ):
                break
        completed = 0
        for cell in [c for c in _cells_in_order(store, self.order) if c not in self.complete]:
            point = store.representative(cell)
            if point is None:
                continue
            span = store.archive_range(cell, self.today_utc, now)
            if span and not self._attempt(
                "archiveFill", lambda c=cell, p=point, s=span: self._save(c, "A", p, lambda: meteo.archive(p, *s, reserve))
            ):
                break
            if not store.archive_complete(cell, self.today_utc):
                continue
            completed += 1
            age = store.forecast_age(cell, now)
            if (age is None or age >= max_age) and not self._attempt(
                "forecast", lambda c=cell, p=point: self._save(c, "F", p, lambda: meteo.forecast(p, reserve))
            ):
                break
        return completed

    def write_report(self) -> None:
        store, catalog = self.context.store, self.context.catalog
        cells = store.cells()
        self.context.report["weather"] = {
            **self.stats,
            "mismatches": self.stats["mismatches"][:20],
            "errors": self.stats["errors"][:20],
            "calls": round(self.context.meteo.calls, 1),
            "callsToday": round(store.calls_today(self.today_utc), 1),
            "cells": len(cells),
            "cellsComplete": sum(1 for cell in cells if store.archive_complete(cell, self.today_utc)),
            "canyonsLocated": sum(1 for c in catalog if (located := store.located(c)) is not None and located.cell),
        }


def _cells_in_order(store: W.WeatherStore, order: list[int]) -> list[str]:
    cells: list[str] = []
    seen: set[str] = set()
    for canyon_id in order:
        located = store.located(canyon_id)
        if located and located.cell and located.cell not in seen:
            seen.add(located.cell)
            cells.append(located.cell)
    return cells


# 3-4. Gauges and estimates


def _estimate_and_publish(context: _Context, live: LiveCheck, today_utc: date, publish: bool) -> dict[str, Any]:
    """Estimates every usable canyon and publishes the file (dated by the estimate time: its J0 is
    the day of that time in each canyon's zone). Returns the entries."""
    now = context.sources.clock()
    entries = _estimate(context, live, today_utc, now)
    live_check = live.score(lambda canyon_id: context.reports.history(canyon_id, []), today_utc)
    context.report["liveCheck"] = live_check
    if entries and publish:
        _publish(context.settings, context.runner, entries, live_check, now, context.report)
    return entries


def _estimate(context: _Context, live: LiveCheck, today_utc: date, now: float) -> dict[str, Any]:
    runner, store, report = context.runner, context.store, context.report
    ready: list[tuple[int, W.Located]] = []
    for canyon_id in sorted(context.catalog):
        located = store.located(canyon_id)
        if located and located.cell and located.elevation is not None and store.archive_complete(located.cell, today_utc):
            ready.append((canyon_id, located))
    stations = {canyon_id: runner.rain_stations(canyon_id) for canyon_id, _ in ready}
    codes = sorted({code for values in stations.values() for code, _ in values})
    tables = G.GaugeTables(context.settings.data_dir / "predictions" / "gauges", context.sources.open_url, clock=context.sources.clock)
    try:
        daily_rain, automatic = tables.rain_for(codes, today_utc)
    except Exception as error:  # noqa: BLE001 - the gauges are an input among others
        daily_rain, automatic = {}, {}
        report["gaugeError"] = repr(error)[:300]
    report["gauges"] = {
        "stations": len(codes), "withDailyRain": len(daily_rain), "automatic": len(automatic),
        "downloads": tables.downloads, "errors": tables.errors[:20],
    }
    grid = RP.recent_cells(context.reports.latest_grid(), today_utc + timedelta(days=1))
    entries: dict[str, Any] = {}
    failures = 0
    for start in range(0, len(ready), ESTIMATE_BATCH):
        batch = []
        for canyon_id, located in ready[start:start + ESTIMATE_BATCH]:
            try:
                batch.append(_inputs(context, canyon_id, located, now, today_utc, grid, stations[canyon_id], daily_rain, automatic))
            except Exception:  # noqa: BLE001 - one canyon never stops the run
                failures += 1
        try:
            results = runner.estimate_many([(inputs, today) for inputs, today, _ in batch])
        except Exception:  # noqa: BLE001
            results = []
            for inputs, today, _ in batch:
                try:
                    results.append(runner.estimate(inputs, today))
                except Exception:  # noqa: BLE001
                    failures += 1
                    results.append(([], None))
        for (inputs, today, located), (estimates, basis) in zip(batch, results):
            if estimates:
                live.record(inputs.canyon_id, estimates, now)
                entries[str(inputs.canyon_id)] = _entry(context, inputs, today, located, estimates, basis, now)
    report["estimateFailures"] = failures
    report["ready"] = len(ready)
    return entries


def _inputs(context: _Context, canyon_id: int, located: W.Located, now: float, today_utc: date, grid, rain_stations, daily_rain, automatic):
    runner = context.runner
    catalog_canyon = context.catalog[canyon_id]
    try:
        zone = ZoneInfo(located.timezone) if located.timezone else timezone.utc
    except Exception:  # noqa: BLE001
        zone = timezone.utc
    today = datetime.fromtimestamp(now, zone).date()
    lookup = runner.lookup_values(canyon_id, catalog_canyon.canyon)
    neighbors, aggregates = runner.neighbors(canyon_id)
    latitude, longitude = (float(part) for part in located.cell.split(","))
    inputs = CanyonInputs(
        canyon_id=canyon_id,
        canyon=catalog_canyon.canyon,
        weather=context.store.window(located, today),
        grid_latitude=latitude,
        grid_longitude=longitude,
        grid_elevation=located.elevation,
        static=runner.static_values(canyon_id),
        lookup=lookup,
        history=context.reports.history(canyon_id, runner.shipped_history(lookup)),
        neighbors=neighbors,
        cell_aggregates=aggregates,
        recent_cells=[c for c in grid if c[1] <= W.epoch_day(today)],
        rain_stations=rain_stations,
        rain_values={code: daily_rain[code] for code, _ in rain_stations if code in daily_rain},
        # Yesterday's automatic gauges are those of the UTC morning files.
        automatic_rain={code: automatic[code] for code, _ in rain_stations if code in automatic} if today == today_utc else {},
    )
    return inputs, today, located


def _entry(context: _Context, inputs: CanyonInputs, today: date, located: W.Located, estimates, basis, now: float) -> dict[str, Any]:
    forecast_age = context.store.forecast_age(located.cell, now)
    return entry_of(
        target=context.catalog[inputs.canyon_id].target,
        timezone_name=located.timezone or "UTC",
        lookup_source=context.runner.lookup_source(inputs.canyon_id, inputs.canyon),
        forecast_at=now - forecast_age if forecast_age is not None else now,
        history=inputs.history,
        today=today,
        estimates=estimates,
        basis=basis,
    )


def entry_of(*, target, timezone_name: str, lookup_source: str, forecast_at: float, history, today: date, estimates, basis) -> dict[str, Any]:
    """One canyon of the published file (ServerCanyonPredictionDto in the app)."""
    past = [day for day, _ in history if day < W.epoch_day(today)]
    return {
        "tz": timezone_name,
        "src": target[2],
        "lat": round(target[0], 6),
        "lon": round(target[1], 6),
        "lookup": lookup_source,
        "wx": int(forecast_at),
        "last": max(past) if past else None,
        "basis": {k: (round(v, 3) if isinstance(v, (int, float)) and math.isfinite(v) else None) for k, v in (basis or {}).items()},
        "days": [
            [e.date.isoformat(), e.horizon, [round(p, 4) for p in e.probabilities], e.rank, round(e.score, 4), round(e.high_threshold, 4)]
            for e in estimates
        ],
    }


# 5. Publication


def _publish(settings: Settings, runner: ModelRunner, entries: dict[str, Any], live_check: dict, now: float, report: dict) -> None:
    generated = datetime.fromtimestamp(now, timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")
    data = payload_bytes(runner, entries, live_check, now)
    with tempfile.TemporaryDirectory(prefix="dc-predictions-", dir=settings.data_dir) as tmp:
        source = Path(tmp) / "predictions.json.gz"
        source.write_bytes(data)
        with publication_lock(settings.lock_path):
            published = publish_file(settings.v1_dir, source, ".json.gz")
            state = manifest.load_state(settings)
            state["predictions"] = {
                "schemaVersion": SCHEMA,
                "dataVersion": generated,
                "path": published["path"],
                "sha256": published["sha256"],
                "size": published["size"],
            }
            manifest.publish(settings, state)
            prune_files(settings.v1_dir, manifest.referenced_files(state))
    report.update({"published": True, "file": published["path"], "size": published["size"]})
    report["publications"] = report.get("publications", 0) + 1


def payload_bytes(runner: ModelRunner, entries: dict[str, Any], live_check: dict, now: float) -> bytes:
    """The published file (ServerPredictionsDto in the app), gzipped."""
    payload = {
        "schema": SCHEMA,
        "generatedAt": datetime.fromtimestamp(now, timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z"),
        "computedAt": int(now),
        "modelVersion": runner.version,
        "policy": runner.policy.upper(),
        "ordinalCutpoints": runner.ordinal_cutpoints,
        "liveCheck": live_check,
        "canyons": entries,
    }
    text = json.dumps(payload, ensure_ascii=False, separators=(",", ":"), allow_nan=False)
    return gzip.compress(text.encode("utf-8"), compresslevel=9, mtime=0)
