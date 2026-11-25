from __future__ import annotations

import dataclasses

import pytest
from fastapi.testclient import TestClient

from dcserver.api import create_app
from dcserver.tracks.canyons import CanyonReference
from dcserver.tracks.service import TrackService

from .conftest import CANYON_ID, OTHER_CANYON_ID, UNLOCATED_CANYON_ID, make_gpx, new_install_key, walk


class Clock:
    def __init__(self) -> None:
        self.now = 1_790_000_000.0

    def __call__(self) -> float:
        return self.now


@pytest.fixture
def clock() -> Clock:
    return Clock()


@pytest.fixture
def client(settings, clock):
    service = TrackService(settings.database_path, settings.pepper, CanyonReference(settings.canyon_reference_path), clock=clock)
    with TestClient(create_app(settings, service)) as test_client:
        yield test_client
    service.close()


def upload(client, key, points=None, role="APPROACH", canyon=CANYON_ID, ip="203.0.113.7"):
    return client.post(
        f"/v1/canyons/{canyon}/tracks",
        params={"role": role},
        content=make_gpx(points or walk()),
        headers={"X-Install-Key": key, "Content-Type": "application/gpx+xml", "X-Forwarded-For": ip},
    )


def vote(client, track_id, value, key, ip):
    return client.put(f"/v1/tracks/{track_id}/vote", json={"value": value}, headers={"X-Install-Key": key, "X-Forwarded-For": ip})


def tracks_of(client, canyon=CANYON_ID):
    response = client.get(f"/v1/canyons/{canyon}/tracks")
    assert response.status_code == 200
    return response.json()["tracks"]


def test_health_and_empty_list(client):
    assert client.get("/v1/health").json() == {"status": "ok"}
    assert tracks_of(client) == []
    assert client.get("/v1/canyons/999/tracks").json() == {"error": "UNKNOWN_CANYON"}
    assert client.get("/v1/canyons/abc/tracks").status_code == 400


def test_upload_returns_the_simplified_track(client):
    response = upload(client, new_install_key())
    assert response.status_code == 201, response.text
    track = response.json()
    assert track["canyonId"] == CANYON_ID and track["role"] == "APPROACH"
    assert track["standing"] == "NEW" and (track["up"], track["down"]) == (0, 0)
    assert track["recordedMonth"] == "2026-06"
    assert 1_500 < track["lengthM"] < 2_500 and track["ascentM"] == 234
    assert track["pointCount"] == 2  # a straight walk keeps its two ends
    assert [c[:2] for c in track["geometry"][0]] == [[6.0, 45.0], [6.0156, 45.0156]]
    assert tracks_of(client)[0]["id"] == track["id"]


@pytest.mark.parametrize(
    ("kwargs", "status", "code"),
    [
        ({"role": "SWIM"}, 400, "BAD_ROLE"),
        ({"canyon": 999}, 404, "UNKNOWN_CANYON"),
        ({"canyon": UNLOCATED_CANYON_ID}, 422, "UNLOCATED_CANYON"),
        ({"canyon": OTHER_CANYON_ID}, 422, "TOO_FAR"),
        ({"points": walk(steps=2, step_deg=0.0001)}, 422, "TOO_SHORT"),
        ({"points": walk(start_lat=45.0, steps=3, step_deg=0.08)}, 422, "TOO_FAR"),
        ({"points": walk(start_lat=45.0, steps=3, step_deg=0.2)}, 422, "TOO_LONG"),
    ],
)
def test_upload_checks(client, kwargs, status, code):
    response = upload(client, new_install_key(), **kwargs)
    assert (response.status_code, response.json()) == (status, {"error": code})


def test_upload_needs_a_well_formed_key_and_a_small_valid_file(client, settings):
    assert upload(client, "short").json() == {"error": "MISSING_KEY"}
    response = client.post(
        f"/v1/canyons/{CANYON_ID}/tracks",
        params={"role": "RETURN"},
        content=b"<gpx><oops",
        headers={"X-Install-Key": new_install_key()},
    )
    assert (response.status_code, response.json()) == (422, {"error": "INVALID_GPX"})
    big = client.post(
        f"/v1/canyons/{CANYON_ID}/tracks",
        params={"role": "RETURN"},
        content=b" " * (settings.max_upload_bytes + 1),
        headers={"X-Install-Key": new_install_key()},
    )
    assert (big.status_code, big.json()) == (413, {"error": "TOO_LARGE"})


def test_same_geometry_twice_is_a_duplicate(client):
    assert upload(client, new_install_key()).status_code == 201
    response = upload(client, new_install_key(), role="RETURN")
    assert (response.status_code, response.json()) == (409, {"error": "DUPLICATE"})


def test_votes_endorse_then_reference_and_rank(client):
    author = new_install_key()
    first = upload(client, author).json()
    second = upload(client, new_install_key(), points=walk(start_lon=6.0005, wiggle=0.0002)).json()
    assert vote(client, first["id"], 1, author, "203.0.113.7").json() == {"error": "OWN_TRACK"}
    for i in range(3):
        response = vote(client, first["id"], 1, new_install_key(), f"198.51.{i}.10")
        assert response.status_code == 200, response.text
    endorsed = response.json()
    assert (endorsed["up"], endorsed["standing"], endorsed["isReference"]) == (3, "ENDORSED", True)
    listed = tracks_of(client)
    assert [t["id"] for t in listed] == [first["id"], second["id"]]
    assert listed[1]["isReference"] is False


def test_one_network_cannot_outvote_the_others(client):
    track = upload(client, new_install_key()).json()
    for _ in range(5):
        response = vote(client, track["id"], -1, new_install_key(), "192.0.2.55")
    assert (response.json()["down"], response.json()["standing"]) == (2, "DISPUTED")
    response = vote(client, track["id"], -1, new_install_key(), "2001:db8:1::1")
    assert (response.json()["down"], response.json()["standing"]) == (3, "DISCREDITED")


def test_discredited_tracks_come_last_and_votes_can_change(client):
    bad = upload(client, new_install_key()).json()
    good = upload(client, new_install_key(), points=walk(start_lon=6.0005, wiggle=0.0002)).json()
    voters = [(new_install_key(), f"198.51.{i}.1") for i in range(3)]
    for key, ip in voters:
        vote(client, bad["id"], -1, key, ip)
    assert [t["id"] for t in tracks_of(client)] == [good["id"], bad["id"]]
    assert tracks_of(client)[1]["standing"] == "DISCREDITED"
    key, ip = voters[0]
    assert vote(client, bad["id"], 1, key, ip).json()["down"] == 2
    assert vote(client, bad["id"], 0, key, ip).json()["up"] == 0
    assert vote(client, bad["id"], 2, key, ip).json() == {"error": "BAD_VOTE"}


def test_only_the_author_deletes(client):
    author = new_install_key()
    track = upload(client, author).json()
    stranger = client.delete(f"/v1/tracks/{track['id']}", headers={"X-Install-Key": new_install_key()})
    assert (stranger.status_code, stranger.json()) == (403, {"error": "NOT_AUTHOR"})
    assert client.delete(f"/v1/tracks/{track['id']}", headers={"X-Install-Key": author}).status_code == 204
    assert tracks_of(client) == []
    assert vote(client, track["id"], 1, new_install_key(), "198.51.100.1").json() == {"error": "NOT_FOUND"}


def test_daily_upload_quota_per_installation(client, clock):
    key = new_install_key()
    for i in range(5):
        assert upload(client, key, points=walk(start_lon=6.0 + i * 0.0003, wiggle=0.0002)).status_code == 201
    response = upload(client, key, points=walk(start_lon=6.002, wiggle=0.0002))
    assert (response.status_code, response.json()) == (429, {"error": "RATE_LIMITED"})
    clock.now += 24 * 3600 + 1
    assert upload(client, key, points=walk(start_lon=6.002, wiggle=0.0002)).status_code == 201


def test_list_supports_etag(client):
    upload(client, new_install_key())
    first = client.get(f"/v1/canyons/{CANYON_ID}/tracks")
    again = client.get(f"/v1/canyons/{CANYON_ID}/tracks", headers={"If-None-Match": first.headers["ETag"]})
    assert again.status_code == 304


def test_track_service_takedown(settings):
    service = TrackService(settings.database_path, settings.pepper, CanyonReference(settings.canyon_reference_path))
    track = service.upload(CANYON_ID, "FULL", make_gpx(walk()), new_install_key(), "203.0.113.9")
    assert service.takedown(track["id"]) is True
    assert service.takedown(track["id"]) is False
    service.close()


def test_without_trusted_proxy_the_socket_address_counts(settings, clock):
    untrusted = dataclasses.replace(settings, trusted_proxy=False)
    service = TrackService(untrusted.database_path, untrusted.pepper, CanyonReference(untrusted.canyon_reference_path), clock=clock)
    with TestClient(create_app(untrusted, service)) as client:
        track = upload(client, new_install_key()).json()
        for i in range(3):
            response = vote(client, track["id"], 1, new_install_key(), f"198.51.{i}.10")
        # Every request comes from the same test client address: one network, two votes at most.
        assert response.json()["up"] == 2
    service.close()
