from __future__ import annotations

import pytest

from dcserver.tracks import geometry, gpx
from dcserver.tracks.gpx import GpxError, parse_gpx

from .conftest import make_gpx, walk


def test_reads_track_points_elevation_and_month_only():
    parsed = parse_gpx(make_gpx([(45.0, 6.0, 800.0), (45.001, 6.001, 810.5), (45.002, 6.002, None)]))
    assert parsed.segments == [[(45.0, 6.0, 800.0), (45.001, 6.001, 810.5), (45.002, 6.002, None)]]
    assert parsed.recorded_month == "2026-06"


def test_reads_routes_and_files_without_namespace():
    parsed = parse_gpx(make_gpx([(45.0, 6.0, None), (45.001, 6.001, None)], route=True, time=None))
    assert parsed.point_count == 2 and parsed.recorded_month is None
    bare = b'<gpx><trk><trkseg><trkpt lat="1" lon="2"/><trkpt lat="1.001" lon="2.001"/></trkseg></trk></gpx>'
    assert parse_gpx(bare).point_count == 2


@pytest.mark.parametrize(
    "payload",
    [
        # Billion laughs
        b'<?xml version="1.0"?><!DOCTYPE lolz [<!ENTITY lol "lol"><!ENTITY lol2 "&lol;&lol;">]><gpx>&lol2;</gpx>',
        # External entity
        b'<?xml version="1.0"?><!DOCTYPE gpx [<!ENTITY xxe SYSTEM "file:///etc/passwd">]><gpx>&xxe;</gpx>',
        # Not XML, not GPX, empty
        b"not xml at all",
        b"<kml><Document/></kml>",
        b"   ",
    ],
)
def test_hostile_or_foreign_files_are_refused(payload):
    with pytest.raises(GpxError) as error:
        parse_gpx(payload)
    assert error.value.code == "INVALID_GPX"


def test_bounds_and_counts(monkeypatch):
    with pytest.raises(GpxError) as error:
        parse_gpx(make_gpx([(45.0, 6.0, None)]))
    assert error.value.code == "TOO_FEW_POINTS"
    monkeypatch.setattr(gpx, "MAX_POINTS", 10)
    with pytest.raises(GpxError) as error:
        parse_gpx(make_gpx(walk(steps=11)))
    assert error.value.code == "TOO_MANY_POINTS"
    parsed = parse_gpx(make_gpx([(95.0, 6.0, None), (45.0, 6.0, 99999.0), (45.001, 6.001, 10.0)]))
    assert parsed.segments == [[(45.0, 6.0, None), (45.001, 6.001, 10.0)]]


def test_distances_length_and_simplification():
    assert geometry.haversine_m(48.8566, 2.3522, 45.7640, 4.8357) == pytest.approx(391_500, rel=0.01)
    straight = [(45.0 + i * 0.0001, 6.0, None) for i in range(50)]
    assert len(geometry.simplify(straight, 1.0)) == 2
    corner = [(45.0, 6.0, None), (45.001, 6.0, None), (45.001, 6.001, None)]
    assert len(geometry.simplify(corner, 1.0)) == 3
    assert geometry.length_m([corner]) == pytest.approx(111.2 + 78.7, rel=0.02)


def test_elevation_gain_ignores_gps_noise():
    noisy = [(45.0, 6.0, 1000.0 + (2 if i % 2 else -2)) for i in range(20)]
    assert geometry.elevation_gain([noisy]) == (0, 0)
    climb = [(45.0, 6.0, 1000.0 + i * 10) for i in range(11)] + [(45.0, 6.0, 1050.0)]
    assert geometry.elevation_gain([climb]) == (100, 50)
    assert geometry.elevation_gain([[(45.0, 6.0, None), (45.0, 6.0, None), (45.0, 6.0, 3.0)]]) is None


def test_geometry_hash_is_stable_under_metre_noise():
    coordinates = geometry.rounded([walk(steps=5)])
    nudged = [[[c[0] + 0.0000001, c[1], *c[2:]] for c in segment] for segment in coordinates]
    assert geometry.geometry_hash(coordinates) == geometry.geometry_hash(nudged)
