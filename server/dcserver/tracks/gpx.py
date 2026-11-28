"""GPX reading, hardened against hostile files (no DTD, no entities, bounded size and points).

Only the geometry is kept: track and route points with their elevation. Times are dropped (they
would tell the dates and pace of the person); only the month of the first point survives, to say
how recent the track is. Names, descriptions, links and waypoints are ignored.
"""
from __future__ import annotations

import math
from dataclasses import dataclass
from datetime import datetime
from xml.etree.ElementTree import ParseError

import defusedxml
import defusedxml.ElementTree as SafeET

MAX_POINTS = 50_000
MIN_ELEVATION_M = -500.0
MAX_ELEVATION_M = 9_000.0

Point = tuple[float, float, float | None]
"""(latitude, longitude, elevation in metres or None)."""


class GpxError(ValueError):
    def __init__(self, code: str, detail: str = "") -> None:
        super().__init__(f"{code}: {detail}" if detail else code)
        self.code = code


@dataclass(frozen=True)
class ParsedGpx:
    segments: list[list[Point]]
    recorded_month: str | None

    @property
    def point_count(self) -> int:
        return sum(len(segment) for segment in self.segments)


def _local(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def _child_text(element, name: str) -> str | None:
    for child in element:
        if _local(child.tag) == name:
            return (child.text or "").strip() or None
    return None


def _parse_point(element) -> Point | None:
    try:
        latitude = float(element.attrib["lat"])
        longitude = float(element.attrib["lon"])
    except (KeyError, ValueError):
        return None
    if not (math.isfinite(latitude) and math.isfinite(longitude)):
        return None
    if not (-90.0 <= latitude <= 90.0 and -180.0 <= longitude <= 180.0):
        return None
    elevation: float | None = None
    raw_elevation = _child_text(element, "ele")
    if raw_elevation is not None:
        try:
            value = float(raw_elevation)
        except ValueError:
            value = math.nan
        if math.isfinite(value) and MIN_ELEVATION_M <= value <= MAX_ELEVATION_M:
            elevation = value
    return latitude, longitude, elevation


def _month_of(raw_time: str | None) -> str | None:
    if not raw_time:
        return None
    try:
        moment = datetime.fromisoformat(raw_time.replace("Z", "+00:00"))
    except ValueError:
        return None
    if not 1990 <= moment.year <= 2100:
        return None
    return f"{moment.year:04d}-{moment.month:02d}"


def parse_gpx(data: bytes) -> ParsedGpx:
    if not data.strip():
        raise GpxError("INVALID_GPX", "empty file")
    try:
        root = SafeET.fromstring(data, forbid_dtd=True, forbid_entities=True, forbid_external=True)
    except defusedxml.DefusedXmlException as exc:
        raise GpxError("INVALID_GPX", "forbidden XML construct") from exc
    except ParseError as exc:
        raise GpxError("INVALID_GPX", "not well-formed XML") from exc
    if _local(root.tag) != "gpx":
        raise GpxError("INVALID_GPX", "root element is not <gpx>")

    segments: list[list[Point]] = []
    first_time: str | None = None
    total = 0
    for container in root.iter():
        name = _local(container.tag)
        if name not in {"trkseg", "rte"}:
            continue
        point_name = "trkpt" if name == "trkseg" else "rtept"
        segment: list[Point] = []
        for element in container:
            if _local(element.tag) != point_name:
                continue
            total += 1
            if total > MAX_POINTS:
                raise GpxError("TOO_MANY_POINTS")
            point = _parse_point(element)
            if point is None:
                continue
            if first_time is None:
                first_time = _child_text(element, "time")
            segment.append(point)
        if len(segment) >= 2:
            segments.append(segment)
    if not segments:
        raise GpxError("TOO_FEW_POINTS", "no track or route with two points")
    return ParsedGpx(segments=segments, recorded_month=_month_of(first_time))


def write_gpx(geometry: list[list[list[float]]], name: str) -> bytes:
    """A GPX file of a stored track (no time: the server never keeps them), for the page of its link."""
    from xml.sax.saxutils import escape, quoteattr

    segments = []
    for segment in geometry:
        points = []
        for coordinate in segment:
            lon, lat = coordinate[0], coordinate[1]
            ele = f"<ele>{coordinate[2]}</ele>" if len(coordinate) > 2 else ""
            points.append(f"<trkpt lat={quoteattr(str(lat))} lon={quoteattr(str(lon))}>{ele}</trkpt>")
        segments.append("<trkseg>" + "".join(points) + "</trkseg>")
    return (
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        '<gpx version="1.1" creator="Descente Canyon" xmlns="http://www.topografix.com/GPX/1/1">'
        f"<metadata><name>{escape(name)}</name><copyright author=\"Descente Canyon\"><license>https://creativecommons.org/publicdomain/zero/1.0/</license></copyright></metadata>"
        f"<trk><name>{escape(name)}</name>{''.join(segments)}</trk></gpx>\n"
    ).encode("utf-8")
