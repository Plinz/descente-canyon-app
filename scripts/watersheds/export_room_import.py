from __future__ import annotations

import argparse
from dataclasses import dataclass
import json
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


def load_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, payload: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


@dataclass(frozen=True)
class WatershedPackage:
    run_dir: Path
    import_ready_path: Path
    track: str
    label: str
    generated_at: str | None


def resolve_explicit_package(value: Path) -> WatershedPackage | None:
    if value.is_file():
        return build_package(value.parent, value)
    if not value.is_dir():
        return None

    import_ready_path = value / "import_ready_watersheds.json"
    if import_ready_path.exists():
        return build_package(value, import_ready_path)
    return None


def build_package(run_dir: Path, import_ready_path: Path) -> WatershedPackage:
    manifest_path = run_dir / "package_manifest.json"
    manifest = load_json(manifest_path) if manifest_path.exists() else {}
    if not isinstance(manifest, dict):
        manifest = {}
    return WatershedPackage(
        run_dir=run_dir,
        import_ready_path=import_ready_path,
        track=str(manifest.get("track") or run_dir.parent.name or "unknown"),
        label=str(manifest.get("label") or run_dir.name),
        generated_at=manifest.get("generatedAt") if isinstance(manifest.get("generatedAt"), str) else None,
    )


def discover_world_packages(root: Path, scope: str = "world") -> list[WatershedPackage]:
    search_root = root / "runs" if (root / "runs").is_dir() else root
    packages: list[WatershedPackage] = []
    for manifest_path in sorted(search_root.glob("**/package_manifest.json")):
        manifest = load_json(manifest_path)
        if not isinstance(manifest, dict):
            continue
        if manifest.get("scope") != scope:
            continue
        run_dir = manifest_path.parent
        import_ready_path = run_dir / "import_ready_watersheds.json"
        if not import_ready_path.exists():
            continue
        packages.append(
            WatershedPackage(
                run_dir=run_dir,
                import_ready_path=import_ready_path,
                track=str(manifest.get("track") or run_dir.parent.name or "unknown"),
                label=str(manifest.get("label") or run_dir.name),
                generated_at=manifest.get("generatedAt") if isinstance(manifest.get("generatedAt"), str) else None,
            )
        )
    return packages


def parse_generated_at(value: str | None) -> datetime:
    if not value:
        return datetime.min.replace(tzinfo=timezone.utc)
    try:
        parsed = datetime.fromisoformat(value)
    except ValueError:
        return datetime.min.replace(tzinfo=timezone.utc)
    return parsed if parsed.tzinfo is not None else parsed.replace(tzinfo=timezone.utc)


def select_latest_package(packages: list[WatershedPackage]) -> WatershedPackage:
    return max(
        packages,
        key=lambda package: (
            parse_generated_at(package.generated_at),
            package.track,
            package.label,
        ),
    )


def overlays_after(packages: list[WatershedPackage], base: WatershedPackage) -> list[WatershedPackage]:
    """Overlay packages generated after the base world run, oldest first: the newest row wins."""
    base_time = parse_generated_at(base.generated_at)
    return sorted(
        (package for package in packages if parse_generated_at(package.generated_at) > base_time),
        key=lambda package: parse_generated_at(package.generated_at),
    )


def normalize_bbox(value: Any) -> list[float] | None:
    if not isinstance(value, list) or len(value) != 4:
        return None
    try:
        return [float(item) for item in value]
    except (TypeError, ValueError):
        return None


def normalize_row(row: dict[str, Any]) -> dict[str, Any] | None:
    canyon_id = row.get("canyonId")
    if canyon_id is None:
        return None
    geometry = row.get("geometry")
    area_km2 = row.get("upstreamCatchmentAreaKm2")
    bbox = normalize_bbox(row.get("bbox"))
    if geometry is None and area_km2 is None and bbox is None:
        return None
    normalized: dict[str, Any] = {"canyonId": int(canyon_id)}
    if area_km2 is not None:
        normalized["upstreamCatchmentAreaKm2"] = float(area_km2)
    if bbox is not None:
        normalized["bbox"] = bbox
    if geometry is not None:
        normalized["geometry"] = geometry
    return normalized


def update_manifest(manifest_path: Path, watersheds_count: int) -> None:
    manifest = load_json(manifest_path)
    tables = manifest.setdefault("tables", {})
    counts = manifest.setdefault("counts", {})
    versions = manifest.setdefault("versions", {})
    tables["watersheds"] = "watersheds.json"
    counts["watersheds"] = watersheds_count
    versions["watersheds"] = datetime.now(timezone.utc).isoformat()
    write_json(manifest_path, manifest)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Genere offline-data/full/room-import/watersheds.json depuis le dernier package watershed world."
    )
    parser.add_argument(
        "--input",
        type=Path,
        default=Path("watershed-results"),
        help="Dossier watershed-results, dossier de run, ou fichier import_ready_watersheds.json.",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("offline-data/full/room-import/watersheds.json"),
        help="Fichier de sortie pour l'import Room.",
    )
    parser.add_argument(
        "--manifest",
        type=Path,
        default=Path("offline-data/full/room-import/manifest.json"),
        help="Manifest Room a mettre a jour.",
    )
    parser.add_argument(
        "--no-overlays",
        action="store_true",
        help="N'applique pas les packages overlay (scope overlay) generes apres le run world retenu.",
    )
    parser.add_argument(
        "--skip-manifest",
        action="store_true",
        help="N'actualise pas le manifest d'import.",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    explicit_package = resolve_explicit_package(args.input)
    overlay_packages: list[WatershedPackage] = []
    if explicit_package is not None:
        selected_package = explicit_package
    else:
        if not args.input.exists():
            raise FileNotFoundError(f"Fichier introuvable: {args.input}")
        packages = discover_world_packages(args.input)
        if not packages:
            raise FileNotFoundError(
                f"Aucun package watershed world trouve dans: {args.input}"
            )
        selected_package = select_latest_package(packages)
        if not args.no_overlays:
            overlay_packages = overlays_after(discover_world_packages(args.input, scope="overlay"), selected_package)

    rows_by_canyon: dict[int, dict[str, Any]] = {}
    overlay_counts: dict[str, int] = {}
    for package in [selected_package, *overlay_packages]:
        raw_rows = load_json(package.import_ready_path)
        if not isinstance(raw_rows, list):
            raise ValueError(f"Le fichier source doit contenir une liste JSON: {package.import_ready_path}")
        count = 0
        for item in raw_rows:
            normalized = normalize_row(item) if isinstance(item, dict) else None
            if normalized is not None:
                rows_by_canyon[normalized["canyonId"]] = normalized
                count += 1
        if package is not selected_package:
            overlay_counts[package.label] = count

    # Keeps the world run order: an overlay row takes the place of the row it replaces, new canyons go last.
    output_rows = list(rows_by_canyon.values())
    write_json(args.output, output_rows)

    if not args.skip_manifest and args.manifest.exists():
        update_manifest(args.manifest, len(output_rows))

    print(
        json.dumps(
            {
                "selectedRun": str(selected_package.run_dir),
                "selectedTrack": selected_package.track,
                "selectedLabel": selected_package.label,
                "selectedGeneratedAt": selected_package.generated_at,
                "input": str(selected_package.import_ready_path),
                "overlays": overlay_counts,
                "output": str(args.output),
                "watersheds": len(output_rows),
                "manifestUpdated": (not args.skip_manifest and args.manifest.exists()),
            },
            ensure_ascii=False,
            indent=2,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
