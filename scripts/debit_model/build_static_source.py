"""Static per-canyon source of the dataset (``canyon_static_descriptors.json``).

Joins the catalog, the app watersheds (room import) and the watershed descriptors of the packaged
runs: the latest world package, then the overlay packages generated after it (newest row wins), as
``scripts/watersheds/export_room_import.py`` does for the watersheds. A canyon whose watershed
changed without new descriptors loses its old ones (they describe another basin).

Run with PYTHONPATH=scripts/debit_model from the repository root.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts" / "debits"))
sys.path.insert(0, str(ROOT / "scripts" / "watersheds"))
from export_mobile_embedded_model import build_canyon_static_features  # noqa: E402
from export_room_import import discover_world_packages, overlays_after, select_latest_package  # noqa: E402


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--watershed-results", default="watershed-results")
    parser.add_argument("--canyons", default="offline-data/full/room-import/canyons.json")
    parser.add_argument("--watersheds", default="offline-data/full/room-import/watersheds.json")
    parser.add_argument("--output", required=True)
    args = parser.parse_args()

    root = Path(args.watershed_results)
    world = select_latest_package(discover_world_packages(root))
    overlays = overlays_after(discover_world_packages(root, scope="overlay"), world)
    descriptors: dict[int, dict] = {}
    for package in [world, *overlays]:
        rows = json.loads((package.run_dir / "import_ready_watershed_descriptors.json").read_text())
        if package is not world:
            # The overlay recomputed these watersheds: drop the world descriptors of all its canyons.
            for row in json.loads(package.import_ready_path.read_text()):
                descriptors.pop(int(row["canyonId"]), None)
        for row in rows:
            descriptors[int(row["canyonId"])] = row

    output = Path(args.output)
    merged_path = output.with_name(output.stem + "-watershed-descriptors.json")
    merged_path.parent.mkdir(parents=True, exist_ok=True)
    merged_path.write_text(json.dumps(list(descriptors.values()), ensure_ascii=False))
    static = build_canyon_static_features(Path(args.canyons), Path(args.watersheds), merged_path)
    output.write_text(json.dumps(static, ensure_ascii=False))
    print(json.dumps({
        "worldPackage": world.label,
        "overlays": [package.label for package in overlays],
        "descriptorRows": len(descriptors),
        "canyons": len(static),
        "output": str(output),
    }, indent=2))


if __name__ == "__main__":
    main()
