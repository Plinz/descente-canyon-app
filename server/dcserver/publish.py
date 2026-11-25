"""Atomic publication of the files Caddy serves.

Data files are content addressed (`v1/files/<sha256><suffix>`), so they never change once
published and can be cached forever; only the manifest moves, and it is replaced atomically.
"""
from __future__ import annotations

import contextlib
import fcntl
import hashlib
import os
import shutil
import tempfile
import time
from collections.abc import Iterator
from pathlib import Path

FILES_DIR_NAME = "files"


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def atomic_write_bytes(path: Path, data: bytes, mode: int = 0o644, fsync: bool = True) -> None:
    """Writes through a temporary file in the same folder, then renames it over the target. Without
    [fsync], the caller syncs once after a batch of files."""
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp_name = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    try:
        with os.fdopen(fd, "wb") as handle:
            handle.write(data)
            handle.flush()
            if fsync:
                os.fsync(handle.fileno())
        os.chmod(tmp_name, mode)
        os.replace(tmp_name, path)
    except BaseException:
        with contextlib.suppress(FileNotFoundError):
            os.unlink(tmp_name)
        raise


@contextlib.contextmanager
def publication_lock(lock_path: Path) -> Iterator[None]:
    """Serializes the jobs that rewrite the manifest (catalog job, manual commands)."""
    lock_path.parent.mkdir(parents=True, exist_ok=True)
    with lock_path.open("a+") as handle:
        fcntl.flock(handle.fileno(), fcntl.LOCK_EX)
        try:
            yield
        finally:
            fcntl.flock(handle.fileno(), fcntl.LOCK_UN)


def publish_file(v1_dir: Path, source: Path, suffix: str) -> dict:
    """Copies [source] under its hash and returns its manifest description (path relative to v1/)."""
    digest = sha256_file(source)
    relative = f"{FILES_DIR_NAME}/{digest}{suffix}"
    target = v1_dir / relative
    if not target.exists():
        target.parent.mkdir(parents=True, exist_ok=True)
        fd, tmp_name = tempfile.mkstemp(prefix=f".{digest}.", dir=target.parent)
        os.close(fd)
        try:
            shutil.copyfile(source, tmp_name)
            os.chmod(tmp_name, 0o644)
            os.replace(tmp_name, target)
        except BaseException:
            with contextlib.suppress(FileNotFoundError):
                os.unlink(tmp_name)
            raise
    return {"path": relative, "sha256": digest, "size": target.stat().st_size}


def prune_files(v1_dir: Path, referenced: set[str], min_age_s: float = 3 * 24 * 3600, now: float | None = None) -> list[str]:
    """Deletes unreferenced data files older than [min_age_s]: apps that read the previous manifest
    can still finish their download."""
    files_dir = v1_dir / FILES_DIR_NAME
    if not files_dir.exists():
        return []
    now = time.time() if now is None else now
    removed: list[str] = []
    for path in files_dir.iterdir():
        relative = f"{FILES_DIR_NAME}/{path.name}"
        if path.name.startswith(".") or relative in referenced:
            continue
        if now - path.stat().st_mtime >= min_age_s:
            path.unlink()
            removed.append(relative)
    return sorted(removed)
