from __future__ import annotations

import base64
import json
import secrets
from pathlib import Path

import pytest

from dcserver import signing
from dcserver.config import Settings

CANYON_ID = 21
CANYON_POINTS = [[45.0, 6.0], [45.01, 6.01]]
OTHER_CANYON_ID = 22
UNLOCATED_CANYON_ID = 23


@pytest.fixture
def settings(tmp_path: Path) -> Settings:
    data = tmp_path / "data"
    publish = tmp_path / "publish"
    data.mkdir()
    publish.mkdir()
    key_path = tmp_path / "secrets" / "manifest-key.pem"
    signing.write_private_key(key_path)
    reference = {
        "generatedAt": "2026-11-25T00:00:00Z",
        "canyons": {
            str(CANYON_ID): {"name": "Test", "points": CANYON_POINTS},
            str(OTHER_CANYON_ID): {"name": "Loin", "points": [[43.0, 1.0]]},
            str(UNLOCATED_CANYON_ID): {"name": "Sans point", "points": []},
        },
    }
    (data / "canyon-reference.json").write_text(json.dumps(reference), encoding="utf-8")
    return Settings(
        data_dir=data,
        publish_dir=publish,
        repo_dir=tmp_path / "repo",
        pepper=b"p" * 32,
        signing_key_path=key_path,
        request_delay_s=0.0,
    )


@pytest.fixture
def public_key(settings: Settings) -> str:
    return signing.public_key_b64(signing.load_private_key(settings.signing_key_path))


def new_install_key() -> str:
    return base64.urlsafe_b64encode(secrets.token_bytes(32)).decode("ascii").rstrip("=")


def make_gpx(points: list[tuple[float, float, float | None]], *, time: str | None = "2026-06-14T08:12:00Z", route: bool = False) -> bytes:
    def point(tag: str, lat: float, lon: float, ele: float | None, index: int) -> str:
        children = ""
        if ele is not None:
            children += f"<ele>{ele}</ele>"
        if time is not None and index == 0:
            children += f"<time>{time}</time>"
        return f'<{tag} lat="{lat}" lon="{lon}">{children}</{tag}>'

    if route:
        body = "<rte>" + "".join(point("rtept", lat, lon, ele, i) for i, (lat, lon, ele) in enumerate(points)) + "</rte>"
    else:
        body = "<trk><name>Ma trace</name><trkseg>" + "".join(point("trkpt", lat, lon, ele, i) for i, (lat, lon, ele) in enumerate(points)) + "</trkseg></trk>"
    return (
        '<?xml version="1.0" encoding="UTF-8"?>'
        '<gpx version="1.1" creator="test" xmlns="http://www.topografix.com/GPX/1/1">' + body + "</gpx>"
    ).encode("utf-8")


def walk(start_lat: float = 45.0, start_lon: float = 6.0, steps: int = 40, step_deg: float = 0.0004, wiggle: float = 0.0) -> list[tuple[float, float, float | None]]:
    """A walk of about steps x 45 m heading north-east with rising elevation."""
    points = []
    for i in range(steps):
        offset = wiggle if i % 2 else -wiggle
        points.append((start_lat + i * step_deg + offset, start_lon + i * step_deg, 800.0 + i * 6))
    return points
