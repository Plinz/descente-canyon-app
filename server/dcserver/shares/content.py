"""What a shared link may carry: an outing (planned on a day, or a route without date), a stay (a
place, its days and their outings) or a list of canyons. Only the fields the pages and the app read
are checked; unknown fields of a later app version are kept as they are, within the size limit.

No name, no account: what the person chose to share and nothing else. The note of an outing is the
only free text; the pages show it escaped, never as a link.
"""
from __future__ import annotations

import re
from datetime import date, timedelta
from typing import Any

KINDS = ("OUTING", "STAY", "LIST")
PAYLOAD_VERSION = 1
MAX_PAYLOAD_BYTES = 16 * 1024
MAX_NOTE = 500
MAX_NAME = 120
MAX_CANYONS_PER_OUTING = 4
MAX_STAY_OUTINGS = 21
MAX_LIST_CANYONS = 200
MAX_STAY_DAYS = 31
LINKS = ("CONTINUOUS", "ON_FOOT", "BY_CAR")
PHASES = ("SHUTTLE", "PREP", "APPROACH", "DESCENT", "RETURN", "PACK_UP")
LEVELS = ("AUTONOMOUS", "WITH_BEGINNERS", "DISCOVERY")
_TIME = re.compile(r"^([01]\d|2[0-3]):[0-5]\d$")
_DATE = re.compile(r"^\d{4}-\d{2}-\d{2}$")
TIMING_BOUNDS: dict[str, tuple[float, float]] = {
    "prep": (0, 240),
    "packUp": (0, 240),
    "shuttleHandling": (0, 240),
    "medium": (0.5, 4),
    "large": (0.5, 4),
    "huge": (0.5, 4),
    "beginners": (0.5, 4),
    "discovery": (0.5, 4),
    "beginnersPrep": (0.5, 4),
    "earlier": (0, 100),
    "later": (0, 200),
    "pace": (0.2, 4),
}


class ShareContentError(Exception):
    def __init__(self, field: str) -> None:
        super().__init__(field)
        self.field = field


def _int(value: Any, field: str, low: int, high: int) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or not low <= value <= high:
        raise ShareContentError(field)
    return value


def _number(value: Any, field: str, low: float, high: float) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not low <= value <= high:
        raise ShareContentError(field)
    return float(value)


def _text(value: Any, field: str, limit: int, *, required: bool = False) -> str:
    if value is None and not required:
        return ""
    if not isinstance(value, str) or len(value) > limit or (required and not value.strip()):
        raise ShareContentError(field)
    if any(ord(char) < 32 and char not in "\n\t" for char in value):
        raise ShareContentError(field)
    return value


def _date(value: Any, field: str, today: date, *, required: bool) -> date | None:
    if value is None and not required:
        return None
    if not isinstance(value, str) or not _DATE.match(value):
        raise ShareContentError(field)
    try:
        day = date.fromisoformat(value)
    except ValueError as exc:
        raise ShareContentError(field) from exc
    # A link is shared before the day, or during it; never for a day long gone or years ahead.
    if not today - timedelta(days=7) <= day <= today + timedelta(days=550):
        raise ShareContentError(field)
    return day


def _time(value: Any, field: str, *, required: bool = True) -> str | None:
    if value is None and not required:
        return None
    if not isinstance(value, str) or not _TIME.match(value):
        raise ShareContentError(field)
    return value


def _adjustments(value: Any, field: str) -> None:
    if value is None:
        return
    if not isinstance(value, dict) or len(value) > len(PHASES):
        raise ShareContentError(field)
    for phase, minutes in value.items():
        if phase not in PHASES:
            raise ShareContentError(field)
        _int(minutes, field, -720, 720)


def _place(value: Any, field: str, *, required: bool) -> None:
    if value is None and not required:
        return
    if not isinstance(value, dict):
        raise ShareContentError(field)
    _text(value.get("name"), f"{field}.name", 80, required=True)
    _number(value.get("lat"), f"{field}.lat", -90, 90)
    _number(value.get("lon"), f"{field}.lon", -180, 180)


def _canyon_id(value: Any, field: str, known: callable) -> int:
    canyon_id = _int(value, field, 1, 2_000_000_000)
    if not known(canyon_id):
        raise ShareContentError(field)
    return canyon_id


def check_outing(payload: Any, today: date, known: callable, *, in_stay: bool = False) -> date | None:
    """Checks an outing; returns its day (None for a route shared without date)."""
    if not isinstance(payload, dict):
        raise ShareContentError("payload")
    if not in_stay:
        _int(payload.get("v"), "v", PAYLOAD_VERSION, PAYLOAD_VERSION)
    _canyon_id(payload.get("canyonId"), "canyonId", known)
    following = payload.get("next") or []
    if not isinstance(following, list) or len(following) > MAX_CANYONS_PER_OUTING - 1:
        raise ShareContentError("next")
    for index, canyon in enumerate(following):
        if not isinstance(canyon, dict):
            raise ShareContentError(f"next.{index}")
        _canyon_id(canyon.get("canyonId"), f"next.{index}.canyonId", known)
        if canyon.get("link") not in LINKS:
            raise ShareContentError(f"next.{index}.link")
        _adjustments(canyon.get("adjustments"), f"next.{index}.adjustments")
    day = _date(payload.get("date"), "date", today, required=in_stay)
    _time(payload.get("anchorTime"), "anchorTime")
    meet_at_place = payload.get("meetAtPlace", False)
    if not isinstance(meet_at_place, bool):
        raise ShareContentError("meetAtPlace")
    if not in_stay:
        _place(payload.get("place"), "place", required=meet_at_place)
    _int(payload.get("groupSize"), "groupSize", 1, 60)
    if payload.get("groupLevel") not in LEVELS:
        raise ShareContentError("groupLevel")
    if payload.get("withShuttle") not in (None, True, False):
        raise ShareContentError("withShuttle")
    _adjustments(payload.get("adjustments"), "adjustments")
    timing = payload.get("timing")
    if timing is not None:
        if not isinstance(timing, dict):
            raise ShareContentError("timing")
        for name, (low, high) in TIMING_BOUNDS.items():
            if timing.get(name) is not None:
                _number(timing[name], f"timing.{name}", low, high)
    _text(payload.get("note"), "note", MAX_NOTE)
    check_summary(payload.get("summary"))
    return day


def check_summary(summary: Any) -> None:
    """What the organizer's phone computed, shown as is by the page: names and a few times."""
    if summary is None:
        return
    if not isinstance(summary, dict):
        raise ShareContentError("summary")
    names = summary.get("names") or []
    if not isinstance(names, list) or len(names) > MAX_CANYONS_PER_OUTING:
        raise ShareContentError("summary.names")
    for index, name in enumerate(names):
        _text(name, f"summary.names.{index}", MAX_NAME, required=True)
    for name in ("meet", "walk", "back", "backLatest"):
        _time(summary.get(name), f"summary.{name}", required=False)
    _text(summary.get("meetPlace"), "summary.meetPlace", 80)


def check_stay(payload: Any, today: date, known: callable) -> date:
    """Checks a stay; returns its last day."""
    if not isinstance(payload, dict):
        raise ShareContentError("payload")
    _int(payload.get("v"), "v", PAYLOAD_VERSION, PAYLOAD_VERSION)
    _place(payload.get("place"), "place", required=True)
    start = _date(payload.get("start"), "start", today, required=True)
    end = _date(payload.get("end"), "end", today, required=True)
    if end < start or (end - start).days >= MAX_STAY_DAYS:
        raise ShareContentError("end")
    outings = payload.get("outings") or []
    if not isinstance(outings, list) or len(outings) > MAX_STAY_OUTINGS:
        raise ShareContentError("outings")
    for index, outing in enumerate(outings):
        try:
            day = check_outing(outing, today, known, in_stay=True)
        except ShareContentError as exc:
            raise ShareContentError(f"outings.{index}.{exc.field}") from exc
        if day is None or not start <= day <= end:
            raise ShareContentError(f"outings.{index}.date")
    return end


def check_list(payload: Any, known: callable) -> None:
    if not isinstance(payload, dict):
        raise ShareContentError("payload")
    _int(payload.get("v"), "v", PAYLOAD_VERSION, PAYLOAD_VERSION)
    _text(payload.get("title"), "title", 80)
    canyons = payload.get("canyonIds")
    if not isinstance(canyons, list) or not 1 <= len(canyons) <= MAX_LIST_CANYONS:
        raise ShareContentError("canyonIds")
    for index, canyon_id in enumerate(canyons):
        _canyon_id(canyon_id, f"canyonIds.{index}", known)
    if len(set(canyons)) != len(canyons):
        raise ShareContentError("canyonIds")


def last_day(kind: str, payload: Any, today: date, known: callable) -> date | None:
    """Checks [payload] for [kind]; the last day it is about (None when it has none)."""
    if kind == "OUTING":
        return check_outing(payload, today, known)
    if kind == "STAY":
        return check_stay(payload, today, known)
    if kind == "LIST":
        check_list(payload, known)
        return None
    raise ShareContentError("kind")
