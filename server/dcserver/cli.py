"""Command line: `dcserver <command>` (see `dcserver --help`)."""
from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path
from urllib.request import Request, urlopen

from . import manifest, signing
from .config import ConfigError, Settings, load_settings
from .publish import publication_lock
from .tracks.canyons import CanyonReference, build_reference_from_catalog, write_reference


def _ping(settings: Settings, suffix: str, body: bytes = b"") -> None:
    """Healthchecks.io-style ping (start, success, fail); a monitoring outage never fails a job."""
    if not settings.healthchecks_url:
        return
    url = settings.healthchecks_url.rstrip("/") + suffix
    try:
        urlopen(Request(url, data=body[:10_000] or None, method="POST"), timeout=10).close()
    except Exception:  # noqa: BLE001
        pass


def cmd_keygen(args: argparse.Namespace) -> int:
    key = signing.write_private_key(Path(args.out))
    public = signing.public_key_b64(key)
    print(f"Private key written to {args.out} (keep it out of git and backups you do not trust).")
    print(f"Key id: {signing.key_id(public)}")
    print("Public key for the app (gradle.properties: dc.backendPublicKey=...):")
    print(public)
    return 0


def cmd_public_key(settings: Settings) -> int:
    if settings.signing_key_path is None:
        print("DC_SIGNING_KEY is not set", file=sys.stderr)
        return 2
    print(signing.public_key_b64(signing.load_private_key(settings.signing_key_path)))
    return 0


def cmd_api(settings: Settings, args: argparse.Namespace) -> int:
    import uvicorn

    from .api import create_app

    uvicorn.run(create_app(settings), host=args.host, port=args.port, access_log=False, proxy_headers=False, server_header=False)
    return 0


def cmd_job(settings: Settings, args: argparse.Namespace) -> int:
    from .jobs import catalog

    if args.pull:
        pulled = subprocess.run(["git", "-C", str(settings.repo_dir), "pull", "--ff-only", "--quiet"], capture_output=True, text=True, check=False)
        if pulled.returncode != 0:
            print(f"git pull failed, building from the current checkout: {pulled.stderr.strip()}", file=sys.stderr)
    _ping(settings, "/start")
    ids = [int(part) for part in args.ids.split(",")] if args.ids else None
    report = catalog.run(settings, ids=ids, limit=args.limit, publish=not args.dry_run)
    text = json.dumps(report, ensure_ascii=False, indent=2)
    print(text)
    if "aborted" in report:
        _ping(settings, "/fail", text.encode("utf-8"))
        return 1
    _ping(settings, "", text.encode("utf-8"))
    return 0


def cmd_manifest(settings: Settings, args: argparse.Namespace) -> int:
    with publication_lock(settings.lock_path):
        state = manifest.load_state(settings)
        if args.action == "show":
            print(json.dumps(state, ensure_ascii=False, indent=2))
            return 0
        if args.action == "message-add":
            texts = {"fr": args.fr}
            if args.en:
                texts["en"] = args.en
            state["messages"] = [m for m in state["messages"] if m["id"] != args.id] + [
                {
                    "id": args.id,
                    "level": args.level,
                    "minVersionCode": args.min_version,
                    "maxVersionCode": args.max_version,
                    "until": args.until,
                    "text": texts,
                }
            ]
        elif args.action == "message-remove":
            state["messages"] = [m for m in state["messages"] if m["id"] != args.id]
        elif args.action == "feature":
            state["features"][args.name] = args.value == "on"
        elif args.action == "min-version":
            state["minVersionCode"] = args.version_code
        published = manifest.publish(settings, state)
    print(f"Manifest published, sequence {published['sequence']}")
    return 0


def cmd_canyon_reference(settings: Settings, args: argparse.Namespace) -> int:
    reference = build_reference_from_catalog(Path(args.catalog_db))
    write_reference(settings.canyon_reference_path, reference)
    print(f"{len(reference['canyons'])} canyons written to {settings.canyon_reference_path}")
    return 0


def cmd_track_delete(settings: Settings, args: argparse.Namespace) -> int:
    from .tracks.service import TrackService

    service = TrackService(settings.database_path, settings.pepper, CanyonReference(settings.canyon_reference_path))
    try:
        removed = service.takedown(args.track_id)
    finally:
        service.close()
    print("Track deleted" if removed else "No such track")
    return 0 if removed else 1


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="dcserver", description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)

    keygen = sub.add_parser("keygen", help="create the manifest signing key and print its public key")
    keygen.add_argument("--out", required=True)
    sub.add_parser("public-key", help="print the public key of DC_SIGNING_KEY")

    api = sub.add_parser("api", help="run the HTTP API")
    api.add_argument("--host", default="127.0.0.1")
    api.add_argument("--port", type=int, default=8080)

    job = sub.add_parser("job", help="run a scheduled job")
    job.add_argument("name", choices=["catalog"])
    job.add_argument("--ids", help="comma-separated canyon ids instead of tonight's rotation slice")
    job.add_argument("--limit", type=int)
    job.add_argument("--dry-run", action="store_true", help="build and check, publish nothing")
    job.add_argument("--pull", action="store_true", help="git pull the app repository first")

    man = sub.add_parser("manifest", help="show or change the published manifest")
    man_sub = man.add_subparsers(dest="action", required=True)
    man_sub.add_parser("show")
    add = man_sub.add_parser("message-add")
    add.add_argument("--id", required=True)
    add.add_argument("--level", choices=sorted(manifest.MESSAGE_LEVELS), default="info")
    add.add_argument("--fr", required=True)
    add.add_argument("--en")
    add.add_argument("--until", help="ISO date after which the app stops showing it")
    add.add_argument("--min-version", type=int, default=0)
    add.add_argument("--max-version", type=int)
    remove = man_sub.add_parser("message-remove")
    remove.add_argument("--id", required=True)
    feature = man_sub.add_parser("feature")
    feature.add_argument("name")
    feature.add_argument("value", choices=["on", "off"])
    min_version = man_sub.add_parser("min-version")
    min_version.add_argument("version_code", type=int)

    reference = sub.add_parser("canyon-reference", help="rebuild the canyon GPS reference from a catalog.db")
    reference.add_argument("--catalog-db", required=True)

    track = sub.add_parser("track", help="operator actions on tracks")
    track_sub = track.add_subparsers(dest="action", required=True)
    delete = track_sub.add_parser("delete", help="delete a track after a legal notice")
    delete.add_argument("track_id")
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    if args.command == "keygen":
        return cmd_keygen(args)
    try:
        settings = load_settings()
    except ConfigError as exc:
        print(f"Configuration error: {exc}", file=sys.stderr)
        return 2
    if args.command == "public-key":
        return cmd_public_key(settings)
    if args.command == "api":
        return cmd_api(settings, args)
    if args.command == "job":
        return cmd_job(settings, args)
    if args.command == "manifest":
        return cmd_manifest(settings, args)
    if args.command == "canyon-reference":
        return cmd_canyon_reference(settings, args)
    if args.command == "track":
        return cmd_track_delete(settings, args)
    return 2


if __name__ == "__main__":
    sys.exit(main())
