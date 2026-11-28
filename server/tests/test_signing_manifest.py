from __future__ import annotations

import base64
import json
import os

import pytest

from dcserver import manifest, signing
from dcserver.publish import prune_files, publish_file


def test_signature_round_trip_and_tampering(settings, public_key, tmp_path):
    key = signing.load_private_key(settings.signing_key_path)
    payload = b'{"schema":1}'
    signature = signing.sign(key, payload)
    assert signing.verify(public_key, payload, signature)
    assert not signing.verify(public_key, b'{"schema":2}', signature)
    other_key = signing.write_private_key(tmp_path / "other.pem")
    assert not signing.verify(signing.public_key_b64(other_key), payload, signature)


def test_key_file_is_private_and_never_overwritten(settings):
    assert os.stat(settings.signing_key_path).st_mode & 0o777 == 0o600
    with pytest.raises(FileExistsError):
        signing.write_private_key(settings.signing_key_path)


def test_publish_signs_and_bumps_the_sequence(settings, public_key):
    state = manifest.load_state(settings)
    first = manifest.publish(settings, state, now=1_795_745_000.4)
    # Twice in the same second: still one more.
    second = manifest.publish(settings, manifest.load_state(settings), now=1_795_745_000.9)
    assert (first["sequence"], second["sequence"]) == (1_795_745_000, 1_795_745_001)
    payload = manifest.read_published_payload(settings.v1_dir, public_key)
    assert payload["sequence"] == 1_795_745_001
    assert payload["features"] == {
        "communityTracks": True,
        "catalogUpdates": True,
        "serverPredictions": True,
        "canyonProposals": True,
        "feedback": True,
        "shares": True,
    }
    envelope = json.loads((settings.v1_dir / "manifest.json").read_text())
    assert envelope["keyId"] == signing.key_id(public_key)
    assert base64.b64decode(envelope["payload"]) == manifest.encode_payload(payload)


def test_invalid_messages_are_refused(settings):
    state = manifest.load_state(settings)
    state["messages"] = [{"id": "a", "level": "loud", "text": {"fr": "x"}}]
    with pytest.raises(ValueError):
        manifest.publish(settings, state)
    state["messages"] = [{"id": "a", "level": "info", "text": {"en": "no french"}}]
    with pytest.raises(ValueError):
        manifest.publish(settings, state)


def test_files_are_content_addressed_and_pruned(settings, tmp_path):
    source = tmp_path / "catalog.db.gz"
    source.write_bytes(b"catalog")
    entry = publish_file(settings.v1_dir, source, ".db.gz")
    assert entry["path"].startswith("files/") and entry["path"].endswith(".db.gz")
    assert (settings.v1_dir / entry["path"]).read_bytes() == b"catalog"
    stale = tmp_path / "old.db.gz"
    stale.write_bytes(b"old")
    old = publish_file(settings.v1_dir, stale, ".db.gz")
    assert prune_files(settings.v1_dir, {entry["path"]}, min_age_s=3600) == []
    assert prune_files(settings.v1_dir, {entry["path"]}, min_age_s=0) == [old["path"]]
    assert (settings.v1_dir / entry["path"]).exists()


def test_a_lost_state_still_publishes_a_sequence_the_phones_accept(settings, public_key):
    """Phones ignore a lower sequence forever: after a state lost or restored from an older backup,
    the next manifest must still be above everything published before."""
    published = manifest.publish(settings, manifest.load_state(settings), now=1_795_745_000)
    for _ in range(3):
        published = manifest.publish(settings, manifest.load_state(settings), now=1_795_745_000)
    settings.manifest_state_path.unlink()
    restored = manifest.publish(settings, manifest.load_state(settings), now=1_795_745_060)
    assert restored["sequence"] > published["sequence"]
    # A clock set back does not make the sequence go down either.
    assert manifest.publish(settings, manifest.load_state(settings), now=1_000)["sequence"] == restored["sequence"] + 1
