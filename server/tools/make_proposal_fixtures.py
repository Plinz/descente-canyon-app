"""Builds the contract fixtures of the proposed canyons (core/data/src/test/resources/backend) from
the real server code: a request as the app sends it (accepted by the API), the answer to it, the
list of an installation's proposals after a review, and a refusal naming a field.

Run from the repository root:
    server/.venv/bin/python -I server/tools/make_proposal_fixtures.py "$PWD/server" "$PWD/core/data/src/test/resources/backend"
"""
import base64
import json
import secrets
import sys
import tempfile
from pathlib import Path

server_dir, out_dir = Path(sys.argv[1]), Path(sys.argv[2])
sys.path.insert(0, str(server_dir))
from fastapi.testclient import TestClient  # noqa: E402

from dcserver.api import create_app  # noqa: E402
from dcserver.canyons.service import CanyonService  # noqa: E402
from dcserver.config import Settings  # noqa: E402

# The request CanyonProposalRepositoryImplTest builds from its sheet: same fields, same order.
REQUEST = {
    "kind": "NEW",
    "content": {
        "nom": "Ravin des Essais",
        "pays": "France",
        "departement": "Isère",
        "cotation": "v4a2III",
        "cordeMin": 35,
        "tempsApproche": "45min",
        "navette": "néant",
        "descente": "Une C30 en entrée, puis des toboggans.",
        "isForbidden": True,
        "points": [
            {"type": "ENTREE", "latitude": 45.1, "longitude": 5.7},
            {"type": "SORTIE", "latitude": 45.105, "longitude": 5.71, "title": "Pont"},
        ],
    },
    "licence": "CC0-1.0",
}


def install_key() -> str:
    return base64.urlsafe_b64encode(secrets.token_bytes(32)).decode().rstrip("=")


out_dir.mkdir(parents=True, exist_ok=True)
with tempfile.TemporaryDirectory() as tmp:
    t = Path(tmp)
    (t / "data").mkdir()
    (t / "publish").mkdir()
    settings = Settings(data_dir=t / "data", publish_dir=t / "publish", repo_dir=t, pepper=b"f" * 32)
    service = CanyonService(settings.database_path, settings.pepper)
    key = install_key()
    headers = {"X-Install-Key": key, "X-Forwarded-For": "203.0.113.1"}
    with TestClient(create_app(settings, canyon_service=service)) as client:
        sent = client.post("/v1/canyon-proposals", json=REQUEST, headers=headers)
        assert sent.status_code == 201, sent.text
        # A second proposal published by the operator, a third one declined with a note.
        approved = client.post("/v1/canyon-proposals", json={**REQUEST, "content": {**REQUEST["content"], "nom": "Clue du Test"}}, headers=headers).json()
        declined = client.post("/v1/canyon-proposals", json={**REQUEST, "content": {**REQUEST["content"], "nom": "Ravin en double"}}, headers=headers).json()
        canyon = service.approve(approved["id"], note=None)
        service.reject(declined["id"], "DUPLICATE", "Déjà ajouté : Ravin des Essais")
        edit = client.post(
            "/v1/canyon-proposals",
            json={**REQUEST, "kind": "EDIT", "canyonId": canyon["id"], "content": {**canyon["content"], "cordeMin": 40}},
            headers=headers,
        )
        assert edit.status_code == 201, edit.text
        mine = client.get("/v1/canyon-proposals/mine", headers=headers).json()
        invalid = client.post("/v1/canyon-proposals", json={**REQUEST, "content": {**REQUEST["content"], "nom": " "}}, headers=headers)
    service.close()
    (out_dir / "proposal-request.json").write_text(json.dumps(REQUEST, ensure_ascii=False, indent=1) + "\n")
    (out_dir / "proposal.json").write_text(json.dumps(sent.json(), ensure_ascii=False, indent=1) + "\n")
    (out_dir / "proposals-mine.json").write_text(json.dumps(mine, ensure_ascii=False, indent=1) + "\n")
    (out_dir / "error-proposal-invalid.json").write_text(invalid.text + "\n")
    print("proposals:", [(p["kind"], p["status"], p["reason"]) for p in mine["proposals"]], "| error:", invalid.status_code, invalid.text)
