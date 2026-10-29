from __future__ import annotations

import argparse
import json
import shutil
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


CORE_FILES = [
    "summary.json",
    "progress.json",
    "import_ready_catchments.json",
    "import_ready_watershed_descriptors.json",
    "import_ready_watersheds.json",
    "watershed_polygons.geojson",
    "errors.log",
    "source_resolution.log",
]

OPTIONAL_DEBUG_FILES = [
    "all_canyon_point_catchments.json",
]


def load_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, data: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")


def slugify(value: str) -> str:
    return "-".join(value.lower().replace("_", "-").split())


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Copies a world watershed batch run into the tracked watershed-results structure."
    )
    parser.add_argument("--source-dir", type=Path, required=True)
    parser.add_argument(
        "--track",
        required=True,
        choices=["full", "retry", "incremental", "manual", "new-canyons"],
        help="Run family/category",
    )
    parser.add_argument("--label", required=True, help="Run label, e.g. 2026-03-25-fr-v1")
    parser.add_argument(
        "--scope",
        choices=["world", "overlay"],
        default="world",
        help="'overlay' for a run of a few canyons whose rows replace those of the latest world run at export.",
    )
    parser.add_argument("--output-root", type=Path, default=Path("watershed-results"))
    parser.add_argument("--include-debug", action="store_true")
    parser.add_argument("--include-canyon-json", action="store_true")
    parser.add_argument("--canyon-id-file", type=Path)
    parser.add_argument("--force", action="store_true")
    return parser.parse_args()


def build_canyon_status_index(source_dir: Path) -> list[dict[str, Any]]:
    canyon_dir = source_dir / "canyons"
    if not canyon_dir.exists():
        return []

    items: list[dict[str, Any]] = []
    for path in sorted(canyon_dir.glob("*.json")):
        payload = load_json(path)
        items.append(
            {
                "canyonId": payload.get("canyonId"),
                "canyonName": payload.get("canyonName"),
                "status": payload.get("status", "ok"),
                "sourceName": payload.get("sourceName"),
                "watershedStatus": payload.get("watershedStatus"),
                "watershedSkipReason": payload.get("watershedSkipReason"),
                "selectedPointType": (payload.get("bestHydroProxyCandidate") or {}).get("pointType"),
                "selectedUpstreamCatchmentAreaKm2": ((payload.get("bestHydroProxyCandidate") or {}).get("evaluation") or {}).get("snapped_upa_km2"),
            }
        )
    return items


def copy_if_exists(source: Path, destination: Path) -> bool:
    if not source.exists():
        return False
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, destination)
    return True


def main() -> int:
    args = parse_args()
    source_dir = args.source_dir.resolve()
    output_dir = (args.output_root / "runs" / args.track / slugify(args.label)).resolve()

    if output_dir.exists() and not args.force:
        raise SystemExit(f"Output directory already exists: {output_dir}. Use --force to overwrite.")
    if output_dir.exists() and args.force:
        shutil.rmtree(output_dir)

    copied_files: list[str] = []
    for filename in CORE_FILES:
        if copy_if_exists(source_dir / filename, output_dir / filename):
            copied_files.append(filename)

    if args.include_debug:
        for filename in OPTIONAL_DEBUG_FILES:
            if copy_if_exists(source_dir / filename, output_dir / filename):
                copied_files.append(filename)

    if args.include_canyon_json and (source_dir / "canyons").exists():
        shutil.copytree(source_dir / "canyons", output_dir / "canyons", dirs_exist_ok=True)
        copied_files.append("canyons/")

    if args.canyon_id_file and args.canyon_id_file.exists():
        copy_if_exists(args.canyon_id_file.resolve(), output_dir / args.canyon_id_file.name)
        copied_files.append(args.canyon_id_file.name)

    canyon_status_index = build_canyon_status_index(source_dir)
    write_json(output_dir / "canyon_status_index.json", canyon_status_index)

    failed = [item for item in canyon_status_index if item.get("status") != "ok"]
    write_json(output_dir / "failed_canyons.json", failed)

    manifest = {
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "sourceDir": str(source_dir),
        "scope": args.scope,
        "track": args.track,
        "label": args.label,
        "copiedFiles": copied_files,
        "canyonStatusCount": len(canyon_status_index),
        "failedCount": len(failed),
    }
    write_json(output_dir / "package_manifest.json", manifest)

    print(json.dumps({"outputDir": str(output_dir), "copiedFiles": copied_files, "failedCount": len(failed)}, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
