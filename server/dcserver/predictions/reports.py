"""Flow reports the model reads: the site's day grid and each canyon's report list.

* The "derniers débits" grid (one page: the last 16 days of every canyon, the lowest level when
  several) is read at every run and kept day by day, so the server's history goes on growing after
  the reports shipped in the lookups.
* The report list of each canyon (its debit page) is read by the histories job, one slice of the
  canyons a day; until then the lookups' history stands in, as in the app.
"""
from __future__ import annotations

import json
import re
from datetime import date, timedelta
from pathlib import Path

from bs4 import BeautifulSoup

GRID_URL = "https://www.descente-canyon.com/canyoning/derniers-debits"
DEBIT_PAGE_URL = "https://www.descente-canyon.com/canyoning/canyon-debit/{id}/observations.html"
GRID_KEEP_DAYS = 120
RECENT_DAYS = 16
EPOCH = date(1970, 1, 1)
FRENCH_MONTHS = {
    "janvier": 1, "janv": 1, "février": 2, "fevrier": 2, "févr": 2, "fevr": 2, "mars": 3, "avril": 4, "avr": 4,
    "mai": 5, "juin": 6, "juillet": 7, "juil": 7, "août": 8, "aout": 8, "septembre": 9, "sept": 9,
    "octobre": 10, "oct": 10, "novembre": 11, "nov": 11, "décembre": 12, "decembre": 12, "déc": 12, "dec": 12,
}
GRID_START = re.compile(r"(\d{1,2})\s+(\w+)\.?\s+(\d{4})", re.UNICODE)
GRID_LEVEL = re.compile(r"\bd([1-6])\b")
CANYON_LINK = re.compile(r"/canyon/(\d+)/")


def epoch_day(day: date) -> int:
    return (day - EPOCH).days


def parse_grid(html: str) -> list[tuple[int, int, int]]:
    """(canyon id, epoch day, rank) of every cell of the grid page (DebitParser.parseLatestDebitGrid)."""
    soup = BeautifulSoup(html, "html.parser")
    table = soup.select_one("table#listedebit")
    if table is None:
        return []
    headers = table.select("thead th")
    match = GRID_START.search(headers[-1].get_text(" ", strip=True)) if headers else None
    if match is None or match.group(2).lower() not in FRENCH_MONTHS:
        return []
    start = date(int(match.group(3)), FRENCH_MONTHS[match.group(2).lower()], int(match.group(1)))
    cells = []
    for row in table.select("tbody tr"):
        tds = row.find_all("td", recursive=False)
        if len(tds) < 5:
            continue
        link = tds[1].find("a")
        found = CANYON_LINK.search(link.get("href", "")) if link else None
        if not found:
            continue
        canyon_id = int(found.group(1))
        for column in range(4, len(tds)):
            span = tds[column].select_one("span.ic-tint")
            if span is None:
                continue
            level = GRID_LEVEL.search(" ".join(span.get("class", [])))
            if level is None:
                continue
            cells.append((canyon_id, epoch_day(start + timedelta(days=column - 4)), 6 - int(level.group(1))))
    return cells


class ReportStore:
    def __init__(self, data_dir: Path) -> None:
        self.grid_dir = data_dir / "predictions" / "grid"
        self.debits_dir = data_dir / "debits"
        self.grid_dir.mkdir(parents=True, exist_ok=True)
        self.debits_dir.mkdir(parents=True, exist_ok=True)
        self._accumulated: dict[int, dict[int, int]] | None = None

    def save_grid(self, cells: list[tuple[int, int, int]], today: date) -> None:
        (self.grid_dir / f"{today.isoformat()}.json").write_text(json.dumps(cells))
        for path in self.grid_dir.glob("*.json"):
            try:
                if (today - date.fromisoformat(path.stem)).days > GRID_KEEP_DAYS:
                    path.unlink()
            except ValueError:
                continue
        self._accumulated = None

    def latest_grid(self) -> list[tuple[int, int, int]]:
        paths = sorted(self.grid_dir.glob("*.json"))
        return [tuple(c) for c in json.loads(paths[-1].read_text())] if paths else []

    def grid_cells(self) -> dict[int, dict[int, int]]:
        """Every cell of the grids kept, by canyon and day (the lowest level of the day)."""
        if self._accumulated is None:
            cells: dict[int, dict[int, int]] = {}
            for path in sorted(self.grid_dir.glob("*.json")):
                for canyon_id, day, rank in json.loads(path.read_text()):
                    days = cells.setdefault(canyon_id, {})
                    days[day] = min(rank, days.get(day, rank))
            self._accumulated = cells
        return self._accumulated

    def save_page(self, canyon_id: int, reports: list[tuple[int, int]], read_at: str) -> None:
        payload = {"readAt": read_at, "reports": sorted(set(reports))}
        path = self.debits_dir / f"{canyon_id}.json"
        path.with_suffix(".part").write_text(json.dumps(payload))
        path.with_suffix(".part").replace(path)

    def page(self, canyon_id: int) -> list[tuple[int, int]] | None:
        path = self.debits_dir / f"{canyon_id}.json"
        if not path.exists():
            return None
        return [tuple(r) for r in json.loads(path.read_text())["reports"]]

    def history(self, canyon_id: int, shipped: list[tuple[int, int]]) -> list[tuple[int, int]]:
        """The canyon's reports (its page, else those shipped in the lookups), completed by the grid
        cells of the days they lack (DebitFeatureBuilder.canyonHistory)."""
        reports = self.page(canyon_id)
        base = reports if reports else shipped
        known = {day for day, _ in base}
        extra = [(day, rank) for day, rank in self.grid_cells().get(canyon_id, {}).items() if day not in known]
        return sorted(base) + sorted(extra)


def recent_cells(cells: list[tuple[int, int, int]], today: date) -> list[tuple[int, int, int]]:
    """The cells of the last 16 days: what the app reads from the grid it downloads."""
    first = epoch_day(today) - RECENT_DAYS
    return [c for c in cells if c[1] >= first]


def parse_debit_page(pipeline_lib, label_quality, features, canyon_id: int, html: str) -> list[tuple[int, int]]:
    """The reports of a debit page as (epoch day, rank), without the message-only high reports
    (fire closures, works...), like the app."""
    reports = pipeline_lib.parse_canyon_debit_page(canyon_id=canyon_id, canyon_name=None, html=html, source_url=DEBIT_PAGE_URL.format(id=canyon_id))
    output = []
    for report in reports:
        level = report.get("niveau")
        if not report.get("date") or level not in features.RANK_BY_LEVEL:
            continue
        rank = features.RANK_BY_LEVEL[level]
        if label_quality.is_message_only_high_report(rank, report.get("isDescended"), report.get("comment")):
            continue
        output.append((epoch_day(date.fromisoformat(report["date"][:10])), rank))
    return output
