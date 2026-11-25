"""Small geodesic helpers: distances, simplification, length, elevation gain."""
from __future__ import annotations

import hashlib
import json
import math
from collections.abc import Iterable, Sequence

from .gpx import Point

EARTH_RADIUS_M = 6_371_008.8


def haversine_m(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    phi1, phi2 = math.radians(lat1), math.radians(lat2)
    d_phi = phi2 - phi1
    d_lambda = math.radians(lon2 - lon1)
    a = math.sin(d_phi / 2) ** 2 + math.cos(phi1) * math.cos(phi2) * math.sin(d_lambda / 2) ** 2
    return 2 * EARTH_RADIUS_M * math.asin(min(1.0, math.sqrt(a)))


def _project(point: Point, lat0: float, lon0: float) -> tuple[float, float]:
    """Local equirectangular projection in metres (accurate enough over a few tens of km)."""
    x = math.radians(point[1] - lon0) * EARTH_RADIUS_M * math.cos(math.radians(lat0))
    y = math.radians(point[0] - lat0) * EARTH_RADIUS_M
    return x, y


def _segment_distance(p: tuple[float, float], a: tuple[float, float], b: tuple[float, float]) -> float:
    dx, dy = b[0] - a[0], b[1] - a[1]
    length2 = dx * dx + dy * dy
    if length2 == 0.0:
        return math.hypot(p[0] - a[0], p[1] - a[1])
    t = max(0.0, min(1.0, ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / length2))
    return math.hypot(p[0] - (a[0] + t * dx), p[1] - (a[1] + t * dy))


def simplify(points: Sequence[Point], tolerance_m: float) -> list[Point]:
    """Douglas-Peucker on the horizontal positions (iterative: no recursion limit)."""
    if len(points) <= 2:
        return list(points)
    lat0, lon0 = points[0][0], points[0][1]
    projected = [_project(point, lat0, lon0) for point in points]
    keep = [False] * len(points)
    keep[0] = keep[-1] = True
    stack = [(0, len(points) - 1)]
    while stack:
        start, end = stack.pop()
        farthest, index = 0.0, -1
        for i in range(start + 1, end):
            distance = _segment_distance(projected[i], projected[start], projected[end])
            if distance > farthest:
                farthest, index = distance, i
        if index != -1 and farthest > tolerance_m:
            keep[index] = True
            stack.append((start, index))
            stack.append((index, end))
    return [point for point, kept in zip(points, keep) if kept]


def length_m(segments: Iterable[Sequence[Point]]) -> float:
    total = 0.0
    for segment in segments:
        for a, b in zip(segment, segment[1:]):
            total += haversine_m(a[0], a[1], b[0], b[1])
    return total


def elevation_gain(segments: Sequence[Sequence[Point]], threshold_m: float = 5.0) -> tuple[int, int] | None:
    """Ascent and descent with a hysteresis that ignores GPS noise below [threshold_m]; None when
    fewer than half of the points have an elevation."""
    points = [point for segment in segments for point in segment]
    with_elevation = sum(1 for point in points if point[2] is not None)
    if not points or with_elevation * 2 < len(points):
        return None
    ascent = descent = 0.0
    for segment in segments:
        reference: float | None = None
        for point in segment:
            elevation = point[2]
            if elevation is None:
                continue
            if reference is None:
                reference = elevation
                continue
            delta = elevation - reference
            if delta >= threshold_m:
                ascent += delta
                reference = elevation
            elif delta <= -threshold_m:
                descent -= delta
                reference = elevation
    return round(ascent), round(descent)


def bbox(segments: Iterable[Sequence[Point]]) -> tuple[float, float, float, float]:
    """(min longitude, min latitude, max longitude, max latitude)."""
    points = [point for segment in segments for point in segment]
    lats = [point[0] for point in points]
    lons = [point[1] for point in points]
    return min(lons), min(lats), max(lons), max(lats)


def nearest_distance_m(points: Iterable[Point], references: Sequence[tuple[float, float]]) -> float:
    best = math.inf
    for point in points:
        for lat, lon in references:
            distance = haversine_m(point[0], point[1], lat, lon)
            if distance < best:
                best = distance
    return best


def farthest_distance_m(points: Iterable[Point], references: Sequence[tuple[float, float]]) -> float:
    """Largest distance from a track point to its closest reference point."""
    worst = 0.0
    for point in points:
        closest = min(haversine_m(point[0], point[1], lat, lon) for lat, lon in references)
        worst = max(worst, closest)
    return worst


def rounded(segments: Sequence[Sequence[Point]]) -> list[list[list[float]]]:
    """GeoJSON-like coordinates [lon, lat(, ele)] rounded to ~1 m."""
    result: list[list[list[float]]] = []
    for segment in segments:
        coordinates: list[list[float]] = []
        for lat, lon, ele in segment:
            coordinate = [round(lon, 6), round(lat, 6)]
            if ele is not None:
                coordinate.append(round(ele, 1))
            coordinates.append(coordinate)
        result.append(coordinates)
    return result


def geometry_hash(coordinates: list[list[list[float]]]) -> str:
    horizontal = [[[round(c[0], 5), round(c[1], 5)] for c in segment] for segment in coordinates]
    return hashlib.sha256(json.dumps(horizontal, separators=(",", ":")).encode("ascii")).hexdigest()
