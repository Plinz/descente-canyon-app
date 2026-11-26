"""The published sheets of the canyons added in the app, as the catalog generator reads them.

Two sources: the server's database (the reference, written by the review tool) and the copy kept in
the app repository (`data-overlay/app-canyons.json`, exported with `dcserver canyons export`), which
the APK embeds and which keeps the sheets if the database is lost. The database wins for a canyon
it knows; a canyon it hides stays out even if the repository still has it.
"""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
from typing import Any

from .content import FIRST_APP_CANYON_ID

REPO_FILE = Path("data-overlay/app-canyons.json")
FORMAT = 1


def empty_document() -> dict[str, Any]:
    return {"format": FORMAT, "licence": "CC0-1.0", "canyons": []}


def load_repo_document(repo_dir: Path) -> dict[str, Any] | None:
    path = repo_dir / REPO_FILE
    if not path.exists():
        return None
    document = json.loads(path.read_text(encoding="utf-8"))
    if document.get("format") != FORMAT:
        raise ValueError(f"{REPO_FILE}: unknown format {document.get('format')!r}")
    return document


def repo_id_floor(repo_dir: Path) -> int:
    """The highest id of the repository's copy (new canyons are numbered above it)."""
    try:
        document = load_repo_document(repo_dir)
    except (OSError, ValueError):
        document = None
    ids = [int(c["id"]) for c in (document or {}).get("canyons", [])]
    return max(ids, default=FIRST_APP_CANYON_ID - 1)


def merged_document(export: dict[str, Any], repo: dict[str, Any] | None) -> dict[str, Any]:
    by_id: dict[int, dict[str, Any]] = {}
    for canyon in (repo or {}).get("canyons", []):
        by_id[int(canyon["id"])] = canyon
    for canyon in export.get("canyons", []):
        by_id[int(canyon["id"])] = canyon
    for hidden in export.get("hidden", []):
        by_id.pop(int(hidden), None)
    document = empty_document()
    document["canyons"] = [by_id[key] for key in sorted(by_id)]
    return document


def to_bytes(document: dict[str, Any]) -> bytes:
    return (json.dumps(document, ensure_ascii=False, indent=1, sort_keys=False) + "\n").encode("utf-8")


def digest(document: dict[str, Any]) -> str:
    return hashlib.sha256(json.dumps(document, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")).hexdigest()
