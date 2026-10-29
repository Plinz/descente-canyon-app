"""Contact sheets to check recomputed watersheds by eye.

One panel per canyon of a run kept with --keep-work: drainage network of the run's own hydrology
(upstream area above 0.05 km2, wider for larger areas), new basin (orange), basin currently in the app
(grey dashes), canyon points (ENTREE green, SORTIE red, parkings blue, review point purple) and the
outlet (black star). Writes sheet-NN.png files of ROWS x COLS panels.
"""

from __future__ import annotations

import argparse
import json
import math
from collections import defaultdict
from pathlib import Path
from typing import Any

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402
import numpy as np  # noqa: E402
import rasterio  # noqa: E402
from rasterio.warp import transform, transform_geom  # noqa: E402
from rasterio.windows import from_bounds  # noqa: E402

from audit_outlets import parse_geometry  # noqa: E402

ROWS, COLS = 3, 4
POINT_STYLES = {
    "ENTREE": ("^", "#1a9850"),
    "SORTIE": ("s", "#d73027"),
    "PARKING_AMONT": ("o", "#4575b4"),
    "PARKING_AVAL": ("o", "#4575b4"),
    "POINT_REMARQUABLE": (".", "#666666"),
}


def load_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def rings(geometry: dict[str, Any]) -> list[list[list[float]]]:
    if geometry["type"] == "Polygon":
        return [geometry["coordinates"][0]]
    return [polygon[0] for polygon in geometry["coordinates"]]


def draw_panel(ax, *, result: dict[str, Any], work_dir: Path, old_geometry: dict[str, Any] | None, points: list[dict[str, Any]], decision: dict[str, Any] | None) -> None:
    upa_path = work_dir / "hydrology" / "ign_upstream_area_km2.tif"
    watershed = result.get("watershed") or {}
    geometry = watershed.get("geometry")
    with rasterio.open(upa_path) as src:
        crs = src.crs
        shapes = [transform_geom("EPSG:4326", crs, g) for g in (geometry, old_geometry) if g]
        xs = [x for g in shapes for ring in rings(g) for x, _ in ring]
        ys = [y for g in shapes for ring in rings(g) for _, y in ring]
        point_xy = transform("EPSG:4326", crs, [p["longitude"] for p in points], [p["latitude"] for p in points]) if points else ([], [])
        xs += list(point_xy[0])
        ys += list(point_xy[1])
        span = max(max(xs) - min(xs), max(ys) - min(ys), 600.0)
        cx, cy = (max(xs) + min(xs)) / 2, (max(ys) + min(ys)) / 2
        half = span * 0.6
        bounds = (cx - half, cy - half, cx + half, cy + half)
        window = from_bounds(*bounds, transform=src.transform).round_offsets().round_lengths()
        step = max(1, int(max(window.width, window.height) / 700))
        upa = src.read(1, window=window, masked=True, out_shape=(int(window.height // step) or 1, int(window.width // step) or 1)).astype(float).filled(np.nan)
        win_bounds = rasterio.windows.bounds(window, src.transform)
    streams = np.where(upa >= 0.05, np.log10(np.clip(upa, 0.05, None)), np.nan)
    ax.imshow(streams, extent=(win_bounds[0], win_bounds[2], win_bounds[1], win_bounds[3]), cmap="Blues", vmin=-3.0, vmax=1.5, interpolation="nearest")
    if old_geometry:
        for ring in rings(transform_geom("EPSG:4326", crs, old_geometry)):
            ax.plot([p[0] for p in ring], [p[1] for p in ring], color="#555555", lw=0.9, ls="--")
    if geometry:
        for ring in rings(transform_geom("EPSG:4326", crs, geometry)):
            ax.plot([p[0] for p in ring], [p[1] for p in ring], color="#f46d43", lw=1.3)
    for point, x, y in zip(points, *point_xy):
        marker, color = POINT_STYLES.get(point.get("type"), ("x", "#7b3294"))
        ax.plot(x, y, marker=marker, color=color, ms=6 if marker != "." else 8, mec="white", mew=0.5)
    candidate = result.get("bestHydroProxyCandidate") or {}
    review = next((p for p in result.get("points", []) if p.get("forcedByReview")), None)
    if review:
        (rx,), (ry,) = transform("EPSG:4326", crs, [review["longitude"]], [review["latitude"]])
        ax.plot(rx, ry, marker="X", color="#7b3294", ms=7, mec="white", mew=0.5)
    evaluation = candidate.get("evaluation") or {}
    if evaluation.get("snapped_longitude") is not None:
        (ox,), (oy,) = transform("EPSG:4326", crs, [evaluation["snapped_longitude"]], [evaluation["snapped_latitude"]])
        ax.plot(ox, oy, marker="*", color="black", ms=11, mec="white", mew=0.6)
    ax.set_xlim(bounds[0], bounds[2])
    ax.set_ylim(bounds[1], bounds[3])
    ax.set_xticks([])
    ax.set_yticks([])
    before = (decision or {}).get("before") or {}
    after = (decision or {}).get("after") or {}
    old_area = before.get("areaKm2")
    new_area = after.get("areaKm2", watershed.get("selectedUpstreamCatchmentAreaKm2"))
    method = ((candidate.get("entryGuided") or {}).get("method")) or "snap"
    title = f"{result['canyonId']} {str(result.get('canyonName') or '')[:24]}\n"
    title += f"{'-' if old_area is None else f'{old_area:.3g}'} -> {'-' if new_area is None else f'{new_area:.3g}'} km2 · {method} · {(decision or {}).get('decision', '')}"
    flags = after.get("flags") or []
    if flags:
        title += "\n" + ",".join(flags)
    ax.set_title(title, fontsize=7)
    ax.text(0.01, 0.01, f"{span / 1000:.1f} km", transform=ax.transAxes, fontsize=6, color="#333333")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--run", type=Path, required=True)
    parser.add_argument("--decisions", type=Path, help="Report of audit_outlets.py --run (before/after/decision).")
    parser.add_argument("--room-import-dir", type=Path, default=Path("offline-data/full/room-import"))
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--only-canyon-id-file", type=Path)
    parser.add_argument("--extra-points-json", type=Path)
    args = parser.parse_args()

    app_rows = {int(row["canyonId"]): row for row in load_json(args.room_import_dir / "watersheds.json")}
    points: dict[int, list[dict[str, Any]]] = defaultdict(list)
    extra_points = load_json(args.extra_points_json) if args.extra_points_json else []
    for point in [*load_json(args.room_import_dir / "geo_points.json"), *extra_points]:
        points[int(point["canyonId"])].append(point)
    decisions = {int(item["canyonId"]): item for item in load_json(args.decisions)} if args.decisions else {}
    wanted = None
    if args.only_canyon_id_file:
        wanted = {int(line.split("|")[0]) for line in args.only_canyon_id_file.read_text().splitlines() if line.strip() and not line.startswith("#")}

    panels = []
    for path in sorted((args.run / "canyons").glob("*.json"), key=lambda p: int(p.stem)):
        canyon_id = int(path.stem)
        if wanted is not None and canyon_id not in wanted:
            continue
        work_dir = args.run / "work" / str(canyon_id)
        result = load_json(path)
        if result.get("watershedStatus") != "built" or not (work_dir / "hydrology" / "ign_upstream_area_km2.tif").exists():
            continue
        panels.append((canyon_id, result, work_dir))

    args.output_dir.mkdir(parents=True, exist_ok=True)
    per_sheet = ROWS * COLS
    index_rows = []
    for sheet_index in range(math.ceil(len(panels) / per_sheet)):
        figure, axes = plt.subplots(ROWS, COLS, figsize=(COLS * 3.6, ROWS * 3.9))
        for ax, (canyon_id, result, work_dir) in zip(axes.flat, panels[sheet_index * per_sheet:(sheet_index + 1) * per_sheet]):
            old = app_rows.get(canyon_id)
            draw_panel(
                ax,
                result=result,
                work_dir=work_dir,
                old_geometry=parse_geometry(old.get("geometry")) if old else None,
                points=points[canyon_id],
                decision=decisions.get(canyon_id),
            )
            index_rows.append({"canyonId": canyon_id, "sheet": sheet_index + 1})
        for ax in axes.flat[len(panels[sheet_index * per_sheet:(sheet_index + 1) * per_sheet]):]:
            ax.axis("off")
        figure.tight_layout()
        figure.savefig(args.output_dir / f"sheet-{sheet_index + 1:02d}.png", dpi=110)
        plt.close(figure)
    (args.output_dir / "index.json").write_text(json.dumps(index_rows, indent=1))
    print(json.dumps({"panels": len(panels), "sheets": math.ceil(len(panels) / per_sheet)}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
