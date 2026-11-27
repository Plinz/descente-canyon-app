from __future__ import annotations

import dataclasses
import importlib.util
import json
import sqlite3
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from dcserver.admin.app import create_admin_app, host_name, nearby
from dcserver.api import create_app
from dcserver.canyons import content as sheets
from dcserver.canyons import publication
from dcserver.canyons.content import FIRST_APP_CANYON_ID, ContentError
from dcserver.canyons.service import LICENCE, CanyonService, ProposalError
from dcserver.jobs import catalog
from dcserver.ratelimit import Quota, RateLimiter
from dcserver.tracks.canyons import CanyonReference
from dcserver.tracks.service import TrackService

from .conftest import new_install_key

REPO = Path(__file__).resolve().parents[2]
TOKEN = "t" * 32


def sheet(**overrides) -> dict:
    base = {
        "nom": "Ravin des Essais",
        "nomComplet": "Ravin des Essais (ruisseau du Test)",
        "pays": "France",
        "region": "Auvergne-Rhône-Alpes",
        "departement": "Isère",
        "commune": "Saint-Test",
        "coursEau": "Ruisseau du Test",
        "cotation": "v4a2III",
        "altitudeDepart": 1100,
        "denivele": 250,
        "cascadeMax": 30,
        "cordeMin": 35,
        "tempsApproche": "45min",
        "tempsDescente": "3h",
        "tempsRetour": "20min",
        "navette": "néant",
        "approche": "Suivre le sentier.",
        "descente": "Une belle C30 en entrée,\r\n\r\n\r\npuis des toboggans.",
        "points": [
            {"type": "ENTREE", "latitude": 45.1, "longitude": 5.7},
            {"type": "SORTIE", "latitude": 45.105, "longitude": 5.71, "title": "Pont"},
        ],
    }
    base.update(overrides)
    return base


class Clock:
    def __init__(self) -> None:
        self.now = 1_790_000_000.0

    def __call__(self) -> float:
        return self.now


@pytest.fixture
def service(settings):
    clock = Clock()
    canyons = CanyonService(settings.database_path, settings.pepper, clock=clock)
    canyons.clock = clock
    yield canyons
    canyons.close()


@pytest.fixture
def client(settings, service):
    tracks = TrackService(settings.database_path, settings.pepper, CanyonReference(settings.canyon_reference_path))
    with TestClient(create_app(settings, tracks, service)) as test_client:
        yield test_client
    tracks.close()


@pytest.fixture
def admin(settings, service):
    settings = dataclasses.replace(settings, admin_token=TOKEN)
    with TestClient(create_admin_app(settings, service, CanyonReference(settings.canyon_reference_path)), base_url="http://localhost:8090") as test_client:
        test_client.headers["X-Admin-Token"] = TOKEN
        yield test_client


def propose(client, key, content=None, kind="NEW", canyon_id=None, ip="203.0.113.7", licence=LICENCE):
    body = {"kind": kind, "content": content or sheet(), "licence": licence}
    if canyon_id is not None:
        body["canyonId"] = canyon_id
    return client.post("/v1/canyon-proposals", json=body, headers={"X-Install-Key": key, "X-Forwarded-For": ip})


# The sheet

def test_a_sheet_is_kept_in_the_site_formats():
    result = sheets.normalize(sheet(cotation="V4A2III", navette="5", tempsApproche="neant", nom="  Ravin   des Essais "))
    assert result["nom"] == "Ravin des Essais"
    assert result["cotation"] == "v4a2III"
    assert result["navette"] == "5.0km"
    assert result["tempsApproche"] == "néant"
    assert result["descente"] == "Une belle C30 en entrée,\n\npuis des toboggans."
    assert result["points"][1] == {"type": "SORTIE", "latitude": 45.105, "longitude": 5.71, "title": "Pont"}
    assert list(result)[0] == "nom" and list(result)[-1] == "points"
    assert sheets.normalize(sheet(cotation="??", navette="", region="  "))  # unknown values are left out
    assert "cotation" not in sheets.normalize(sheet(cotation="??"))


@pytest.mark.parametrize(
    "overrides, code, field",
    [
        ({"nom": " "}, "REQUIRED", "nom"),
        ({"descente": "", "approche": ""}, "TEXT_REQUIRED", None),
        ({"cotation": "v9a2III"}, "BAD_FORMAT", "cotation"),
        ({"navette": "loin"}, "BAD_FORMAT", "navette"),
        ({"tempsDescente": "toute la journée"}, "TOO_LONG", "tempsDescente"),
        ({"tempsDescente": "longue"}, "BAD_FORMAT", "tempsDescente"),
        ({"cordeMin": 5000}, "OUT_OF_RANGE", "cordeMin"),
        ({"cordeMin": True}, "BAD_TYPE", "cordeMin"),
        ({"points": []}, "POINT_REQUIRED", "points"),
        ({"points": [{"type": "ENTREE", "latitude": 45.1, "longitude": 5.7}, {"type": "SORTIE", "latitude": 46.1, "longitude": 5.7}]}, "POINTS_TOO_FAR_APART", "points"),
        ({"points": [{"type": "ENTREE", "latitude": 0, "longitude": 0}]}, "BAD_COORDINATES", "points"),
        ({"points": [{"type": "TOILETTES", "latitude": 45.1, "longitude": 5.7}]}, "BAD_POINT_TYPE", "points"),
        ({"url": "https://example.org"}, "UNKNOWN_FIELD", "url"),
        ({"remarques": "x" * 20_001}, "TOO_LONG", "remarques"),
    ],
)
def test_a_sheet_that_cannot_be_kept_is_refused(overrides, code, field):
    with pytest.raises(ContentError) as error:
        sheets.normalize(sheet(**overrides))
    assert (error.value.code, error.value.field) == (code, field)


def test_a_name_one_point_and_one_text_make_a_sheet():
    result = sheets.normalize({"nom": "Ravin", "remarques": "Vu du pont.", "points": [{"type": "PARKING_AVAL", "latitude": 45.1, "longitude": 5.7}]})
    assert result == {"nom": "Ravin", "remarques": "Vu du pont.", "points": [{"type": "PARKING_AVAL", "latitude": 45.1, "longitude": 5.7}]}
    # The entry places the canyon when there is one, else the first point.
    assert sheets.anchor(result)["type"] == "PARKING_AVAL"
    assert sheets.anchor(sheets.normalize(sheet(points=[
        {"type": "SORTIE", "latitude": 45.105, "longitude": 5.71},
        {"type": "ENTREE", "latitude": 45.1, "longitude": 5.7},
    ])))["type"] == "ENTREE"


def test_control_characters_are_removed():
    result = sheets.normalize(sheet(nom="Ravin‮ des\x00 Essais"))
    assert result["nom"] == "Ravin des Essais"


# Proposals from the app

def test_a_proposal_waits_for_the_operator(client, service):
    key = new_install_key()
    response = propose(client, key)
    assert response.status_code == 201, response.text
    proposal = response.json()
    assert proposal["status"] == "PENDING" and proposal["kind"] == "NEW" and proposal["canyonId"] is None
    assert proposal["name"] == "Ravin des Essais"
    assert "author" not in proposal
    mine = client.get("/v1/canyon-proposals/mine", headers={"X-Install-Key": key}).json()["proposals"]
    assert [p["id"] for p in mine] == [proposal["id"]]
    assert client.get("/v1/canyon-proposals/mine", headers={"X-Install-Key": new_install_key()}).json() == {"proposals": []}
    # Nothing is published before the review.
    assert service.export()["canyons"] == []


def test_proposals_need_a_key_the_licence_and_a_valid_sheet(client):
    key = new_install_key()
    assert propose(client, "not-a-key").json() == {"error": "MISSING_KEY"}
    assert propose(client, key, licence=None).json() == {"error": "LICENCE_REQUIRED"}
    response = propose(client, key, content=sheet(descente="", approche=""))
    assert response.status_code == 422
    assert response.json() == {"error": "INVALID_TEXT_REQUIRED"}
    assert propose(client, key, kind="EDIT", canyon_id=21).json() == {"error": "NOT_EDITABLE"}
    assert propose(client, key, kind="EDIT", canyon_id=FIRST_APP_CANYON_ID + 5).json() == {"error": "UNKNOWN_CANYON"}
    assert client.post("/v1/canyon-proposals", content=b"{", headers={"X-Install-Key": key}).json() == {"error": "BAD_REQUEST"}


def test_the_author_withdraws_a_pending_proposal(client):
    key = new_install_key()
    proposal = propose(client, key).json()
    assert client.delete(f"/v1/canyon-proposals/{proposal['id']}", headers={"X-Install-Key": new_install_key()}).json() == {"error": "NOT_AUTHOR"}
    assert client.delete(f"/v1/canyon-proposals/{proposal['id']}", headers={"X-Install-Key": key}).status_code == 204
    assert client.delete(f"/v1/canyon-proposals/{proposal['id']}", headers={"X-Install-Key": key}).json() == {"error": "ALREADY_DECIDED"}
    mine = client.get("/v1/canyon-proposals/mine", headers={"X-Install-Key": key}).json()["proposals"]
    assert mine[0]["status"] == "WITHDRAWN"


def test_proposals_are_rate_limited(settings):
    limiter = RateLimiter({"proposal:key": Quota(2), "proposal:net": Quota(3)})
    service = CanyonService(settings.database_path, settings.pepper, limiter=limiter)
    try:
        key = new_install_key()
        body = {"kind": "NEW", "content": sheet(), "licence": LICENCE}
        service.submit(body, key, "203.0.113.7")
        service.submit(body, key, "203.0.113.7")
        with pytest.raises(ProposalError) as error:
            service.submit(body, key, "203.0.113.7")
        assert error.value.code == "RATE_LIMITED"
        service.submit(body, new_install_key(), "203.0.113.8")
        with pytest.raises(ProposalError) as error:
            service.submit(body, new_install_key(), "203.0.113.9")  # same /24 network
        assert error.value.code == "RATE_LIMITED"
    finally:
        service.close()


# Review

def test_approval_publishes_the_sheet_corrected_by_the_operator(client, service):
    key = new_install_key()
    proposal = propose(client, key).json()
    corrected = sheet(nom="Ravin des Essais Supérieurs")
    canyon = service.approve(proposal["id"], corrected, "Nom corrigé")
    assert canyon["id"] == FIRST_APP_CANYON_ID
    assert canyon["revision"] == 1 and canyon["content"]["nom"] == "Ravin des Essais Supérieurs"
    mine = client.get("/v1/canyon-proposals/mine", headers={"X-Install-Key": key}).json()["proposals"][0]
    assert mine["status"] == "APPROVED" and mine["canyonId"] == FIRST_APP_CANYON_ID and mine["note"] == "Nom corrigé"
    with pytest.raises(ProposalError) as error:
        service.approve(proposal["id"])
    assert error.value.code == "ALREADY_DECIDED"
    export = service.export()
    assert [c["id"] for c in export["canyons"]] == [FIRST_APP_CANYON_ID]
    assert export["canyons"][0]["content"]["nom"] == "Ravin des Essais Supérieurs"
    # The next canyon takes the next id.
    second = propose(client, key, content=sheet(nom="Autre ravin")).json()
    assert service.approve(second["id"])["id"] == FIRST_APP_CANYON_ID + 1


def test_the_place_comes_from_the_canyon_nearest_to_the_point(settings, tmp_path):
    path = tmp_path / "reference.json"
    path.write_text(json.dumps({"canyons": {
        "8": {"name": "Voisin", "points": [[45.11, 5.7]], "place": {"pays": "France", "region": "Auvergne-Rhône-Alpes", "departement": "Isère", "massif": "Vercors"}},
        "9": {"name": "Plus loin", "points": [[45.2, 5.7]], "place": {"pays": "France", "departement": "Drôme"}},
        "10": {"name": "Sans lieu", "points": [[45.1, 5.7]], "place": {}},
    }}))
    reference = CanyonReference(path)
    assert reference.place_near(45.1, 5.7) == {"pays": "France", "region": "Auvergne-Rhône-Alpes", "departement": "Isère", "massif": "Vercors"}
    assert reference.place_near(40.0, 5.7) is None
    service = CanyonService(settings.database_path, settings.pepper, locate=reference.place_near)
    try:
        bare = {"nom": "Ravin", "descente": "Une C20.", "points": [{"type": "PARKING_AMONT", "latitude": 45.1, "longitude": 5.7}]}
        proposal = service.submit({"kind": "NEW", "content": bare, "licence": LICENCE}, new_install_key(), "203.0.113.7")
        content = service.proposal(proposal["id"])["content"]
        assert (content["pays"], content["departement"], content["massif"]) == ("France", "Isère", "Vercors")
        assert "commune" not in content and "bassin" not in content
        # What the author wrote stays.
        assert service.create({**bare, "departement": "Savoie"})["content"]["departement"] == "Savoie"
        # Far from every canyon, the operator writes the country before publishing.
        far = {**bare, "points": [{"type": "ENTREE", "latitude": 40.0, "longitude": 5.7}]}
        proposal = service.submit({"kind": "NEW", "content": far, "licence": LICENCE}, new_install_key(), "203.0.113.8")
        with pytest.raises(ProposalError) as error:
            service.approve(proposal["id"])
        assert (error.value.code, error.value.field) == ("INVALID_REQUIRED", "pays")
        assert service.approve(proposal["id"], {**far, "pays": "Espagne"})["content"]["pays"] == "Espagne"
    finally:
        service.close()


def test_new_ids_stay_above_the_repository_copy(settings):
    service = CanyonService(settings.database_path, settings.pepper, id_floor=lambda: FIRST_APP_CANYON_ID + 41)
    try:
        assert service.create(sheet())["id"] == FIRST_APP_CANYON_ID + 42
    finally:
        service.close()


def test_edits_revisions_and_restore(client, service):
    key = new_install_key()
    canyon = service.approve(propose(client, key).json()["id"])
    canyon_id = canyon["id"]
    assert propose(client, key, content=sheet(), kind="EDIT", canyon_id=canyon_id).json() == {"error": "NO_CHANGE"}
    edit = propose(client, new_install_key(), content=sheet(cordeMin=40), kind="EDIT", canyon_id=canyon_id).json()
    detail = service.proposal(edit["id"])
    assert detail["changes"] == [{"field": "cordeMin", "before": 35, "after": 40}]
    assert detail["baseRevision"] == detail["currentRevision"] == 1
    # A second edit written on the same version, approved first.
    other = propose(client, new_install_key(), content=sheet(cascadeMax=32), kind="EDIT", canyon_id=canyon_id).json()
    service.approve(other["id"])
    detail = service.proposal(edit["id"])
    assert detail["currentRevision"] == 2
    assert {c["field"] for c in detail["diffWithCurrent"]} == {"cordeMin", "cascadeMax"}
    assert service.approve(edit["id"])["revision"] == 3
    restored = service.restore(canyon_id, 1)
    assert restored["revision"] == 4 and restored["content"] == service.revision(canyon_id, 1)["content"]
    assert [r["origin"] for r in restored["revisions"]] == ["RESTORE", "PROPOSAL", "PROPOSAL", "PROPOSAL"]
    assert restored["revisions"][0]["note"] == "Retour à la version 1"
    with pytest.raises(ProposalError) as error:
        service.edit(canyon_id, service.revision(canyon_id, 4)["content"])
    assert error.value.code == "NO_CHANGE"


def test_rejection_and_blocked_authors(client, service):
    key = new_install_key()
    first = propose(client, key).json()
    second = propose(client, key, content=sheet(nom="Encore")).json()
    service.reject(first["id"], "DUPLICATE", "Existe déjà : Ravin des Essais")
    mine = {p["id"]: p for p in client.get("/v1/canyon-proposals/mine", headers={"X-Install-Key": key}).json()["proposals"]}
    assert mine[first["id"]]["reason"] == "DUPLICATE" and mine[first["id"]]["note"] == "Existe déjà : Ravin des Essais"
    with pytest.raises(ProposalError):
        service.reject(second["id"], "BECAUSE")
    assert service.block_author(second["id"]) == 1
    assert propose(client, key).json() == {"error": "BLOCKED"}
    assert service.unblock_author(second["id"]) is True
    assert propose(client, key).status_code == 201


def test_a_hidden_canyon_leaves_the_export_and_takes_no_edit(client, service):
    canyon_id = service.approve(propose(client, new_install_key()).json()["id"])["id"]
    service.set_hidden(canyon_id, True)
    export = service.export()
    assert export["canyons"] == [] and export["hidden"] == [canyon_id]
    assert propose(client, new_install_key(), content=sheet(cordeMin=40), kind="EDIT", canyon_id=canyon_id).json() == {"error": "UNKNOWN_CANYON"}


def test_nearby_proposals_and_author_stats(client, service):
    key = new_install_key()
    first = propose(client, key).json()
    second = propose(client, new_install_key(), content=sheet(nom="Même canyon")).json()
    detail = service.proposal(second["id"])
    assert [o["id"] for o in detail["nearbyProposals"]] == [first["id"]]
    assert detail["nearbyProposals"][0]["distanceM"] == 0
    assert detail["authorStats"]["counts"] == {"PENDING": 1}


# Publication

def test_the_repository_copy_and_the_database_merge(service):
    canyon = service.create(sheet())
    hidden = service.create(sheet(nom="Masqué"))
    service.set_hidden(hidden["id"], True)
    repo = {
        "format": 1,
        "canyons": [
            {"id": canyon["id"], "revision": 0, "updatedAt": "2026-01-01T00:00:00Z", "content": sheet(nom="Ancien nom")},
            {"id": hidden["id"], "revision": 1, "updatedAt": "2026-01-01T00:00:00Z", "content": sheet(nom="Masqué")},
            {"id": FIRST_APP_CANYON_ID + 10, "revision": 1, "updatedAt": "2026-01-01T00:00:00Z", "content": sheet(nom="Seulement dans le dépôt")},
        ],
    }
    merged = publication.merged_document(service.export(), repo)
    names = {c["id"]: c["content"]["nom"] for c in merged["canyons"]}
    assert names == {canyon["id"]: "Ravin des Essais", FIRST_APP_CANYON_ID + 10: "Seulement dans le dépôt"}
    assert publication.digest(merged) == publication.digest(json.loads(publication.to_bytes(merged)))


def _generator():
    spec = importlib.util.spec_from_file_location("dc_generator", REPO / "scripts/app/generate_prepackaged_room_db.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def test_the_generator_turns_sheets_into_catalog_rows(tmp_path, service):
    canyon = service.create(sheet(isForbidden=True))
    path = tmp_path / "app-canyons.json"
    path.write_bytes(publication.to_bytes(publication.merged_document(service.export(), None)))
    rows, points = _generator().load_app_canyons(path, {21, 22})
    assert len(rows) == 1 and len(points) == 2
    row = rows[0]
    assert row["id"] == canyon["id"] and row["sourceType"] == "APP" and row["sourceKey"] == f"app:{canyon['id']}"
    assert row["url"] == "" and row["interet"] is None and row["nbVotes"] == 0
    assert row["communes"] == ["Saint-Test"] and row["isForbidden"] is True
    assert row["nomComplet"] == "Ravin des Essais (ruisseau du Test)" and row["cotation"] == "v4a2III"
    assert points[1] == {"canyonId": canyon["id"], "type": "SORTIE", "latitude": 45.105, "longitude": 5.71, "label": "Pont"}
    with pytest.raises(RuntimeError):
        _generator().load_app_canyons(path, {canyon["id"]})


# Sheets taken from another database

SOURCE = {
    "name": "OpenCanyon",
    "url": "https://www.opencanyon.org/fr/canyon/0e1f",
    "licence": "CC-BY-NC-SA-4.0",
    "authors": ["Une personne", "Une autre"],
    "changes": "Traduit de l'allemand, recoupé avec CWiki",
}
SHARE_ALIKE = "CC-BY-NC-SA-4.0"


def test_a_sheet_from_a_share_alike_database_carries_its_licence_and_sources():
    result = sheets.normalize(sheet(licence=SHARE_ALIKE, sources=[SOURCE]))
    assert result["licence"] == SHARE_ALIKE and result["sources"] == [SOURCE]
    assert list(result)[-2:] == ["licence", "sources"]
    # CC0 is the default and is left out of the sheet.
    assert "licence" not in sheets.normalize(sheet(licence="CC0-1.0"))
    with pytest.raises(ContentError) as error:
        sheets.normalize(sheet(licence=SHARE_ALIKE))
    assert error.value.code == "SOURCE_REQUIRED"
    with pytest.raises(ContentError) as error:
        sheets.normalize(sheet(sources=[SOURCE]))  # a share-alike source cannot be given to the public domain
    assert error.value.code == "BAD_LICENCE"
    assert sheets.normalize(sheet(sources=[{**SOURCE, "licence": "CC-BY-4.0"}]))["sources"][0]["licence"] == "CC-BY-4.0"
    for bad in ({**SOURCE, "url": "http://example.org"}, {**SOURCE, "licence": "CC-BY-ND-4.0"}, {**SOURCE, "extra": 1}, {"name": "X"}):
        with pytest.raises(ContentError) as error:
            sheets.normalize(sheet(licence=SHARE_ALIKE, sources=[bad]))
        assert error.value.code == "BAD_SOURCES"
    with pytest.raises(ContentError):
        sheets.normalize(sheet(licence="CC-BY-ND-4.0", sources=[SOURCE]))


def test_a_proposal_declares_the_licence_of_its_sheet(client, service):
    key = new_install_key()
    content = sheet(sources=[SOURCE])
    response = propose(client, key, content=content, licence=SHARE_ALIKE)
    assert response.status_code == 201, response.text
    assert service.proposal(response.json()["id"])["content"]["licence"] == SHARE_ALIKE
    assert propose(client, key, content=content).json() == {"error": "INVALID_BAD_LICENCE", "field": "licence"}
    assert propose(client, key, content=sheet(licence=SHARE_ALIKE, sources=[SOURCE])).json() == {"error": "LICENCE_MISMATCH"}
    assert propose(client, key, licence="CC-BY-SA-4.0").json() == {"error": "LICENCE_REQUIRED"}


def test_changes_never_drop_the_licence_and_the_sources(client, service, admin):
    key = new_install_key()
    proposal = propose(client, key, content=sheet(sources=[SOURCE]), licence=SHARE_ALIKE).json()
    # The review tool sends the sheet without them.
    canyon = service.approve(proposal["id"], sheet(nom="Ravin corrigé"))
    assert canyon["content"]["licence"] == SHARE_ALIKE and canyon["content"]["sources"] == [SOURCE]
    # The app neither.
    edit = propose(client, new_install_key(), content=sheet(nom="Ravin corrigé", cordeMin=40), kind="EDIT", canyon_id=canyon["id"]).json()
    detail = service.proposal(edit["id"])
    assert detail["content"]["sources"] == [SOURCE]
    assert detail["changes"] == [{"field": "cordeMin", "before": 35, "after": 40}]
    assert service.approve(edit["id"])["content"]["licence"] == SHARE_ALIKE
    edited = service.edit(canyon["id"], sheet(nom="Ravin corrigé", cordeMin=45))
    assert edited["content"]["sources"] == [SOURCE]
    assert service.export()["canyons"][0]["content"]["sources"] == [SOURCE]


def test_the_generator_credits_the_sources(tmp_path, service):
    canyon = service.create(sheet(licence=SHARE_ALIKE, sources=[SOURCE, {"name": "CWiki", "url": "https://canyon.carto.net/x", "licence": "CC-BY-NC-SA-2.0-AT"}]))
    service.create(sheet(nom="Sans source"))
    path = tmp_path / "app-canyons.json"
    path.write_bytes(publication.to_bytes(publication.merged_document(service.export(), None)))
    entries, links = _generator().load_app_canyon_sources(path)
    assert links == [
        {"canyonId": canyon["id"], "bibliographyId": f"app_source_{canyon['id']}_0"},
        {"canyonId": canyon["id"], "bibliographyId": f"app_source_{canyon['id']}_1"},
    ]
    first, second = entries
    assert first["kind"] == "RESOURCE" and first["resourceType"] == "WEBSITE" and first["url"] == SOURCE["url"]
    assert first["title"] == "Source : OpenCanyon" and first["authors"] == SOURCE["authors"]
    assert first["status"] == "Traduit de l'allemand, recoupé avec CWiki. Licence de la source : CC BY-NC-SA 4.0. Cette fiche : CC BY-NC-SA 4.0."
    assert second["authors"] is None and "CC BY-NC-SA 2.0 AT" in second["status"]


# Review tool

def test_the_review_tool_needs_its_token_and_a_local_host(settings, service):
    reference = CanyonReference(settings.canyon_reference_path)
    with TestClient(create_admin_app(settings, service, reference), base_url="http://localhost:8090") as test_client:
        assert test_client.get("/api/status").json() == {"error": "ADMIN_TOKEN_NOT_SET"}
    settings = dataclasses.replace(settings, admin_token=TOKEN)
    with TestClient(create_admin_app(settings, service, reference), base_url="http://localhost:8090") as test_client:
        assert test_client.get("/api/status").status_code == 401
        assert test_client.get("/api/status", headers={"X-Admin-Token": "x" * 32}).status_code == 401
        page = test_client.get("/")
        assert page.status_code == 200 and "Relecture des canyons" in page.text
        assert "frame-ancestors 'none'" in page.headers["content-security-policy"]
    with TestClient(create_admin_app(settings, service, reference), base_url="http://evil.example") as test_client:
        assert test_client.get("/", headers={"X-Admin-Token": TOKEN}).status_code == 421
    assert host_name("[::1]:8090") == "::1" and host_name("localhost:8090") == "localhost" and host_name("127.0.0.1") == "127.0.0.1"


def test_review_through_the_tool(client, admin):
    proposal = propose(client, new_install_key(), content=sheet(points=[{"type": "ENTREE", "latitude": 45.0005, "longitude": 6.0}])).json()
    status = admin.get("/api/status").json()
    # A pending proposal is not published: nothing waits for the catalog yet.
    assert status["pendingProposals"] == 1 and status["publicationUpToDate"] is True
    listed = admin.get("/api/proposals").json()
    assert [p["id"] for p in listed] == [proposal["id"]]
    detail = admin.get(f"/api/proposals/{proposal['id']}").json()
    # The test reference holds canyon 21 at 45.0, 6.0.
    assert detail["nearby"][0]["id"] == 21 and detail["nearby"][0]["distanceM"] == 56
    bad = admin.post(f"/api/proposals/{proposal['id']}/approve", json={"content": {**detail["content"], "cotation": "x"}})
    assert bad.status_code == 422 and bad.json() == {"error": "INVALID_BAD_FORMAT", "field": "cotation"}
    approved = admin.post(f"/api/proposals/{proposal['id']}/approve", json={"note": "Merci"}).json()
    canyon_id = approved["id"]
    assert admin.get("/api/status").json()["publicationUpToDate"] is False
    assert admin.get("/api/proposals?status=DECIDED").json()[0]["status"] == "APPROVED"
    edited = admin.put(f"/api/canyons/{canyon_id}", json={"content": {**approved["content"], "cordeMin": 50}, "note": "corde"}).json()
    assert edited["revision"] == 2
    assert admin.get(f"/api/canyons/{canyon_id}/revisions/1").json()["diffWithCurrent"] == [{"field": "cordeMin", "before": 50, "after": 35}]
    assert admin.post(f"/api/canyons/{canyon_id}/restore", json={"revision": 1}).json()["revision"] == 3
    assert admin.post(f"/api/canyons/{canyon_id}/hidden", json={"hidden": True}).json()["status"] == "HIDDEN"
    assert admin.get("/api/canyons").json()[0]["status"] == "HIDDEN"
    created = admin.post("/api/canyons", json={"content": sheet(nom="Ajouté par l'admin")})
    assert created.status_code == 201 and created.json()["revisions"][0]["origin"] == "ADMIN"
    around = admin.get("/api/nearby", params={"lat": 45.0, "lon": 6.0, "radius": 2000}).json()
    assert [c["id"] for c in around] == [21]


def test_nearby_flags_forbidden_and_app_canyons(tmp_path):
    path = tmp_path / "reference.json"
    path.write_text(json.dumps({"canyons": {
        "5": {"name": "Interdit", "points": [[45.0, 6.0]], "forbidden": True, "app": False},
        "1000000": {"name": "Ajouté", "points": [[45.01, 6.0]], "forbidden": False, "app": True},
        "7": {"name": "Loin", "points": [[46.0, 6.0]]},
    }}))
    found = nearby(CanyonReference(path), [{"latitude": 45.0, "longitude": 6.0}])
    assert [(c["id"], c["forbidden"], c["app"]) for c in found] == [(5, True, False), (1000000, False, True)]


@pytest.mark.slow
@pytest.mark.skipif(not (REPO / catalog.REPO_CANYONS).exists(), reason="needs the repository data")
def test_approved_canyons_reach_the_catalog(settings, service):
    settings = dataclasses.replace(settings, repo_dir=REPO)
    canyon_id = service.create(sheet())["id"]

    report = catalog.run_app_canyons(settings)
    assert report["published"] is True and report["appCanyons"] == 1, report
    connection = sqlite3.connect(settings.data_dir / "catalog" / "current.db")
    row = connection.execute("SELECT nom, sourceType, sourceKey, url, cotation, navette FROM canyons WHERE id = ?", (canyon_id,)).fetchone()
    assert row == ("Ravin des Essais", "APP", f"app:{canyon_id}", "", "v4a2III", "néant")
    assert connection.execute("SELECT COUNT(*) FROM geo_points WHERE canyonId = ?", (canyon_id,)).fetchone()[0] == 2
    assert connection.execute("SELECT COUNT(*) FROM search_index WHERE id = ?", (canyon_id,)).fetchone()[0] == 1
    connection.close()
    reference = json.loads(settings.canyon_reference_path.read_text())
    assert reference["canyons"][str(canyon_id)]["app"] is True
    assert reference["canyons"][str(canyon_id)]["place"]["departement"] == "Isère"
    state = json.loads((settings.data_dir / "catalog" / "state.json").read_text())
    assert str(canyon_id) in state["bundleHashes"]

    # Nothing changed: nothing is built.
    assert catalog.run_app_canyons(settings)["unchanged"] is True

    # Hidden: the canyon leaves the catalog and its bundle leaves the index.
    service.set_hidden(canyon_id, True)
    report = catalog.run_app_canyons(settings)
    assert report["published"] is True and report["catalogChanges"] == [canyon_id]
    state = json.loads((settings.data_dir / "catalog" / "state.json").read_text())
    assert str(canyon_id) not in state["bundleHashes"]
