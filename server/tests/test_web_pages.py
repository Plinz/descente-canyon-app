from __future__ import annotations

import dataclasses
import json

import pytest
from fastapi.testclient import TestClient

from dcserver.api import create_app
from dcserver.config import RELEASE_CERT_SHA256, ConfigError, load_settings
from dcserver.web import links
from dcserver.web.i18n import negotiate

from .conftest import CANYON_ID

ANDROID = "Mozilla/5.0 (Linux; Android 14; Pixel 5) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36"
DESKTOP = "Mozilla/5.0 (X11; Linux x86_64) Gecko/20100101 Firefox/133.0"
SITE_CANYON_ID = 2895


@pytest.fixture
def reference_settings(settings):
    """A reference written by the catalog job of this version: rating, commune, sheet and place."""
    reference = json.loads(settings.canyon_reference_path.read_text(encoding="utf-8"))
    reference["canyons"][str(SITE_CANYON_ID)] = {
        "name": "Riolan (Clue du)",
        "points": [[43.91234, 6.91234]],
        "forbidden": False,
        "app": False,
        "place": {"pays": "France", "region": "Provence-Alpes-Côte d'Azur", "departement": "Alpes-Maritimes"},
        "cotation": "v4a4III",
        "commune": "Sigale",
        "url": "https://www.descente-canyon.com/canyoning/canyon/2895/riolan.html",
    }
    reference["canyons"]["31"] = {
        "name": "<script>alert(1)</script>",
        "points": [],
        "forbidden": True,
        "app": True,
        "place": {"pays": "France"},
        "cotation": "",
        "commune": "",
        "url": "",
    }
    settings.canyon_reference_path.write_text(json.dumps(reference), encoding="utf-8")
    return settings


@pytest.fixture
def client(reference_settings):
    with TestClient(create_app(reference_settings)) as test_client:
        yield test_client


def test_canyon_page_says_what_the_canyon_is_and_opens_the_app(client):
    response = client.get(f"/c/{SITE_CANYON_ID}-riolan", headers={"User-Agent": ANDROID, "Accept-Language": "fr-FR,fr;q=0.9"})
    assert response.status_code == 200
    page = response.text
    assert "<h1>Riolan (Clue du)</h1>" in page
    assert "v4a4III" in page
    assert "Sigale · Alpes-Maritimes · France" in page
    assert 'property="og:title" content="Riolan (Clue du) (v4a4III)"' in page
    assert f'rel="canonical" href="https://canyon.aduquennoy.fr/c/{SITE_CANYON_ID}-riolan-clue-du"' in page
    # Android: the intent link opens the app, the Play Store when it is missing, with the link as referrer.
    assert f"intent://canyon.aduquennoy.fr/c/{SITE_CANYON_ID}-riolan#Intent;scheme=https;package=fr.descentecanyon.app;" in page
    assert "referrer=dc_link%3Dhttps%253A%252F%252Fcanyon.aduquennoy.fr%252Fc%252F2895-riolan" in page
    assert "https://www.descente-canyon.com/canyoning/canyon/2895/riolan.html" in page
    assert "Ouvrir dans l&#x27;application" in page
    assert response.headers["x-robots-tag"] == "noindex, nofollow"
    assert "default-src 'none'" in response.headers["content-security-policy"]
    assert response.headers["cache-control"] == "public, max-age=3600"


def test_canyon_page_in_the_browser_language_and_without_intent_off_android(client):
    page = client.get(f"/c/{SITE_CANYON_ID}", headers={"User-Agent": DESKTOP, "Accept-Language": "de-DE,de;q=0.9,en;q=0.5"}).text
    assert '<html lang="de">' in page
    assert "App installieren" in page
    assert "intent://" not in page
    assert "Vollständige Beschreibung auf descente-canyon.com" in page


def test_sections_and_point_of_a_canyon(client):
    assert client.get(f"/c/{SITE_CANYON_ID}/debits").status_code == 200
    page = client.get(f"/c/{SITE_CANYON_ID}/carte?lat=43.9&lon=6.9", headers={"Accept-Language": "en"}).text
    assert "GPS point: 43.90000, 6.90000" in page
    assert "mlat=43.90000&amp;mlon=6.90000" in page
    assert client.get(f"/c/{SITE_CANYON_ID}/elsewhere").status_code == 404
    assert client.get("/c/not-a-canyon").status_code == 404


def test_unknown_canyon_and_escaped_values(client):
    unknown = client.get("/c/999999")
    assert unknown.status_code == 404
    assert "pas, ou plus, dans le catalogue" in unknown.text
    assert unknown.headers["cache-control"] == "no-store"
    page = client.get("/c/31").text
    assert "<script>alert(1)</script>" not in page
    assert "&lt;script&gt;alert(1)&lt;/script&gt;" in page
    assert "Descente interdite" in page
    # A canyon added in the app has no sheet on the site.
    assert "descente-canyon.com/canyoning" not in page


def test_canyon_without_new_reference_fields_still_shows(client):
    page = client.get(f"/c/{CANYON_ID}").text
    assert "<h1>Test</h1>" in page


def test_landing_and_static_files(client):
    page = client.get("/", headers={"User-Agent": ANDROID}).text
    assert "Installer l&#x27;application" in page
    assert "intent://" not in page
    assert client.get("/static/site.css").status_code == 200
    assert client.get("/static/icon-512.png").headers["content-type"] == "image/png"


def test_asset_links(reference_settings):
    with TestClient(create_app(reference_settings)) as client:
        statements = client.get("/.well-known/assetlinks.json").json()
    assert statements == [
        {
            "relation": ["delegate_permission/common.handle_all_urls"],
            "target": {"namespace": "android_app", "package_name": "fr.descentecanyon.app", "sha256_cert_fingerprints": [RELEASE_CERT_SHA256]},
        }
    ]
    debug = dataclasses.replace(reference_settings, android_debug_cert_sha256=("AB:" * 31 + "CD",))
    with TestClient(create_app(debug)) as client:
        statements = client.get("/.well-known/assetlinks.json").json()
    assert [s["target"]["package_name"] for s in statements] == ["fr.descentecanyon.app", "fr.descentecanyon.app.debug"]


def test_settings_of_the_links():
    base = {"DC_PEPPER": "ab" * 32}
    assert load_settings(base).public_url == "https://canyon.aduquennoy.fr"
    assert load_settings({**base, "DC_DOMAIN": "liens.example.org"}).public_url == "https://liens.example.org"
    assert load_settings({**base, "DC_PUBLIC_URL": "https://a.example/"}).public_url == "https://a.example"
    with pytest.raises(ConfigError):
        load_settings({**base, "DC_PUBLIC_URL": "http://a.example"})
    play = "1f" * 32
    settings = load_settings({**base, "DC_ANDROID_CERT_SHA256": f"{RELEASE_CERT_SHA256}, {play}"})
    assert settings.android_cert_sha256 == (RELEASE_CERT_SHA256, ":".join(["1F"] * 32))
    with pytest.raises(ConfigError):
        load_settings({**base, "DC_ANDROID_CERT_SHA256": "nope"})


def test_link_helpers():
    assert links.slug("Gorges du Riolan (aval)") == "gorges-du-riolan-aval"
    assert links.slug("Ça Écœure") == "ca-ecoeure"
    assert links.canyon_path(12, "") == "/c/12"
    assert links.parse_canyon_ref("2895-riolan") == 2895
    assert links.parse_canyon_ref("2895") == 2895
    assert links.parse_canyon_ref("riolan") is None
    assert negotiate("es-ES,es;q=0.9") == "es"
    assert negotiate("nl-NL,en;q=0.4,it;q=0.8") == "it"
    assert negotiate(None) == "fr"
    assert negotiate("zh-CN") == "fr"
