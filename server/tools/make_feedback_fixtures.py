"""Builds the contract fixtures of the idea box (core/data/src/test/resources/backend) from the real
server code: a request as the app sends it (accepted by the API), the answer to it, the list of an
installation's messages after the operator answered, and a refusal naming a field.

Run from the repository root:
    server/.venv/bin/python -I server/tools/make_feedback_fixtures.py "$PWD/server" "$PWD/core/data/src/test/resources/backend"
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
from dcserver.config import Settings  # noqa: E402
from dcserver.feedback.service import FeedbackService  # noqa: E402

# The request FeedbackRepositoryImplTest builds from its draft: same fields, same order.
REQUEST = {
    "kind": "BUG",
    "text": "La carte reste blanche quand je reviens de l'onglet Sorties.",
    "technical": {"appVersion": "3.2.2", "versionCode": 30202, "android": "14 (API 34)", "device": "Google Pixel 5", "language": "fr-FR"},
    "contact": {"username": "Membre", "email": "membre@example.org"},
}


def install_key() -> str:
    return base64.urlsafe_b64encode(secrets.token_bytes(32)).decode().rstrip("=")


out_dir.mkdir(parents=True, exist_ok=True)
with tempfile.TemporaryDirectory() as tmp:
    t = Path(tmp)
    (t / "data").mkdir()
    (t / "publish").mkdir()
    settings = Settings(data_dir=t / "data", publish_dir=t / "publish", repo_dir=t, pepper=b"f" * 32)
    service = FeedbackService(settings.database_path, settings.pepper)
    key = install_key()
    headers = {"X-Install-Key": key, "X-Forwarded-For": "203.0.113.1"}
    with TestClient(create_app(settings, feedback_service=service)) as client:
        sent = client.post("/v1/feedback", json=REQUEST, headers=headers)
        assert sent.status_code == 201, sent.text
        # An idea the operator planned with a reply, a change declined without one.
        idea = client.post("/v1/feedback", json={"kind": "IDEA", "text": "Afficher la météo de toute la semaine."}, headers=headers).json()
        change = client.post("/v1/feedback", json={"kind": "CHANGE", "text": "Trier les favoris par distance."}, headers=headers).json()
        service.update(idea["id"], status="PLANNED", reply="Bonne idée, c'est prévu pour la prochaine version.")
        service.update(change["id"], status="DECLINED")
        mine = client.get("/v1/feedback/mine", headers=headers).json()
        invalid = client.post("/v1/feedback", json={**REQUEST, "text": "Trop court"[:5]}, headers=headers)
    service.close()
    (out_dir / "feedback-request.json").write_text(json.dumps(REQUEST, ensure_ascii=False, indent=1) + "\n")
    (out_dir / "feedback.json").write_text(json.dumps(sent.json(), ensure_ascii=False, indent=1) + "\n")
    (out_dir / "feedback-mine.json").write_text(json.dumps(mine, ensure_ascii=False, indent=1) + "\n")
    (out_dir / "error-feedback-invalid.json").write_text(invalid.text + "\n")
    print("feedback:", [(m["kind"], m["status"], bool(m["reply"])) for m in mine["feedback"]], "| error:", invalid.status_code, invalid.text)
