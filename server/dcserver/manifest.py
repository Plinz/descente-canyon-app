"""The signed manifest: the only file the app polls.

`v1/manifest.json` is an envelope `{format, keyId, payload, signature}` where `payload` is the base64
of the UTF-8 JSON below and `signature` its ECDSA P-256 signature. The app ignores a manifest whose
signature fails or whose `sequence` is lower than the last one it accepted, so the sequence is at
least the publication time in epoch seconds (see [next_sequence]).

Payload (schema 1):
    {
      "schema": 1, "sequence": 1795745000, "generatedAt": "2026-11-25T02:41:07Z",
      "minVersionCode": 0,                      # older apps are asked to update
      "features": {"communityTracks": true, "catalogUpdates": true, "serverPredictions": true, "canyonProposals": true,
                   "feedback": true, "shares": true},
      "messages": [{"id": "...", "level": "info|warning", "minVersionCode": 0,
                    "maxVersionCode": null, "until": null, "text": {"fr": "...", "en": "..."}}],
      "catalogs": [{"schemaVersion": 4, "identityHash": "...", "dataVersion": "...",
                    "path": "files/<sha256>.db.gz", "sha256": "...", "size": 0,
                    "uncompressedSize": 0, "canyonCount": 0}],
      "catalogIndex": {"schemaVersion": 4, "identityHash": "...", "dataVersion": "...",
                       "path": "files/<sha256>.json.gz", "sha256": "...", "size": 0},
      "predictions": {"schemaVersion": 1, "dataVersion": "...", "path": "files/<sha256>.json.gz",
                      "sha256": "...", "size": 0}
    }
The catalog index (gzipped JSON) lists the hash and reading time of every canyon's bundle
(catalog/bundles/<hash>.json.gz): the app downloads only the bundles it lacks and applies them at
once. The whole catalog stays published for apps without per-canyon hashes or too far behind.
"""
from __future__ import annotations

import base64
import json
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from . import signing
from .config import Settings
from .publish import atomic_write_bytes

SCHEMA = 1
ENVELOPE_FORMAT = 1
MESSAGE_LEVELS = {"info", "warning"}
DEFAULT_FEATURES = {
    "communityTracks": True,
    "catalogUpdates": True,
    "serverPredictions": True,
    "canyonProposals": True,
    "feedback": True,
    "shares": True,
}


def utc_now_iso() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def default_state() -> dict[str, Any]:
    return {
        "schema": SCHEMA,
        "sequence": 0,
        "generatedAt": None,
        "minVersionCode": 0,
        "features": dict(DEFAULT_FEATURES),
        "messages": [],
        "catalogs": [],
    }


def load_state(settings: Settings) -> dict[str, Any]:
    path = settings.manifest_state_path
    if not path.exists():
        return default_state()
    state = json.loads(path.read_text(encoding="utf-8"))
    merged = default_state()
    merged.update(state)
    merged["features"] = {**DEFAULT_FEATURES, **state.get("features", {})}
    return merged


def validate_state(state: dict[str, Any]) -> None:
    if state.get("schema") != SCHEMA:
        raise ValueError(f"unsupported manifest schema {state.get('schema')}")
    if not isinstance(state.get("minVersionCode"), int) or state["minVersionCode"] < 0:
        raise ValueError("minVersionCode must be a non-negative integer")
    for name, value in state.get("features", {}).items():
        if not isinstance(value, bool):
            raise ValueError(f"feature {name} must be true or false")
    seen: set[str] = set()
    for message in state.get("messages", []):
        message_id = message.get("id")
        if not message_id or message_id in seen:
            raise ValueError(f"message ids must be unique and non-empty: {message_id!r}")
        seen.add(message_id)
        if message.get("level") not in MESSAGE_LEVELS:
            raise ValueError(f"message {message_id}: level must be one of {sorted(MESSAGE_LEVELS)}")
        texts = message.get("text") or {}
        if "fr" not in texts or not all(isinstance(t, str) and t.strip() for t in texts.values()):
            raise ValueError(f"message {message_id}: a non-empty French text is required")
        link = message.get("link")
        if link is not None and (not isinstance(link, str) or not link.startswith("https://") or len(link) > 500):
            raise ValueError(f"message {message_id}: link must be an https:// address")
    for catalog in state.get("catalogs", []):
        for key in ("schemaVersion", "identityHash", "dataVersion", "path", "sha256", "size", "uncompressedSize", "canyonCount"):
            if key not in catalog:
                raise ValueError(f"catalog entry without {key}")


def encode_payload(state: dict[str, Any]) -> bytes:
    return json.dumps(state, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")


def build_envelope(state: dict[str, Any], key_path: Path) -> dict[str, Any]:
    key = signing.load_private_key(key_path)
    public_b64 = signing.public_key_b64(key)
    payload = encode_payload(state)
    return {
        "format": ENVELOPE_FORMAT,
        "keyId": signing.key_id(public_b64),
        "payload": base64.b64encode(payload).decode("ascii"),
        "signature": signing.sign(key, payload),
    }


def next_sequence(previous: int, now: float | None = None) -> int:
    """The sequence of the next manifest: above the previous one, and never below the current time
    in epoch seconds. A state lost or restored from an older backup then still publishes a manifest
    the phones accept (they ignore any sequence lower than the last one they saw, forever)."""
    now = time.time() if now is None else now
    return max(int(previous) + 1, int(now))


def publish(settings: Settings, state: dict[str, Any], now: float | None = None) -> dict[str, Any]:
    """Bumps the sequence, signs and writes the manifest, then saves the state. Call under the
    publication lock."""
    if settings.signing_key_path is None:
        raise RuntimeError("DC_SIGNING_KEY is not set: the manifest cannot be signed")
    validate_state(state)
    state = dict(state)
    state["sequence"] = next_sequence(int(state.get("sequence", 0)), now)
    state["generatedAt"] = utc_now_iso()
    envelope = build_envelope(state, settings.signing_key_path)
    atomic_write_bytes(settings.v1_dir / "manifest.json", json.dumps(envelope).encode("utf-8"))
    atomic_write_bytes(
        settings.manifest_state_path,
        json.dumps(state, ensure_ascii=False, indent=2, sort_keys=True).encode("utf-8"),
        mode=0o600,
    )
    return state


def read_published_payload(v1_dir: Path, public_b64: str) -> dict[str, Any]:
    """Reads and verifies the published manifest (used by tests and the `manifest show` command)."""
    envelope = json.loads((v1_dir / "manifest.json").read_text(encoding="utf-8"))
    payload = base64.b64decode(envelope["payload"])
    if not signing.verify(public_b64, payload, envelope["signature"]):
        raise ValueError("manifest signature does not verify")
    return json.loads(payload)


def referenced_files(state: dict[str, Any]) -> set[str]:
    paths = {entry["path"] for entry in state.get("catalogs", [])}
    for key in ("catalogIndex", "predictions"):
        if state.get(key):
            paths.add(state[key]["path"])
    return paths
