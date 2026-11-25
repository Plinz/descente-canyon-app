"""Builds the contract fixtures of the app tests (core/data/src/test/resources/backend) from the
real server code: a manifest signed with a throwaway key and a track list served by the API.

Run from the repository root: server/.venv/bin/python -I server/tools/make_app_fixtures.py "$PWD/server" "$PWD/core/data/src/test/resources/backend"
"""
import base64, json, secrets, sys, tempfile
from pathlib import Path

server_dir, out_dir = Path(sys.argv[1]), Path(sys.argv[2])
sys.path.insert(0, str(server_dir))
from fastapi.testclient import TestClient
from dcserver import manifest, signing
from dcserver.api import create_app
from dcserver.config import Settings
from dcserver.tracks.canyons import CanyonReference
from dcserver.tracks.service import TrackService

out_dir.mkdir(parents=True, exist_ok=True)
with tempfile.TemporaryDirectory() as tmp:
    t = Path(tmp)
    (t / "data").mkdir(); (t / "publish").mkdir()
    key_path = t / "key.pem"
    key = signing.write_private_key(key_path)
    public = signing.public_key_b64(key)
    s = Settings(data_dir=t / "data", publish_dir=t / "publish", repo_dir=t, pepper=b"f" * 32, signing_key_path=key_path)
    state = manifest.default_state()
    state["minVersionCode"] = 50
    state["features"]["communityTracks"] = False
    state["messages"] = [
        {"id": "site-2026-12", "level": "warning", "minVersionCode": 0, "maxVersionCode": None, "until": None,
         "text": {"fr": "Le site est en maintenance ce soir.", "en": "The site is under maintenance tonight."}},
    ]
    state["catalogs"] = [{
        "schemaVersion": 4, "identityHash": "38ceb224a4367c7742d73e390e93467c", "dataVersion": "2026-11-25T02:41:07Z",
        "path": "files/" + "ab" * 32 + ".db.gz", "sha256": "ab" * 32, "size": 7219514, "uncompressedSize": 38649856, "canyonCount": 4127,
    }]
    manifest.publish(s, state)
    (out_dir / "manifest-envelope.json").write_text((s.v1_dir / "manifest.json").read_text())
    (out_dir / "manifest-public-key.txt").write_text(public + "\n")

    # A track list as the API serves it: one endorsed reference approach, one fresh return.
    reference = {"canyons": {"21": {"name": "Mouna", "points": [[44.868481, 5.5616]]}}}
    (s.data_dir / "canyon-reference.json").write_text(json.dumps(reference))
    service = TrackService(s.database_path, s.pepper, CanyonReference(s.canyon_reference_path))
    client = TestClient(create_app(s, service))
    def key_(): return base64.urlsafe_b64encode(secrets.token_bytes(32)).decode().rstrip("=")
    def gpx(lon0, n=30, zig=0.00025, time=True):
        pts = "".join(
            f'<trkpt lat="{44.868481 + i * 0.0004:.6f}" lon="{lon0 + i * 0.0003 + (zig if i % 2 else 0):.6f}"><ele>{700 + i * 6}</ele>'
            + ("<time>2026-06-14T08:00:00Z</time>" if time and i == 0 else "") + "</trkpt>"
            for i in range(n))
        return f'<?xml version="1.0"?><gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1"><trk><trkseg>{pts}</trkseg></trk></gpx>'.encode()
    with client:
        a = client.post("/v1/canyons/21/tracks", params={"role": "APPROACH"}, content=gpx(5.5616), headers={"X-Install-Key": key_(), "X-Forwarded-For": "203.0.113.1"}).json()
        b = client.post("/v1/canyons/21/tracks", params={"role": "RETURN"}, content=gpx(5.5620, n=12, zig=0.0, time=False), headers={"X-Install-Key": key_(), "X-Forwarded-For": "203.0.113.2"}).json()
        for i in range(3):
            client.put(f"/v1/tracks/{a['id']}/vote", json={"value": 1}, headers={"X-Install-Key": key_(), "X-Forwarded-For": f"198.51.{i}.9"})
        client.put(f"/v1/tracks/{b['id']}/vote", json={"value": -1}, headers={"X-Install-Key": key_(), "X-Forwarded-For": "198.51.7.9"})
        listing = client.get("/v1/canyons/21/tracks").json()
        error = client.post("/v1/canyons/21/tracks", params={"role": "APPROACH"}, content=b"<gpx><oops", headers={"X-Install-Key": key_()})
    service.close()
    (out_dir / "tracks.json").write_text(json.dumps(listing, ensure_ascii=False, indent=1))
    (out_dir / "error-invalid-gpx.json").write_text(error.text)
    print("tracks:", [(t["role"], t["standing"], t["isReference"], t["pointCount"]) for t in listing["tracks"]], "| error:", error.status_code, error.text)
