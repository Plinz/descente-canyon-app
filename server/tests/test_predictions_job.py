from __future__ import annotations

import gzip
import json
import math
import sqlite3
from dataclasses import replace
from datetime import date, datetime, timedelta, timezone
from pathlib import Path

import pytest

from dcserver import manifest
from dcserver.predictions import job as J
from dcserver.predictions import weather as W
from dcserver.predictions.gauges import parse_meteo_france_hourly

REPO = Path(__file__).resolve().parents[2]
HTML = REPO / "core/data/src/test/resources/html"
NOW = datetime(2026, 11, 25, 6, 45, tzinfo=timezone.utc).timestamp()
# Canyons of the lookups with reports, neighbours and rain gauges; two share a grid cell.
CANYONS = {
    23222: (45.20, 6.40, 1500.0),
    23223: (45.21, 6.41, 1100.0),
    2268: (43.90, 7.10, 600.0),
}


def cell_of(latitude: float, longitude: float) -> tuple[float, float]:
    return round(latitude * 4) / 4, round(longitude * 4) / 4


def elevation_of(latitude: float, longitude: float) -> float:
    for lat, lon, elevation in CANYONS.values():
        if abs(lat - latitude) < 1e-6 and abs(lon - longitude) < 1e-6:
            return elevation
    raise AssertionError(f"unexpected point {latitude},{longitude}")


class FakeOpenMeteo:
    """Open-Meteo answering a point with its cell's values, the temperature brought to the point."""

    def __init__(self) -> None:
        self.calls: list[tuple[str, dict]] = []

    def __call__(self, url: str, params: dict) -> dict:
        self.calls.append((url, params))
        latitude, longitude = params["latitude"], params["longitude"]
        cell = cell_of(latitude, longitude)
        elevation = elevation_of(latitude, longitude)
        if "start_date" in params:
            first, last = date.fromisoformat(params["start_date"]), date.fromisoformat(params["end_date"])
        else:
            today = datetime.fromtimestamp(NOW, timezone.utc).date()
            first = today - timedelta(days=params.get("past_days", 0))
            last = today + timedelta(days=params["forecast_days"] - 1)
        days = [first + timedelta(days=i) for i in range((last - first).days + 1)]
        rain = [round(4 + 4 * math.sin(d.toordinal() / 3.0 + cell[0]), 1) if d.toordinal() % 3 else 0.0 for d in days]
        temp = [round(12 + 8 * math.sin((d.timetuple().tm_yday - 110) / 58.0) - 0.0065 * elevation, 1) for d in days]
        daily = {"time": [d.isoformat() for d in days], "precipitation_sum": rain}
        if "temperature_2m_mean" in params["daily"]:
            daily["temperature_2m_mean"] = temp
        return {"latitude": cell[0], "longitude": cell[1], "elevation": elevation, "timezone": "Europe/Paris", "daily": daily}


def make_catalog(path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    connection = sqlite3.connect(path)
    connection.executescript(
        """
        CREATE TABLE canyons (id INTEGER PRIMARY KEY, region TEXT, massif TEXT, altitudeDepart REAL, denivele REAL, longueur REAL, cascadeMax REAL);
        CREATE TABLE watersheds (canyonId INTEGER PRIMARY KEY, bboxMinLongitude REAL, bboxMinLatitude REAL, bboxMaxLongitude REAL, bboxMaxLatitude REAL);
        CREATE TABLE geo_points (id INTEGER PRIMARY KEY, canyonId INTEGER, type TEXT, latitude REAL, longitude REAL);
        """
    )
    for canyon_id, (lat, lon, elevation) in CANYONS.items():
        connection.execute("INSERT INTO canyons VALUES (?, 'Alpes', NULL, ?, 200, 1500, 20)", (canyon_id, elevation))
        if canyon_id == 2268:
            connection.execute("INSERT INTO geo_points(canyonId, type, latitude, longitude) VALUES (?, 'SORTIE', ?, ?)", (canyon_id, lat - 0.01, lon))
            connection.execute("INSERT INTO geo_points(canyonId, type, latitude, longitude) VALUES (?, 'ENTREE', ?, ?)", (canyon_id, lat, lon))
        else:
            connection.execute("INSERT INTO watersheds VALUES (?, ?, ?, ?, ?)", (canyon_id, lon - 0.02, lat - 0.01, lon + 0.02, lat + 0.01))
    connection.commit()
    connection.close()


def sources(fake: FakeOpenMeteo, pages: list[str], clock=lambda: NOW) -> J.Sources:
    grid = (HTML / "derniers_debits_grid.html").read_text()
    debits = (HTML / "canyon_debits.html").read_text()

    def fetch_text(url: str) -> str:
        if url == J.RP.GRID_URL:
            return grid
        pages.append(url)
        return debits

    return J.Sources(fetch_json=fake, open_url=lambda url: None, fetch_text=fetch_text, clock=clock, sleep=lambda s: None)


@pytest.fixture
def job_settings(settings):
    make_catalog(settings.data_dir / "catalog" / "current.db")
    return replace(settings, repo_dir=REPO, openmeteo_seconds_per_call=0.0, openmeteo_reserve=0.0, debit_pages_per_run=3)


@pytest.mark.slow
def test_estimates_every_canyon_and_publishes_them(job_settings, public_key):
    fake = FakeOpenMeteo()
    pages: list[str] = []
    report = J.run(job_settings, sources=sources(fake, pages))

    assert "aborted" not in report, report
    assert report["published"] is True
    weather = report["weather"]
    assert weather["locate"] == 3
    # Two canyons share a cell: two windows filled, two forecasts.
    assert weather["cells"] == 2 and weather["cellsComplete"] == 2
    assert weather["archiveFill"] == 2 and weather["forecast"] == 2
    assert weather["callsToday"] == pytest.approx(3 + 2 * 400 / 14 + 2 * 17 / 14, abs=0.06)
    assert report["debitPages"]["read"] == 3 and len(pages) == 3
    assert report["estimated"] == 3

    payload = manifest.read_published_payload(job_settings.v1_dir, public_key)
    entry = payload["predictions"]
    data = (job_settings.v1_dir / entry["path"]).read_bytes()
    assert len(data) == entry["size"]
    published = json.loads(gzip.decompress(data))
    assert published["schema"] == 1 and published["policy"] == "BALANCED"
    assert set(published["canyons"]) == {"23222", "23223", "2268"}
    canyon = published["canyons"]["23222"]
    assert canyon["src"] == "WATERSHED_CENTER" and canyon["tz"] == "Europe/Paris"
    assert published["canyons"]["2268"]["src"] == "ENTRY"
    assert [day[0] for day in canyon["days"]] == ["2026-11-25", "2026-11-26", "2026-11-27"]
    for day in canyon["days"]:
        assert sum(day[2]) == pytest.approx(1.0, abs=1e-3)
        assert 0 <= day[3] <= 5
    assert {"histDaysSinceLast", "wPrecip3d", "mfCount"} <= set(canyon["basis"])
    assert published["liveCheck"]["byHorizon"][0]["horizon"] == 0

    # The canyons of one cell get the cell's rain and their own temperature.
    store = W.WeatherStore(job_settings.data_dir / "predictions" / "weather.sqlite3")
    high, low = store.located(23222), store.located(23223)
    today = date(2026, 11, 25)
    high_window, low_window = store.window(high, today), store.window(low, today)
    store.close()
    assert high.cell == low.cell
    assert high_window[today][0] == low_window[today][0]
    assert low_window[today][1] - high_window[today][1] == pytest.approx(0.0065 * 400, abs=0.11)

    # A second run the same morning: nothing to fetch, the estimates are published again.
    fake.calls.clear()
    again = J.run(job_settings, sources=sources(fake, pages, clock=lambda: NOW + 600))
    assert again["published"] is True
    assert fake.calls == []
    assert again["weather"]["forecast"] == 0 and again["weather"]["archiveRefresh"] == 0


def test_the_budget_stops_the_filling_and_keeps_the_reserve(job_settings):
    fake = FakeOpenMeteo()
    tight = replace(job_settings, openmeteo_daily_calls=20.0, openmeteo_reserve=0.0)
    report = J.run(tight, sources=sources(fake, []))
    weather = report["weather"]
    assert weather["locate"] == 3
    assert weather["archiveFill"] == 0
    assert weather["callsToday"] <= 20.0
    assert report["unpublished"] == "NO_ESTIMATE"

    reserved = replace(job_settings, openmeteo_daily_calls=100.0, openmeteo_reserve=80.0)
    report = J.run(reserved, sources=sources(fake, []))
    assert report["weather"]["archiveFill"] == 0
    assert report["weather"]["callsToday"] <= 20.0


def test_without_a_catalog_the_job_waits_for_the_catalog_job(settings):
    report = J.run(replace(settings, repo_dir=REPO), sources=sources(FakeOpenMeteo(), []))
    assert report["aborted"] == "NO_CATALOG"


def test_live_check_scores_the_first_estimate_against_the_reports(tmp_path):
    live = J.LiveCheck(tmp_path / "live.sqlite3")

    class Estimate:
        def __init__(self, day, horizon, rank, level):
            self.date, self.horizon, self.rank, self.level = day, horizon, rank, level

    today = date(2026, 11, 25)
    live.record(1, [Estimate(today, 0, 3, "HIGH"), Estimate(today + timedelta(days=1), 1, 2, "MEDIUM")], 0.0)
    live.record(1, [Estimate(today, 0, 0, "LOW")], 10.0)  # later estimate of the same day: ignored
    live.record(2, [Estimate(today, 0, 1, "LOW")], 0.0)
    reports = {1: [(W.epoch_day(today), 4)], 2: [(W.epoch_day(today), 3)]}
    scores = live.score(lambda canyon_id: reports.get(canyon_id, []), today)
    live.close()
    j0 = scores["byHorizon"][0]
    assert j0 == {"horizon": 0, "reports": 2, "exact": 0, "withinOne": 1, "highReports": 2, "highCaught": 1, "highShown": 1, "highShownRight": 1}
    assert scores["byHorizon"][1]["reports"] == 0


def test_hourly_gauges_sum_yesterday_and_estimate_the_missing_hours():
    header = "NUM_POSTE;NOM_USUEL;LAT;LON;ALTI;AAAAMMJJHH;RR1;QRR1"
    lines = [header]
    for hour in range(7, 24):
        lines.append(f"38185001;GRENOBLE;45;5;200;20261124{hour:02d};1.0;1")
    for hour in range(0, 4):
        lines.append(f"38185001;GRENOBLE;45;5;200;20261125{hour:02d};0.0;1")
    lines.append("38185002;AUTRE;45;5;200;2026112410;5.0;1")  # too few hours
    lines.append("38185001;GRENOBLE;45;5;200;2026112306;9.0;1")  # before the window
    table = parse_meteo_france_hourly(lines, date(2026, 11, 25))
    assert set(table) == {"38185001"}
    assert table["38185001"] == pytest.approx(17 + 0.125 * 3 * math.sqrt(17))
