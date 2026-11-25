"""Builds the contract fixtures of the app tests (core/data/src/test/resources/backend) from the
real server code: a manifest signed with a throwaway key and a track list served by the API.

Run from the repository root: server/.venv/bin/python -I server/tools/make_app_fixtures.py "$PWD/server" "$PWD/core/data/src/test/resources/backend" app/build/generated/catalog-db/catalog.db
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


def make_bundle_fixture(catalog_db: Path, out: Path) -> None:
    """A real bundle of canyon 21 (Mouna et Pellas) taken from the APK's catalog, made forbidden and
    with a new remark, as the server would publish it after the site changed the sheet."""
    import shutil
    import sqlite3

    sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "scripts" / "app"))
    import catalog_bundles as B

    with tempfile.TemporaryDirectory() as tmp:
        copy = Path(tmp) / "catalog.db"
        shutil.copyfile(catalog_db, copy)
        connection = sqlite3.connect(copy)
        connection.execute("UPDATE canyons SET isForbidden = 1, hasSpecificRegulation = 1, remarques = 'Canyon interdit par arrêté.' WHERE id = 21")
        connection.execute("DELETE FROM geo_points WHERE canyonId = 21 AND type = 'POINT_REMARQUABLE'")
        connection.commit()
        connection.close()
        bundle = next(b for b in B.canyon_bundles(copy) if b.canyon_id == 21)
    (out / "catalog-bundle-21.json").write_bytes(bundle.data)
    (out / "catalog-bundle-21.hash").write_text(bundle.hash + "\n")
    print("bundle 21:", bundle.hash, len(bundle.data), "bytes")


def make_predictions_fixture(out: Path) -> None:
    """A predictions file of two canyons computed by the real model on golden inputs moved to
    2026-11-25 (computed at 07:00 UTC), with a live check."""
    from datetime import date, datetime, timedelta, timezone
    from dcserver.predictions import job as J
    from dcserver.predictions.model import ModelRunner

    repo = server_dir.parent
    runner = ModelRunner(repo)
    golden = json.loads((repo / "core/data/src/test/resources/debit/golden_vectors.json").read_text())["vectors"]
    today = date(2026, 11, 25)
    now = datetime(2026, 11, 25, 7, 0, tzinfo=timezone.utc).timestamp()
    entries = {}
    seen = set()
    for vector in golden:
        if vector["horizon"] != 0 or vector["canyonId"] in seen or len(seen) == 2:
            continue
        seen.add(vector["canyonId"])
        shift = (today - date.fromisoformat(vector["targetDate"])).days
        inputs = __import__("tests.test_predictions_model", fromlist=["golden_inputs"]).golden_inputs(vector)
        inputs.weather = {d + timedelta(days=shift): v for d, v in inputs.weather.items()}
        # The forecast of the next two days (the golden window stops at its target day).
        inputs.weather.update({today + timedelta(days=k): (2.0 * k, inputs.weather[today][1]) for k in (1, 2)})
        inputs.history = [(d + shift, r) for d, r in inputs.history]
        inputs.recent_cells = [(c, d + shift, r) for c, d, r in inputs.recent_cells]
        inputs.rain_values = {k: {d + shift: rr for d, rr in v.items()} for k, v in inputs.rain_values.items()}
        estimates, basis = runner.estimate(inputs, today)
        entries[str(vector["canyonId"])] = J.entry_of(
            target=(45.0, 6.0, "WATERSHED_CENTER"), timezone_name="Europe/Paris", lookup_source="CANYON",
            forecast_at=now - 1800, history=inputs.history, today=today, estimates=estimates, basis=basis,
        )
    live = {"days": 90, "since": "2026-08-27", "byHorizon": [
        {"horizon": 0, "reports": 40, "exact": 31, "withinOne": 39, "highReports": 6, "highCaught": 5, "highShown": 7, "highShownRight": 5},
        {"horizon": 1, "reports": 38, "exact": 27, "withinOne": 37, "highReports": 6, "highCaught": 4, "highShown": 6, "highShownRight": 4},
        {"horizon": 2, "reports": 0, "exact": 0, "withinOne": 0, "highReports": 0, "highCaught": 0, "highShown": 0, "highShownRight": 0},
    ]}
    (out / "predictions.json.gz").write_bytes(J.payload_bytes(runner, entries, live, now))


make_predictions_fixture(out_dir)
if len(sys.argv) > 3:
    make_bundle_fixture(Path(sys.argv[3]), out_dir)
