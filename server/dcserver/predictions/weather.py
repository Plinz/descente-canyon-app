"""Daily weather of every canyon's weather point, kept on the server.

Same sources and merge rule as the app (DebitPredictionSupportRepositoryImpl): ERA5 reanalysis
(Open-Meteo archive, the training source) for the past, ECMWF IFS 0.25 deg (forecast endpoint,
14 past days + 3 forecast days) for the days ERA5 does not cover yet and for the forecast.

The weather is kept by grid cell, not by canyon. Open-Meteo answers a point with the values of one
0.25 deg cell (the same cell for ERA5 and IFS, checked on 12 points on 2026-11-25), the temperature
corrected to the point's elevation with a lapse rate of 0.65 degC / 100 m (the correction the
training store already relies on, update_weather_targets.cell_series). So each canyon is located
once (one cheap call: its cell, elevation and time zone), and the 400-day window and the forecasts
are read once per cell: about 630 cells for 4,100 canyons.

Open-Meteo's free plan (non-commercial use) allows 10,000 calls a day, 5,000 an hour and 600 a
minute: calls are weighted as Open-Meteo counts them (a call over more than 14 days counts as
several), paced under the hourly limit and stopped at the daily budget.
"""
from __future__ import annotations

import json
import sqlite3
import time
import urllib.error
import urllib.parse
import urllib.request
from collections.abc import Callable
from dataclasses import dataclass
from datetime import date, datetime, timedelta, timezone
from pathlib import Path

FORECAST_URL = "https://api.open-meteo.com/v1/forecast"
ARCHIVE_URL = "https://archive-api.open-meteo.com/v1/archive"
DAILY_VARIABLES = "precipitation_sum,temperature_2m_mean"
FORECAST_MODEL = "ecmwf_ifs025"
ARCHIVE_MODEL = "era5"
FORECAST_PAST_DAYS = 14
FORECAST_DAYS = 3
WINDOW_DAYS = 400
ARCHIVE_REFRESH_DAYS = 12
ARCHIVE_REFRESH_EVERY_S = 2 * 86400 - 3600
LAPSE_RATE_C_PER_M = 0.0065
EPOCH = date(1970, 1, 1)

FetchJson = Callable[[str, dict], dict]


class BudgetExhausted(RuntimeError):
    pass


def epoch_day(day: date) -> int:
    return (day - EPOCH).days


def from_epoch_day(day: int) -> date:
    return EPOCH + timedelta(days=day)


def call_weight(days: int, variables: int = 2) -> float:
    """Open-Meteo counts a call over more than 14 days or more than 10 variables as several."""
    return max(1.0, days / 14.0) * max(1.0, variables / 10.0)


def cell_key(latitude: float, longitude: float) -> str:
    return f"{latitude:.4f},{longitude:.4f}"


def http_fetch_json(url: str, params: dict, user_agent: str, timeout: int = 120) -> dict:
    """GET with a few retries on rate limiting and server errors."""
    full = f"{url}?{urllib.parse.urlencode(params)}"
    request = urllib.request.Request(full, headers={"User-Agent": user_agent})
    for attempt in range(4):
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                return json.loads(response.read().decode("utf-8"))
        except urllib.error.HTTPError as error:
            if error.code not in (429, 500, 502, 503, 504) or attempt == 3:
                raise
            time.sleep(65 if error.code == 429 else 5 * (attempt + 1))
    raise RuntimeError("unreachable")


@dataclass
class Located:
    canyon_id: int
    latitude: float
    longitude: float
    cell: str | None
    elevation: float | None
    timezone: str | None


class WeatherStore:
    def __init__(self, path: Path) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        self.connection = sqlite3.connect(path, isolation_level=None)
        self.connection.execute("PRAGMA journal_mode=WAL")
        self.connection.executescript(
            """
            CREATE TABLE IF NOT EXISTS canyon (
                canyon_id INTEGER PRIMARY KEY, latitude REAL NOT NULL, longitude REAL NOT NULL,
                cell TEXT, elevation REAL, timezone TEXT, located_at REAL);
            CREATE TABLE IF NOT EXISTS cell (
                cell TEXT PRIMARY KEY, latitude REAL NOT NULL, longitude REAL NOT NULL,
                forecast_at REAL, archive_at REAL);
            CREATE TABLE IF NOT EXISTS cell_daily (
                cell TEXT NOT NULL, day INTEGER NOT NULL, source TEXT NOT NULL,
                precip REAL, temp0 REAL, PRIMARY KEY (cell, day, source));
            CREATE TABLE IF NOT EXISTS budget (day TEXT PRIMARY KEY, calls REAL NOT NULL);
            """
        )

    def close(self) -> None:
        self.connection.close()

    # Canyons

    def located(self, canyon_id: int) -> Located | None:
        row = self.connection.execute(
            "SELECT canyon_id, latitude, longitude, cell, elevation, timezone FROM canyon WHERE canyon_id = ?", (canyon_id,)
        ).fetchone()
        return Located(*row) if row else None

    def set_target(self, canyon_id: int, latitude: float, longitude: float) -> bool:
        """Records the canyon's weather point; a moved point (new watershed) is located again.
        True when the canyon must be located."""
        current = self.located(canyon_id)
        if current and abs(current.latitude - latitude) < 1e-7 and abs(current.longitude - longitude) < 1e-7:
            return current.cell is None
        self.connection.execute(
            "INSERT OR REPLACE INTO canyon(canyon_id, latitude, longitude) VALUES (?, ?, ?)", (canyon_id, latitude, longitude)
        )
        return True

    def save_location(self, canyon_id: int, payload: dict, now: float) -> str:
        cell = cell_key(payload["latitude"], payload["longitude"])
        self.connection.execute(
            "UPDATE canyon SET cell = ?, elevation = ?, timezone = ?, located_at = ? WHERE canyon_id = ?",
            (cell, payload.get("elevation"), payload.get("timezone"), now, canyon_id),
        )
        self.connection.execute(
            "INSERT OR IGNORE INTO cell(cell, latitude, longitude) VALUES (?, ?, ?)", (cell, payload["latitude"], payload["longitude"])
        )
        return cell

    def representative(self, cell: str) -> Located | None:
        """The member canyon whose point is asked for the cell (the same point every time)."""
        row = self.connection.execute(
            "SELECT canyon_id, latitude, longitude, cell, elevation, timezone FROM canyon "
            "WHERE cell = ? AND elevation IS NOT NULL ORDER BY canyon_id LIMIT 1",
            (cell,),
        ).fetchone()
        return Located(*row) if row else None

    def cells(self) -> list[str]:
        return [row[0] for row in self.connection.execute("SELECT DISTINCT cell FROM canyon WHERE cell IS NOT NULL")]

    def drop_unused_cells(self) -> None:
        self.connection.execute("DELETE FROM cell_daily WHERE cell NOT IN (SELECT DISTINCT cell FROM canyon WHERE cell IS NOT NULL)")
        self.connection.execute("DELETE FROM cell WHERE cell NOT IN (SELECT DISTINCT cell FROM canyon WHERE cell IS NOT NULL)")

    def forget_canyons(self, keep: set[int]) -> None:
        for (canyon_id,) in self.connection.execute("SELECT canyon_id FROM canyon").fetchall():
            if canyon_id not in keep:
                self.connection.execute("DELETE FROM canyon WHERE canyon_id = ?", (canyon_id,))

    # Window

    def window(self, canyon: Located, today: date) -> dict[date, tuple[float, float]]:
        """The canyon's merged series: ERA5 where it has the day, IFS otherwise (complete days only),
        the temperature brought to the canyon's elevation."""
        first = epoch_day(today) - WINDOW_DAYS - 2
        last = epoch_day(today) + FORECAST_DAYS
        merged: dict[int, tuple[float, float]] = {}
        shift = LAPSE_RATE_C_PER_M * (canyon.elevation or 0.0)
        for day, precip, temp0 in self.connection.execute(
            # F before A: the archive overrides the forecast.
            "SELECT day, precip, temp0 FROM cell_daily WHERE cell = ? AND day BETWEEN ? AND ? ORDER BY source DESC",
            (canyon.cell, first, last),
        ):
            if precip is not None and temp0 is not None:
                merged[day] = (precip, round(temp0 - shift, 1))
        return {from_epoch_day(day): values for day, values in merged.items()}

    def archive_complete(self, cell: str, today_utc: date) -> bool:
        window_start = today_utc - timedelta(days=WINDOW_DAYS)
        stable_end = today_utc - timedelta(days=ARCHIVE_REFRESH_DAYS + 1)
        count = self.connection.execute(
            "SELECT COUNT(*) FROM cell_daily WHERE cell = ? AND source = 'A' AND precip IS NOT NULL AND temp0 IS NOT NULL AND day BETWEEN ? AND ?",
            (cell, epoch_day(window_start), epoch_day(stable_end)),
        ).fetchone()[0]
        return count >= (stable_end - window_start).days + 1

    def archive_range(self, cell: str, today_utc: date, now: float) -> tuple[date, date] | None:
        """Days the archive must be read for: the whole window when it has holes, else the last
        days ERA5 may still revise, every other day."""
        if not self.archive_complete(cell, today_utc):
            return today_utc - timedelta(days=WINDOW_DAYS), today_utc - timedelta(days=1)
        archive_at = self.connection.execute("SELECT archive_at FROM cell WHERE cell = ?", (cell,)).fetchone()
        if archive_at is None or archive_at[0] is None or now - archive_at[0] >= ARCHIVE_REFRESH_EVERY_S:
            return today_utc - timedelta(days=ARCHIVE_REFRESH_DAYS), today_utc - timedelta(days=1)
        return None

    def forecast_age(self, cell: str, now: float) -> float | None:
        row = self.connection.execute("SELECT forecast_at FROM cell WHERE cell = ?", (cell,)).fetchone()
        return None if row is None or row[0] is None else now - row[0]

    def save_daily(self, cell: str, source: str, payload: dict, elevation: float, now: float) -> None:
        daily = payload.get("daily") or {}
        shift = LAPSE_RATE_C_PER_M * elevation
        rows = [
            (cell, epoch_day(date.fromisoformat(day)), source, p, None if t is None else t + shift)
            for day, p, t in zip(daily.get("time", []), daily.get("precipitation_sum", []), daily.get("temperature_2m_mean", []))
        ]
        column = "forecast_at" if source == "F" else "archive_at"
        self.connection.execute("BEGIN")
        self.connection.executemany("INSERT OR REPLACE INTO cell_daily VALUES (?, ?, ?, ?, ?)", rows)
        self.connection.execute(f"UPDATE cell SET {column} = ? WHERE cell = ?", (now, cell))
        self.connection.execute("COMMIT")

    def prune(self, today_utc: date) -> None:
        self.connection.execute("DELETE FROM cell_daily WHERE day < ?", (epoch_day(today_utc) - WINDOW_DAYS - 40,))
        self.connection.execute("DELETE FROM budget WHERE day < ?", ((today_utc - timedelta(days=30)).isoformat(),))

    # Budget

    def calls_today(self, today_utc: date) -> float:
        row = self.connection.execute("SELECT calls FROM budget WHERE day = ?", (today_utc.isoformat(),)).fetchone()
        return row[0] if row else 0.0

    def add_calls(self, today_utc: date, calls: float) -> None:
        self.connection.execute(
            "INSERT INTO budget(day, calls) VALUES (?, ?) ON CONFLICT(day) DO UPDATE SET calls = calls + excluded.calls",
            (today_utc.isoformat(), calls),
        )


class CellMismatch(RuntimeError):
    """Open-Meteo answered the representative point with another cell than when it was located."""


class OpenMeteo:
    """Weighted, paced Open-Meteo calls within a daily budget."""

    def __init__(
        self,
        store: WeatherStore,
        fetch: FetchJson,
        daily_budget: float,
        seconds_per_call: float,
        api_key: str | None = None,
        clock: Callable[[], float] = time.time,
        sleep: Callable[[float], None] = time.sleep,
    ) -> None:
        self.store = store
        self.fetch = fetch
        self.daily_budget = daily_budget
        self.seconds_per_call = seconds_per_call
        self.api_key = api_key
        self.clock = clock
        self.sleep = sleep
        self._next_at = 0.0
        self.calls = 0.0

    def today_utc(self) -> date:
        return datetime.fromtimestamp(self.clock(), timezone.utc).date()

    def remaining(self) -> float:
        return self.daily_budget - self.store.calls_today(self.today_utc())

    def _call(self, url: str, params: dict, weight: float, reserve: float = 0.0) -> dict:
        if self.remaining() - reserve < weight:
            raise BudgetExhausted(f"daily budget of {self.daily_budget:g} calls reached")
        wait = self._next_at - self.clock()
        if wait > 0:
            self.sleep(wait)
        if self.api_key:
            params = {**params, "apikey": self.api_key}
            url = url.replace("://api.", "://customer-api.").replace("://archive-api.", "://customer-archive-api.")
        try:
            return self.fetch(url, params)
        finally:
            # A failed call is counted too: Open-Meteo may have counted it.
            self.store.add_calls(self.today_utc(), weight)
            self.calls += weight
            self._next_at = self.clock() + weight * self.seconds_per_call

    def locate(self, latitude: float, longitude: float, reserve: float = 0.0) -> dict:
        params = {
            "latitude": latitude, "longitude": longitude, "daily": "precipitation_sum", "forecast_days": 1,
            "models": FORECAST_MODEL, "timezone": "auto",
        }
        return self._call(FORECAST_URL, params, 1.0, reserve)

    def forecast(self, point: Located, reserve: float = 0.0) -> dict:
        params = {
            "latitude": point.latitude, "longitude": point.longitude, "daily": DAILY_VARIABLES,
            "past_days": FORECAST_PAST_DAYS, "forecast_days": FORECAST_DAYS, "models": FORECAST_MODEL, "timezone": "auto",
        }
        return self._checked(point, self._call(FORECAST_URL, params, call_weight(FORECAST_PAST_DAYS + FORECAST_DAYS), reserve))

    def archive(self, point: Located, start: date, end: date, reserve: float = 0.0) -> dict:
        params = {
            "latitude": point.latitude, "longitude": point.longitude, "start_date": start.isoformat(),
            "end_date": end.isoformat(), "daily": DAILY_VARIABLES, "models": ARCHIVE_MODEL, "timezone": "auto",
        }
        return self._checked(point, self._call(ARCHIVE_URL, params, call_weight((end - start).days + 1), reserve))

    @staticmethod
    def _checked(point: Located, payload: dict) -> dict:
        if cell_key(payload["latitude"], payload["longitude"]) != point.cell:
            raise CellMismatch(f"canyon {point.canyon_id}: {payload['latitude']},{payload['longitude']} instead of {point.cell}")
        return payload
