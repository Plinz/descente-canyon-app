"""Builds the contract fixtures of the shared links (core/data/src/test/resources/backend) from the
real server code: an outing as the app encodes it (accepted by the API), the answer to it, the link
read back, and a link that does not exist.

Run from the repository root:
    server/.venv/bin/python -I server/tools/make_share_fixtures.py "$PWD/server" "$PWD/core/data/src/test/resources/backend"
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
from dcserver.shares.service import ShareService  # noqa: E402
from dcserver.tracks.canyons import CanyonReference  # noqa: E402

# The outing ShareRepositoryImplTest encodes with ShareCodec: same fields.
REQUEST = {
    "kind": "OUTING",
    "payload": {
        "v": 1,
        "canyonId": 2224,
        "next": [{"canyonId": 2225, "link": "ON_FOOT", "adjustments": {"APPROACH": 15}}],
        "date": "2027-07-03",
        "anchorTime": "08:30",
        "meetAtPlace": True,
        "place": {"name": "Camping des Gorges", "lat": 43.9, "lon": 6.9},
        "groupSize": 6,
        "groupLevel": "WITH_BEGINNERS",
        "withShuttle": True,
        "adjustments": {"DESCENT": 30},
        "timing": {
            "prep": 30, "packUp": 20, "shuttleHandling": 10, "medium": 1.2, "large": 1.3, "huge": 1.5,
            "beginners": 1.3, "discovery": 1.6, "beginnersPrep": 1.5, "earlier": 10, "later": 25, "pace": 1.1,
        },
        "note": "Combinaisons 5 mm",
        "summary": {"names": ["Riolan", "Aiglun"], "meet": "08:30", "walk": "09:05", "back": "16:40", "backLatest": "17:30"},
    },
}


def install_key() -> str:
    return base64.urlsafe_b64encode(secrets.token_bytes(32)).decode().rstrip("=")


out_dir.mkdir(parents=True, exist_ok=True)
with tempfile.TemporaryDirectory() as tmp:
    t = Path(tmp)
    (t / "data").mkdir()
    (t / "publish").mkdir()
    reference = {"canyons": {"2224": {"name": "Riolan", "points": [[43.9, 6.9]]}, "2225": {"name": "Aiglun", "points": [[43.85, 6.92]]}}}
    (t / "data" / "canyon-reference.json").write_text(json.dumps(reference))
    settings = Settings(data_dir=t / "data", publish_dir=t / "publish", repo_dir=t, pepper=b"s" * 32)
    service = ShareService(settings.database_path, settings.pepper, CanyonReference(settings.canyon_reference_path).exists, settings.public_url)
    headers = {"X-Install-Key": install_key(), "X-Forwarded-For": "203.0.113.1"}
    with TestClient(create_app(settings, share_service=service)) as client:
        created = client.post("/v1/shares", json=REQUEST, headers=headers)
        assert created.status_code == 201, created.text
        read = client.get(f"/v1/shares/{created.json()['code']}")
        missing = client.get("/v1/shares/zzzzzzzzz")
    service.close()
    (out_dir / "share-request.json").write_text(json.dumps(REQUEST, ensure_ascii=False, indent=1) + "\n")
    (out_dir / "share.json").write_text(json.dumps(created.json(), ensure_ascii=False, indent=1) + "\n")
    (out_dir / "share-read.json").write_text(json.dumps(read.json(), ensure_ascii=False, indent=1) + "\n")
    (out_dir / "error-share-not-found.json").write_text(missing.text + "\n")
    print("share:", created.json()["code"], "| read version", read.json()["version"], "| missing:", missing.status_code, missing.text)
