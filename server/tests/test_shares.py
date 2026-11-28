from __future__ import annotations

import base64
import json
import zlib
from datetime import datetime, timezone

import pytest
from fastapi.testclient import TestClient

from dcserver.api import create_app
from dcserver.shares.service import CODE_ALPHABET, CODE_LENGTH, ShareService
from dcserver.tracks.canyons import CanyonReference

from .conftest import CANYON_ID, OTHER_CANYON_ID, new_install_key

NOW = datetime(2026, 7, 1, 12, 0, tzinfo=timezone.utc).timestamp()


class Clock:
    def __init__(self) -> None:
        self.now = NOW

    def __call__(self) -> float:
        return self.now


@pytest.fixture
def clock() -> Clock:
    return Clock()


@pytest.fixture
def client(settings, clock):
    reference = CanyonReference(settings.canyon_reference_path)
    service = ShareService(settings.database_path, settings.pepper, reference.exists, settings.public_url, clock=clock)
    with TestClient(create_app(settings, share_service=service)) as test_client:
        yield test_client
    service.close()


def outing(**changes) -> dict:
    payload = {
        "v": 1,
        "canyonId": CANYON_ID,
        "next": [{"canyonId": OTHER_CANYON_ID, "link": "ON_FOOT", "adjustments": {"APPROACH": 15}}],
        "date": "2026-07-04",
        "anchorTime": "08:30",
        "meetAtPlace": False,
        "groupSize": 6,
        "groupLevel": "WITH_BEGINNERS",
        "withShuttle": True,
        "adjustments": {"DESCENT": 30},
        "timing": {"prep": 20, "packUp": 20, "medium": 1.15, "pace": 1.08},
        "note": "Combinaisons 5 mm <b>pas</b> de néoprène fin",
        "summary": {"names": ["Test", "Loin"], "meet": "08:30", "walk": "09:05", "back": "16:40", "backLatest": "17:30"},
    }
    payload.update(changes)
    return payload


def post(client, payload, key, kind="OUTING", ip="203.0.113.7"):
    return client.post("/v1/shares", json={"kind": kind, "payload": payload}, headers={"X-Install-Key": key, "X-Forwarded-For": ip})


def test_an_outing_is_shared_read_changed_by_its_author_and_deleted(client):
    key = new_install_key()
    created = post(client, outing(), key)
    assert created.status_code == 201
    share = created.json()
    code = share["code"]
    assert len(code) == CODE_LENGTH and all(char in CODE_ALPHABET for char in code)
    assert share["url"] == f"https://canyon.aduquennoy.fr/s/{code}"
    assert share["version"] == 1
    # Kept 30 days after its day.
    assert share["expiresAt"] == "2026-08-04T00:00:00Z"

    read = client.get(f"/v1/shares/{code}")
    assert read.status_code == 200
    assert read.json()["payload"]["note"].startswith("Combinaisons")
    assert read.headers["etag"] == '"v1"'
    assert client.get(f"/v1/shares/{code}", headers={"If-None-Match": '"v1"'}).status_code == 304

    other = new_install_key()
    changed = outing(anchorTime="09:00")
    assert client.put(f"/v1/shares/{code}", json={"kind": "OUTING", "payload": changed}, headers={"X-Install-Key": other}).json() == {
        "error": "NOT_AUTHOR"
    }
    updated = client.put(f"/v1/shares/{code}", json={"kind": "OUTING", "payload": changed}, headers={"X-Install-Key": key})
    assert updated.json()["version"] == 2
    # The same content again: no new version for the readers.
    again = client.put(f"/v1/shares/{code}", json={"kind": "OUTING", "payload": changed}, headers={"X-Install-Key": key})
    assert again.json()["version"] == 2
    assert client.get(f"/v1/shares/{code}").json()["payload"]["anchorTime"] == "09:00"

    assert client.delete(f"/v1/shares/{code}", headers={"X-Install-Key": other}).status_code == 403
    assert client.delete(f"/v1/shares/{code}", headers={"X-Install-Key": key}).status_code == 204
    assert client.get(f"/v1/shares/{code}").json() == {"error": "NOT_FOUND"}


def test_expired_and_unknown_links(client, clock):
    key = new_install_key()
    code = post(client, outing(), key).json()["code"]
    clock.now = datetime(2026, 8, 5, tzinfo=timezone.utc).timestamp()
    assert client.get(f"/v1/shares/{code}").status_code == 404
    assert client.get("/v1/shares/abc").status_code == 404
    assert client.get("/v1/shares/0OIl00000").status_code == 404


def test_a_route_without_date_and_lists_are_kept_a_year(client):
    key = new_install_key()
    route = post(client, outing(date=None), key).json()
    assert route["expiresAt"] == "2027-07-01T12:00:00Z"
    listed = post(client, {"v": 1, "title": "Stage été", "canyonIds": [CANYON_ID, OTHER_CANYON_ID]}, key, kind="LIST").json()
    assert listed["kind"] == "LIST"


def test_a_stay_with_its_outings(client):
    key = new_install_key()
    stay = {
        "v": 1,
        "place": {"name": "Camping des Gorges", "lat": 44.1, "lon": 6.5},
        "start": "2026-07-10",
        "end": "2026-07-14",
        "outings": [outing(date="2026-07-11", place=None, v=None), outing(date="2026-07-12", meetAtPlace=True)],
    }
    created = post(client, stay, key, kind="STAY")
    assert created.status_code == 201, created.json()
    assert created.json()["expiresAt"] == "2026-08-14T00:00:00Z"
    stay["outings"].append(outing(date="2026-07-20"))
    refused = post(client, stay, key, kind="STAY")
    assert refused.json() == {"error": "INVALID_PAYLOAD", "field": "outings.2.date"}


@pytest.mark.parametrize(
    ("changes", "field"),
    [
        ({"canyonId": 999}, "canyonId"),
        ({"date": "2020-01-01"}, "date"),
        ({"anchorTime": "25:00"}, "anchorTime"),
        ({"groupLevel": "EXPERTS"}, "groupLevel"),
        ({"note": "x" * 501}, "note"),
        ({"note": "bip\x07"}, "note"),
        ({"meetAtPlace": True}, "place"),
        ({"next": [{"canyonId": OTHER_CANYON_ID, "link": "BY_PLANE"}]}, "next.0.link"),
        ({"adjustments": {"NAP": 10}}, "adjustments"),
        ({"timing": {"pace": 12}}, "timing.pace"),
        ({"v": 2}, "v"),
    ],
)
def test_invalid_outings_are_refused_with_the_field(client, changes, field):
    response = post(client, outing(**changes), new_install_key())
    assert response.status_code == 400
    assert response.json() == {"error": "INVALID_PAYLOAD", "field": field}


def test_requests_without_key_kind_or_too_large(client):
    assert client.post("/v1/shares", json={"kind": "OUTING", "payload": outing()}).json() == {"error": "MISSING_KEY"}
    assert post(client, outing(), new_install_key(), kind="PHOTO").json() == {"error": "BAD_KIND"}
    big = outing(summary={"names": ["Test"]}, extra="y" * 20_000)
    assert post(client, big, new_install_key()).status_code == 413


def test_quota_of_new_links(client):
    key = new_install_key()
    statuses = [post(client, outing(), key).status_code for _ in range(41)]
    assert statuses[:40] == [201] * 40
    assert statuses[40] == 429


def test_the_page_of_a_shared_outing(client):
    code = post(client, outing(), new_install_key()).json()["code"]
    page = client.get(f"/s/{code}", headers={"Accept-Language": "fr", "User-Agent": "Android"})
    assert page.status_code == 200
    text = page.text
    assert "Sortie partagée" in text
    assert "<h1>Test + Loin</h1>" in text
    assert "Samedi 4 juillet 2026" in text
    assert "08:30 au parking" in text
    assert "16:40, au plus tard 17:30" in text
    assert "6 personnes, avec des débutants" in text
    # The note is shown, escaped.
    assert "&lt;b&gt;pas&lt;/b&gt;" in text
    assert f"intent://canyon.aduquennoy.fr/s/{code}#Intent" in text
    assert page.headers["cache-control"] == "public, max-age=300"
    english = client.get(f"/s/{code}", headers={"Accept-Language": "en"}).text
    assert "Saturday 4 July 2026" in english
    assert client.get("/s/nothing99").status_code == 404


def test_the_pages_of_a_route_a_stay_and_a_list(client):
    key = new_install_key()
    route = post(client, outing(date=None), key).json()["code"]
    assert "Itinéraire partagé, sans date" in client.get(f"/s/{route}").text
    stay = {
        "v": 1,
        "place": {"name": "Camping des Gorges", "lat": 44.1, "lon": 6.5},
        "start": "2026-07-10",
        "end": "2026-07-14",
        "outings": [outing(date="2026-07-11")],
    }
    stay_page = client.get(f"/s/{post(client, stay, key, kind='STAY').json()['code']}", headers={"Accept-Language": "de"}).text
    assert "Vom 10. Juli bis 14. Juli 2026" in stay_page
    assert "Samstag, 11. Juli 2026" in stay_page
    listed = post(client, {"v": 1, "title": "Stage été", "canyonIds": [CANYON_ID, OTHER_CANYON_ID]}, key, kind="LIST").json()["code"]
    list_page = client.get(f"/s/{listed}").text
    assert "<h1>Stage été</h1>" in list_page
    assert "2 canyons" in list_page
    assert f'href="/c/{CANYON_ID}-test"' in list_page


def test_the_page_of_an_outing_carried_by_the_link(client):
    page = client.get(f"/o/{CANYON_ID}-test", headers={"User-Agent": "Android"})
    assert page.status_code == 200
    assert '<script src="/static/outing.js" defer></script>' in page.text
    assert "le programme de la sortie est dans le lien" in page.text.lower()
    assert client.get("/static/outing.js").status_code == 200
    assert client.get("/o/999999").status_code == 404


def test_outing_payload_as_the_app_compresses_it():
    """The app deflates the outing (raw, no header) and writes it in base64url: the page's script reads it."""
    raw = json.dumps(outing(), separators=(",", ":")).encode()
    compressor = zlib.compressobj(9, zlib.DEFLATED, -15)
    packed = base64.urlsafe_b64encode(compressor.compress(raw) + compressor.flush()).rstrip(b"=").decode()
    assert len(packed) < len(raw)
    padded = packed + "=" * (-len(packed) % 4)
    assert json.loads(zlib.decompress(base64.urlsafe_b64decode(padded), -15)) == outing()


def test_operator_takedown_and_prune(settings, clock):
    reference = CanyonReference(settings.canyon_reference_path)
    service = ShareService(settings.database_path, settings.pepper, reference.exists, settings.public_url, clock=clock)
    try:
        key = new_install_key()
        first = service.create({"kind": "OUTING", "payload": outing()}, key, "203.0.113.7")["code"]
        second = service.create({"kind": "OUTING", "payload": outing(date="2026-07-20")}, key, "203.0.113.7")["code"]
        assert service.takedown(first) is True
        assert service.takedown(first) is False
        clock.now = datetime(2026, 8, 30, tzinfo=timezone.utc).timestamp()
        assert service.prune() == 1
        assert service.takedown(second) is False
    finally:
        service.close()
