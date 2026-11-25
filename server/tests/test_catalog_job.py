from __future__ import annotations

import dataclasses
import gzip
import json
import sqlite3
from datetime import date
from pathlib import Path

import pytest

from dcserver import manifest
from dcserver.jobs import catalog
from dcserver.jobs.catalog import JobAborted, ScrapeResult
from dcserver.publish import sha256_file

REPO = Path(__file__).resolve().parents[2]


def test_rotation_covers_every_canyon_once_per_period():
    ids = list(range(100, 140))
    start = date(2026, 11, 25).toordinal()
    slices = [catalog.rotation_slice(ids, date.fromordinal(start + d), 7) for d in range(7)]
    flat = [canyon_id for part in slices for canyon_id in part]
    assert sorted(flat) == ids
    assert len(set(flat)) == len(flat)


def test_scrape_checks():
    ok = ScrapeResult(scraped=list(range(97)), changed=[], format_failures=[1, 2, 3], network_failures=[])
    catalog.check_scrape(ok, 100)
    with pytest.raises(JobAborted) as error:
        catalog.check_scrape(dataclasses.replace(ok, format_failures=[1, 2, 3, 4, 5, 6]), 100)
    assert error.value.reason == "SITE_FORMAT_CHANGED"
    with pytest.raises(JobAborted) as error:
        catalog.check_scrape(dataclasses.replace(ok, format_failures=[], network_failures=[(i, "x") for i in range(31)]), 100)
    assert error.value.reason == "SITE_UNREACHABLE"


def test_a_shrinking_catalog_is_not_published():
    previous = {"counts": {"canyons": 4127, "geo_points": 11198, "regulation_texts": 566}}
    catalog.check_against_previous({"counts": {"canyons": 4100, "geo_points": 11000, "regulation_texts": 560}}, previous)
    with pytest.raises(JobAborted) as error:
        catalog.check_against_previous({"counts": {"canyons": 3900, "geo_points": 11000, "regulation_texts": 560}}, previous)
    assert error.value.reason == "CATALOG_SHRANK"


def _tiny_db(path: Path, name: str, last_updated: int) -> None:
    connection = sqlite3.connect(path)
    connection.execute("CREATE TABLE canyons (id INTEGER PRIMARY KEY, nom TEXT, lastUpdated INTEGER)")
    connection.execute("CREATE TABLE geo_points (id INTEGER PRIMARY KEY, canyonId INTEGER, latitude REAL)")
    connection.execute("INSERT INTO canyons VALUES (21, ?, ?)", (name, last_updated))
    connection.execute("INSERT INTO geo_points VALUES (1, 21, 45.0)")
    connection.commit()
    connection.close()


def test_fingerprint_ignores_reading_times_only(tmp_path):
    _tiny_db(tmp_path / "a.db", "Mouna", 1)
    _tiny_db(tmp_path / "b.db", "Mouna", 2)
    _tiny_db(tmp_path / "c.db", "Mouna et Pellas", 1)
    a, per_a = catalog.content_fingerprints(tmp_path / "a.db")
    b, _ = catalog.content_fingerprints(tmp_path / "b.db")
    c, per_c = catalog.content_fingerprints(tmp_path / "c.db")
    assert a == b != c
    assert per_a[21] != per_c[21]


def _fake_scraper(changes: dict[int, str], blank: set[int] = frozenset()):
    def loader(repo: Path, user_agent: str, delay_s: float):
        module = catalog.load_scraper(repo, user_agent, delay_s)

        def scrape_canyon(canyon_id: int) -> dict:
            document = json.loads((repo / catalog.REPO_CANYONS / f"{canyon_id}.json").read_text(encoding="utf-8"))
            document["scrapedAt"] = "2026-11-25T02:00:00+00:00"
            if canyon_id in changes:
                document["canyon"]["topo"]["remarques"] = changes[canyon_id]
            if canyon_id in blank:
                document["canyon"]["identity"]["nom"] = ""
            return document

        module.scrape_canyon = scrape_canyon
        return module

    return loader


@pytest.mark.slow
@pytest.mark.skipif(not (REPO / catalog.REPO_CANYONS).exists(), reason="needs the repository data")
def test_end_to_end_on_the_repository_data(settings, public_key):
    settings = dataclasses.replace(settings, repo_dir=REPO)
    remark = "Remarque ajoutée par le test du serveur."

    first = catalog.run(settings, ids=[21, 24212], scraper_loader=_fake_scraper({21: remark}))
    assert first["published"] is True, first
    assert first["changedCanyons"] == [21]
    payload = manifest.read_published_payload(settings.v1_dir, public_key)
    entry = payload["catalogs"][0]
    assert entry["schemaVersion"] == 4 and entry["canyonCount"] == first["counts"]["canyons"] >= 4000
    packed = settings.v1_dir / entry["path"]
    assert sha256_file(packed) == entry["sha256"]
    db_path = settings.data_dir / "check.db"
    db_path.write_bytes(gzip.decompress(packed.read_bytes()))
    assert db_path.stat().st_size == entry["uncompressedSize"]
    connection = sqlite3.connect(db_path)
    remarks, last_updated = connection.execute("SELECT remarques, lastUpdated FROM canyons WHERE id = 21").fetchone()
    identity = connection.execute("SELECT identity_hash FROM room_master_table").fetchone()[0]
    connection.close()
    assert remarks == remark
    assert last_updated == 1795572000000  # 2026-11-25T02:00:00Z, the reading time of the sheet
    assert identity == entry["identityHash"]
    reference = json.loads(settings.canyon_reference_path.read_text())
    assert len(reference["canyons"]) == entry["canyonCount"]

    second = catalog.run(settings, ids=[21, 24212], scraper_loader=_fake_scraper({21: remark}))
    assert second.get("unchanged") is True and second["published"] is False
    assert manifest.read_published_payload(settings.v1_dir, public_key)["sequence"] == payload["sequence"]

    broken = catalog.run(settings, ids=[21, 24212, 22, 23], scraper_loader=_fake_scraper({}, blank={21, 24212, 22, 23}))
    assert broken["aborted"] == "SITE_FORMAT_CHANGED"
    assert manifest.read_published_payload(settings.v1_dir, public_key)["sequence"] == payload["sequence"]
