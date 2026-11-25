"""Rain gauges near the canyons: Python twin of the app's RainGaugeSource.

* Météo-France daily climatology, one gzipped CSV per department (RR, 06-06 UTC), read for the last
  35 days;
* Météo-France hourly file, one per department (about 200 MB of text): yesterday's rain at the
  automatic gauges, sum of RR1 for the hours ending 07 UTC yesterday to 06 UTC today, the hours
  still missing in the morning file estimated as 0.125 x sqrt(sum) each (gauges with fewer than 18
  hours left out);
* MeteoSwiss SwissMetNet (CHS_<abbr>) and precipitation (CHP_<abbr>) stations, daily file.

The server reads each file once a day for every canyon, where every phone used to download the
7 to 33 MB hourly file of its department.
"""
from __future__ import annotations

import gzip
import io
import json
import math
import time
import urllib.request
from collections.abc import Callable, Iterable
from datetime import date, datetime, time as dtime, timedelta, timezone
from pathlib import Path

METEO_FRANCE_DAILY = "https://meteofrance.s3.sbg.io.cloud.ovh.net/data/synchro_ftp/BASE/QUOT/Q_{dept}_latest-{first}-{last}_RR-T-Vent.csv.gz"
METEO_FRANCE_HOURLY = "https://meteofrance.s3.sbg.io.cloud.ovh.net/data/synchro_ftp/BASE/HOR/H_{dept}_latest-{first}-{last}.csv.gz"
METEO_SWISS = "https://data.geo.admin.ch/ch.meteoschweiz.{network}/{abbr}/{network}_{abbr}_d_recent.csv"
KEEP_DAYS = 35
MIN_HOURS = 18
DAY_HOURS = 24
MISSING_HOUR_RAIN_FACTOR = 0.125
PUBLICATION_TIME_UTC = dtime(6, 15)
EPOCH = date(1970, 1, 1)

FetchStream = Callable[[str], io.BufferedIOBase | None]


def department_of(code: str) -> str:
    return code[:3] if code.startswith(("97", "98")) else code[:2]


def is_swiss(code: str) -> bool:
    return code.startswith(("CHS_", "CHP_"))


def epoch_day(day: date) -> int:
    return (day - EPOCH).days


def http_stream(url: str, user_agent: str, timeout: int = 300):
    request = urllib.request.Request(url, headers={"User-Agent": user_agent})
    try:
        return urllib.request.urlopen(request, timeout=timeout)
    except urllib.error.HTTPError as error:
        if error.code == 404:
            return None
        raise


def parse_meteo_france_daily(lines: Iterable[str], first_day: date) -> dict[str, dict[int, float]]:
    iterator = iter(lines)
    header = next(iterator, "").rstrip("\n").split(";")
    try:
        station_index, date_index, rain_index = header.index("NUM_POSTE"), header.index("AAAAMMJJ"), header.index("RR")
    except ValueError:
        return {}
    first_key = first_day.strftime("%Y%m%d")
    output: dict[str, dict[int, float]] = {}
    for line in iterator:
        columns = line.rstrip("\n").split(";")
        if len(columns) <= rain_index or columns[date_index] < first_key:
            continue
        try:
            rain = float(columns[rain_index])
            day = datetime.strptime(columns[date_index], "%Y%m%d").date()
        except ValueError:
            continue
        output.setdefault(columns[station_index], {})[epoch_day(day)] = rain
    return output


def parse_meteo_france_hourly(lines: Iterable[str], today: date) -> dict[str, float]:
    iterator = iter(lines)
    header = next(iterator, "").rstrip("\n").split(";")
    try:
        station_index, hour_index, rain_index = header.index("NUM_POSTE"), header.index("AAAAMMJJHH"), header.index("RR1")
    except ValueError:
        return {}
    yesterday = today - timedelta(days=1)
    first_hour = yesterday.strftime("%Y%m%d") + "07"
    last_hour = today.strftime("%Y%m%d") + "06"
    # Two years of hours, about 200 MB of text: only the lines of the two days are split.
    day_marks = (";" + yesterday.strftime("%Y%m%d"), ";" + today.strftime("%Y%m%d"))
    sums: dict[str, float] = {}
    hours: dict[str, int] = {}
    for line in iterator:
        if day_marks[0] not in line and day_marks[1] not in line:
            continue
        columns = line.rstrip("\n").split(";")
        if len(columns) <= max(rain_index, hour_index):
            continue
        hour = columns[hour_index]
        if len(hour) != len(first_hour) or not first_hour <= hour <= last_hour:
            continue
        try:
            rain = float(columns[rain_index])
        except ValueError:
            continue
        station = columns[station_index]
        sums[station] = sums.get(station, 0.0) + rain
        hours[station] = hours.get(station, 0) + 1
    return {
        station: total + MISSING_HOUR_RAIN_FACTOR * max(DAY_HOURS - hours[station], 0) * math.sqrt(total)
        for station, total in sums.items()
        if hours[station] >= MIN_HOURS
    }


def parse_meteo_swiss(text: str) -> dict[int, float]:
    lines = [line for line in text.splitlines() if line.strip()]
    if not lines:
        return {}
    header = lines[0].split(";")
    try:
        date_index, rain_index = header.index("reference_timestamp"), header.index("rre150d0")
    except ValueError:
        return {}
    output = {}
    for line in lines[1:]:
        columns = line.split(";")
        if len(columns) <= max(date_index, rain_index):
            continue
        try:
            rain = float(columns[rain_index].strip())
            day = datetime.strptime(columns[date_index][:10], "%d.%m.%Y").date()
        except ValueError:
            continue
        output[epoch_day(day)] = rain
    return output


class GaugeTables:
    """Tables of the day, read once and kept on disk (re-read once today's files are published)."""

    def __init__(self, directory: Path, open_url: Callable[[str], object | None], clock: Callable[[], float] = time.time) -> None:
        self.directory = directory
        self.open_url = open_url
        self.clock = clock
        directory.mkdir(parents=True, exist_ok=True)
        self.downloads = 0
        self.errors: list[str] = []
        self._memory: dict[tuple[str, date], dict] = {}

    def _fresh(self, path: Path) -> bool:
        if not path.exists():
            return False
        now = datetime.fromtimestamp(self.clock(), timezone.utc)
        publication = datetime.combine(now.date(), PUBLICATION_TIME_UTC, tzinfo=timezone.utc)
        return now < publication or path.stat().st_mtime >= publication.timestamp()

    def _cached(self, key: str, today: date, build: Callable[[], dict | None]) -> dict:
        if (key, today) in self._memory:
            return self._memory[(key, today)]
        path = self.directory / f"{key}_{today.isoformat()}.json"
        if self._fresh(path):
            table = json.loads(path.read_text())
        else:
            try:
                built = build()
            except Exception as error:  # noqa: BLE001 - a missing file leaves its stations out
                self.errors.append(f"{key}: {error!r}"[:200])
                built = None
            if built is None:
                table = json.loads(path.read_text()) if path.exists() else {}
            else:
                for old in self.directory.glob(f"{key}_*.json"):
                    old.unlink()
                path.write_text(json.dumps(built))
                table = built
        self._memory[(key, today)] = table
        return table

    def _gz_lines(self, url_template: str, dept: str, today: date):
        for first, last in ((today.year - 1, today.year), (today.year, today.year + 1)):
            response = self.open_url(url_template.format(dept=dept, first=first, last=last))
            if response is None:
                continue
            self.downloads += 1
            return io.TextIOWrapper(gzip.GzipFile(fileobj=response), encoding="utf-8", errors="replace")
        return None

    def daily(self, dept: str, today: date) -> dict[str, dict[int, float]]:
        def build():
            lines = self._gz_lines(METEO_FRANCE_DAILY, dept, today)
            if lines is None:
                return None
            with lines:
                table = parse_meteo_france_daily(lines, today - timedelta(days=KEEP_DAYS))
            return {code: {str(d): v for d, v in days.items()} for code, days in table.items()}
        key = ("FRint_" + dept, today)
        if key not in self._memory:
            self._memory[key] = {code: {int(d): v for d, v in days.items()} for code, days in self._cached(f"FR_{dept}", today, build).items()}
        return self._memory[key]

    def hourly(self, dept: str, today: date) -> dict[str, float]:
        def build():
            lines = self._gz_lines(METEO_FRANCE_HOURLY, dept, today)
            if lines is None:
                return None
            with lines:
                return parse_meteo_france_hourly(lines, today)
        return self._cached(f"FRH_{dept}", today, build)

    def swiss(self, code: str, today: date) -> dict[int, float]:
        def build():
            network = "ogd-smn" if code.startswith("CHS_") else "ogd-smn-precip"
            abbr = code.split("_", 1)[1].lower()
            response = self.open_url(METEO_SWISS.format(network=network, abbr=abbr))
            if response is None:
                return None
            self.downloads += 1
            with response:
                return {str(d): v for d, v in parse_meteo_swiss(response.read().decode("iso-8859-1")).items()}
        return {int(d): v for d, v in self._cached(code, today, build).items()}

    def rain_for(self, codes: list[str], today: date) -> tuple[dict[str, dict[int, float]], dict[str, float]]:
        """(daily rain by station and day, yesterday's rain at the automatic gauges) of [codes]."""
        yesterday = epoch_day(today) - 1
        daily: dict[str, dict[int, float]] = {}
        automatic: dict[str, float] = {}
        french = [c for c in codes if not is_swiss(c)]
        for dept in sorted({department_of(c) for c in french}):
            table = self.daily(dept, today)
            hourly = self.hourly(dept, today)
            for code in french:
                if department_of(code) != dept:
                    continue
                if code in table:
                    daily[code] = table[code]
                if code in hourly:
                    automatic[code] = hourly[code]
        for code in (c for c in codes if is_swiss(c)):
            values = self.swiss(code, today)
            if values:
                daily[code] = values
                if yesterday in values:
                    automatic[code] = values[yesterday]
        return daily, automatic
