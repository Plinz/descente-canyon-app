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


def _ping(base_url: str | None, suffix: str, body: bytes = b"") -> None:
    """Healthchecks.io-style ping (start, success, fail); a monitoring outage never fails a job."""
    if not base_url:
        return
    url = base_url.rstrip("/") + suffix
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


def cmd_admin(settings: Settings, args: argparse.Namespace) -> int:
    import uvicorn

    from .admin.app import create_admin_app

    if not settings.admin_token:
        print("DC_ADMIN_TOKEN is not set: the review tool will refuse every request (openssl rand -hex 24).", file=sys.stderr)
    uvicorn.run(create_admin_app(settings), host=args.host, port=args.port, access_log=False, proxy_headers=False, server_header=False)
    return 0


def cmd_canyons(settings: Settings, args: argparse.Namespace) -> int:
    from .canyons import publication
    from .canyons.service import CanyonService

    service = CanyonService(settings.database_path, settings.pepper, id_floor=lambda: publication.repo_id_floor(settings.repo_dir))
    try:
        if args.action == "export":
            export = service.export()
            document = publication.merged_document(export, publication.load_repo_document(settings.repo_dir))
            data = publication.to_bytes(document)
            if args.output == "-":
                sys.stdout.buffer.write(data)
            else:
                Path(args.output).write_bytes(data)
                print(f"{len(document['canyons'])} canyons written to {args.output}", file=sys.stderr)
            return 0
        if args.action == "list":
            for canyon in service.canyons():
                print(f"{canyon['id']}  v{canyon['revision']}  {canyon['status']:<9}  {canyon['name']}")
            pending = service.proposals("PENDING")
            print(f"{len(pending)} proposal(s) waiting for review")
            return 0
    finally:
        service.close()
    return 2


def cmd_job(settings: Settings, args: argparse.Namespace) -> int:
    if args.pull:
        pulled = subprocess.run(["git", "-C", str(settings.repo_dir), "pull", "--ff-only", "--quiet"], capture_output=True, text=True, check=False)
        if pulled.returncode != 0:
            print(f"git pull failed, building from the current checkout: {pulled.stderr.strip()}", file=sys.stderr)
    ids = [int(part) for part in args.ids.split(",")] if args.ids else None
    if args.name == "app-canyons":
        from .jobs import catalog

        # Every ten minutes: no monitoring ping (the nightly job has its own check), and a catalog
        # job already running is no failure (it takes the sheets as well).
        report = catalog.run_app_canyons(settings)
        print(json.dumps(report, ensure_ascii=False, indent=2))
        return 1 if report.get("aborted") not in (None, "ALREADY_RUNNING") else 0
    if args.name == "predictions":
        from .predictions import job as predictions

        ping_url = settings.healthchecks_predictions_url
        _ping(ping_url, "/start")
        report = predictions.run(settings, ids=ids, publish=not args.dry_run)
    else:
        from .jobs import catalog

        ping_url = settings.healthchecks_url
        _ping(ping_url, "/start")
        report = catalog.run(settings, ids=ids, limit=args.limit, publish=not args.dry_run)
    text = json.dumps(report, ensure_ascii=False, indent=2)
    print(text)
    if "aborted" in report:
        _ping(ping_url, "/fail", text.encode("utf-8"))
        return 1
    _ping(ping_url, "", text.encode("utf-8"))
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


def cmd_share(settings: Settings, args: argparse.Namespace) -> int:
    """Operator actions on the shared links: delete one on request (the code is in the link), prune."""
    from .shares.service import ShareService

    reference = CanyonReference(settings.canyon_reference_path)
    service = ShareService(settings.database_path, settings.pepper, reference.exists, settings.public_url)
    try:
        if args.action == "prune":
            print(f"{service.prune()} expired links deleted")
            return 0
        removed = service.takedown(args.code)
    finally:
        service.close()
    print("Link deleted" if removed else "No such link")
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

    admin = sub.add_parser("admin", help="run the review tool of the proposed canyons (loopback only, see README)")
    admin.add_argument("--host", default="127.0.0.1")
    admin.add_argument("--port", type=int, default=8090)

    canyons = sub.add_parser("canyons", help="the canyons added in the app")
    canyons_sub = canyons.add_subparsers(dest="action", required=True)
    export = canyons_sub.add_parser("export", help="write the published sheets in the format of data-overlay/app-canyons.json")
    export.add_argument("--output", default="-", help="file to write (default: standard output)")
    canyons_sub.add_parser("list", help="list the canyons added and count the pending proposals")

    job = sub.add_parser("job", help="run a scheduled job")
    job.add_argument("name", choices=["catalog", "predictions", "app-canyons"])
    job.add_argument("--ids", help="comma-separated canyon ids (catalog: instead of tonight's slice; predictions: only these)")
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

    share = sub.add_parser("share", help="operator actions on the links shared from the app")
    share_sub = share.add_subparsers(dest="action", required=True)
    share_delete = share_sub.add_parser("delete", help="delete a shared link (on request of its author or after a legal notice)")
    share_delete.add_argument("code")
    share_sub.add_parser("prune", help="delete the expired links now (the API also does it along the way)")
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
    if args.command == "admin":
        return cmd_admin(settings, args)
    if args.command == "canyons":
        return cmd_canyons(settings, args)
    if args.command == "job":
        return cmd_job(settings, args)
    if args.command == "manifest":
        return cmd_manifest(settings, args)
    if args.command == "canyon-reference":
        return cmd_canyon_reference(settings, args)
    if args.command == "track":
        return cmd_track_delete(settings, args)
    if args.command == "share":
        return cmd_share(settings, args)
    return 2


if __name__ == "__main__":
    sys.exit(main())
