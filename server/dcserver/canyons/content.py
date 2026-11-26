"""The sheet of a canyon added in the app: the fields of a sheet of the site, checked and normalised.

The site takes no new canyon, so the app lets its users propose them; once the operator approves a
proposal, the sheet goes into the catalog as a canyon like the others. A sheet is stored as the
canonical JSON of [normalize]: only the fields that hold a value, trimmed, in the site's formats
(cotation "v4a3III", durations "1h30" / "45min" / "néant", shuttle "néant" / "5.0km").
"""
from __future__ import annotations

import json
import math
import re
import unicodedata
from typing import Any

FIRST_APP_CANYON_ID = 1_000_000
"""Ids of the app's canyons start here: the site's sheets stay far below (24,212 in 2026)."""

SHORT_FIELDS: dict[str, int] = {
    "nom": 120,
    "nomComplet": 300,
    "pays": 60,
    "region": 120,
    "departement": 120,
    "commune": 120,
    "massif": 120,
    "bassin": 120,
    "coursEau": 120,
}
DURATION_FIELDS = ("tempsApproche", "tempsDescente", "tempsRetour")
DURATION_MAX = 10
"""The site keeps its duration fields to 10 characters; the app shows them the same way."""
TEXT_FIELDS = (
    "accesAval",
    "accesAmont",
    "approche",
    "descente",
    "retour",
    "engagement",
    "periode",
    "geologie",
    "historique",
    "remarques",
)
TEXT_MAX = 20_000
TOTAL_TEXT_MAX = 80_000
INT_FIELDS: dict[str, tuple[int, int]] = {
    "altitudeDepart": (0, 6_000),
    "denivele": (0, 3_000),
    "longueur": (0, 50_000),
    "cascadeMax": (0, 500),
    "cordeMin": (0, 1_000),
}
BOOL_FIELDS = ("isForbidden",)
POINT_TYPES = ("ENTREE", "SORTIE", "PARKING_AMONT", "PARKING_AVAL", "POINT_REMARQUABLE", "ECHAPPATOIRE")
MAX_POINTS = 30
POINT_TITLE_MAX = 80
MAX_SPREAD_M = 30_000
"""Two points of one canyon farther apart than this are a mistake (a point of another canyon)."""
REQUIRED = ("nom",)
"""Besides the name, a sheet needs one point of any type and one text: the rest can come later."""
PLACE_FIELDS = ("pays", "region", "departement", "massif", "bassin")
"""Where the canyon is: when left blank, taken from the catalog canyon nearest to its [anchor]. The
commune is not, the nearest canyon is often in the next one."""

FIELD_ORDER = (
    *SHORT_FIELDS,
    "cotation",
    *INT_FIELDS,
    *DURATION_FIELDS,
    "navette",
    *TEXT_FIELDS,
    *BOOL_FIELDS,
    "points",
)
ALL_FIELDS = frozenset(FIELD_ORDER)

COTATION = re.compile(r"^v[1-7]a[1-7](I|II|III|IV|V|VI)$")
NAVETTE = re.compile(r"^(néant|\d{1,3}(\.\d)?km)$")
DURATION = re.compile(r"^(néant|\?\?|[0-9hminjàou' -]*[0-9][0-9hminjàou' -]*)$")
CONTROL = re.compile(r"[\x00-\x08\x0b-\x1f\x7f​-‏‪-‮⁦-⁩]")
EARTH_RADIUS_M = 6_371_008.8


class ContentError(ValueError):
    """A sheet that cannot be kept: [code] says why, [field] where."""

    def __init__(self, code: str, field: str | None = None) -> None:
        super().__init__(f"{code}{f' ({field})' if field else ''}")
        self.code = code
        self.field = field


def _clean_line(value: Any, field: str, limit: int) -> str:
    if not isinstance(value, str):
        raise ContentError("BAD_TYPE", field)
    text = unicodedata.normalize("NFC", CONTROL.sub(" ", value))
    text = " ".join(text.split())
    if len(text) > limit:
        raise ContentError("TOO_LONG", field)
    return text


def _clean_text(value: Any, field: str) -> str:
    if not isinstance(value, str):
        raise ContentError("BAD_TYPE", field)
    text = unicodedata.normalize("NFC", value.replace("\r\n", "\n").replace("\r", "\n"))
    text = CONTROL.sub(lambda m: m.group(0) if m.group(0) == "\t" else " ", text)
    lines = [" ".join(line.split()) for line in text.split("\n")]
    # At most one empty line between paragraphs.
    kept: list[str] = []
    for line in lines:
        if line or (kept and kept[-1]):
            kept.append(line)
    text = "\n".join(kept).strip()
    if len(text) > TEXT_MAX:
        raise ContentError("TOO_LONG", field)
    return text


def _int(value: Any, field: str) -> int | None:
    if value is None or value == "":
        return None
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ContentError("BAD_TYPE", field)
    if isinstance(value, float):
        if not math.isfinite(value) or value != int(value):
            raise ContentError("BAD_TYPE", field)
        value = int(value)
    low, high = INT_FIELDS[field]
    if not low <= value <= high:
        raise ContentError("OUT_OF_RANGE", field)
    return value


def _cotation(value: Any) -> str | None:
    text = _clean_line(value, "cotation", 12).replace(" ", "")
    if text in ("", "??"):
        return None
    text = re.sub(r"^[vV](\d)[aA]", r"v\1a", text)
    if not COTATION.match(text):
        raise ContentError("BAD_FORMAT", "cotation")
    return text


def _navette(value: Any) -> str | None:
    text = _clean_line(value, "navette", 12).lower().replace(" ", "").replace(",", ".")
    if text in ("", "??"):
        return None
    if text in ("non", "aucune", "0", "0km", "0.0km"):
        return "néant"
    if re.match(r"^\d{1,3}(\.\d)?$", text):
        text += "km"
    if re.match(r"^\d{1,3}km$", text):
        text = text[:-2] + ".0km"
    if not NAVETTE.match(text):
        raise ContentError("BAD_FORMAT", "navette")
    return text


def _duration(value: Any, field: str) -> str | None:
    text = _clean_line(value, field, DURATION_MAX)
    if text in ("", "??"):
        return None
    if text.lower() in ("neant", "néant", "aucun", "aucune"):
        return "néant"
    if not DURATION.match(text.lower()):
        raise ContentError("BAD_FORMAT", field)
    return text


def distance_m(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    phi1, phi2 = math.radians(lat1), math.radians(lat2)
    d_phi = phi2 - phi1
    d_lambda = math.radians(lon2 - lon1)
    a = math.sin(d_phi / 2) ** 2 + math.cos(phi1) * math.cos(phi2) * math.sin(d_lambda / 2) ** 2
    return 2 * EARTH_RADIUS_M * math.asin(min(1.0, math.sqrt(a)))


def _points(value: Any) -> list[dict[str, Any]]:
    if not isinstance(value, list):
        raise ContentError("BAD_TYPE", "points")
    if len(value) > MAX_POINTS:
        raise ContentError("TOO_MANY_POINTS", "points")
    points = []
    for item in value:
        if not isinstance(item, dict) or set(item) - {"type", "latitude", "longitude", "title"}:
            raise ContentError("BAD_TYPE", "points")
        kind = item.get("type")
        if kind not in POINT_TYPES:
            raise ContentError("BAD_POINT_TYPE", "points")
        latitude, longitude = item.get("latitude"), item.get("longitude")
        for coordinate in (latitude, longitude):
            if isinstance(coordinate, bool) or not isinstance(coordinate, (int, float)) or not math.isfinite(coordinate):
                raise ContentError("BAD_COORDINATES", "points")
        if not (-90 <= latitude <= 90 and -180 <= longitude <= 180) or (abs(latitude) < 1e-6 and abs(longitude) < 1e-6):
            raise ContentError("BAD_COORDINATES", "points")
        point: dict[str, Any] = {"type": kind, "latitude": round(float(latitude), 6), "longitude": round(float(longitude), 6)}
        title = _clean_line(item.get("title") or "", "points", POINT_TITLE_MAX)
        if title:
            point["title"] = title
        points.append(point)
    if not points:
        raise ContentError("POINT_REQUIRED", "points")
    for i, a in enumerate(points):
        for b in points[i + 1 :]:
            if distance_m(a["latitude"], a["longitude"], b["latitude"], b["longitude"]) > MAX_SPREAD_M:
                raise ContentError("POINTS_TOO_FAR_APART", "points")
    return points


def normalize(content: Any) -> dict[str, Any]:
    """The canonical sheet of [content], or a ContentError."""
    if not isinstance(content, dict):
        raise ContentError("BAD_TYPE")
    unknown = set(content) - ALL_FIELDS
    if unknown:
        raise ContentError("UNKNOWN_FIELD", sorted(unknown)[0])
    sheet: dict[str, Any] = {}
    for field, limit in SHORT_FIELDS.items():
        if field in content and content[field] is not None:
            text = _clean_line(content[field], field, limit)
            if text:
                sheet[field] = text
    if content.get("cotation") is not None:
        cotation = _cotation(content["cotation"])
        if cotation:
            sheet["cotation"] = cotation
    for field in INT_FIELDS:
        number = _int(content.get(field), field)
        if number is not None:
            sheet[field] = number
    for field in DURATION_FIELDS:
        if content.get(field) is not None:
            duration = _duration(content[field], field)
            if duration:
                sheet[field] = duration
    if content.get("navette") is not None:
        navette = _navette(content["navette"])
        if navette:
            sheet["navette"] = navette
    total = 0
    for field in TEXT_FIELDS:
        if content.get(field) is not None:
            text = _clean_text(content[field], field)
            total += len(text)
            if text:
                sheet[field] = text
    if total > TOTAL_TEXT_MAX:
        raise ContentError("TOO_LONG")
    for field in BOOL_FIELDS:
        value = content.get(field)
        if value is not None and not isinstance(value, bool):
            raise ContentError("BAD_TYPE", field)
        if value:
            sheet[field] = True
    sheet["points"] = _points(content.get("points", []))
    for field in REQUIRED:
        if not sheet.get(field):
            raise ContentError("REQUIRED", field)
    if not any(sheet.get(field) for field in TEXT_FIELDS):
        raise ContentError("TEXT_REQUIRED")
    return {field: sheet[field] for field in FIELD_ORDER if field in sheet}


def anchor(sheet: dict[str, Any]) -> dict[str, Any] | None:
    """The point that places the canyon: its entry, else its first point (a parking, an exit...)."""
    points = sheet.get("points") or []
    return next((point for point in points if point["type"] == "ENTREE"), points[0] if points else None)


def with_place(sheet: dict[str, Any], place: dict[str, str] | None) -> dict[str, Any]:
    """[sheet] with its blank place fields taken from [place], in the order of the sheet."""
    if not place:
        return sheet
    filled = dict(sheet)
    for field in PLACE_FIELDS:
        value = place.get(field)
        if not filled.get(field) and isinstance(value, str) and value.strip():
            filled[field] = _clean_line(value, field, SHORT_FIELDS[field])
    return {field: filled[field] for field in FIELD_ORDER if field in filled}


def canonical(sheet: dict[str, Any]) -> str:
    return json.dumps(sheet, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def diff(before: dict[str, Any] | None, after: dict[str, Any]) -> list[dict[str, Any]]:
    """The fields that differ, in the order of the sheet."""
    before = before or {}
    changes = []
    for field in FIELD_ORDER:
        old, new = before.get(field), after.get(field)
        if old != new:
            changes.append({"field": field, "before": old, "after": new})
    return changes
