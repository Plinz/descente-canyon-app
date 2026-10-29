from __future__ import annotations

import argparse
import json
import math
import time
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError

import numpy as np
import rasterio
from rasterio.transform import from_origin
from rasterio.warp import transform
from rasterio.windows import Window


WMS_URL = "https://data.geopf.fr/wms-r/wms"
DEFAULT_LAYER = "ELEVATION.ELEVATIONGRIDCOVERAGE.HIGHRES"
WMS_NODATA = -99999.0
# Lowest land of the covered territories is about -10 m (Camargue).
MIN_VALID_ELEVATION_M = -200.0
# The service accepts up to 5010 px per side.
TILE_PX = 4000


def write_json(path: Path, data: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Prepare an IGN RGE ALTI extract around canyon points from the Geoplateforme WMS (BIL float32), without the department archives."
    )
    parser.add_argument("--point", action="append", required=True, help="Point as lat,lon")
    parser.add_argument("--buffer-km", type=float, default=8.0)
    parser.add_argument("--crs", default="EPSG:2154", help="Metric CRS served by the WMS (EPSG:2154, EPSG:2975, EPSG:5490, EPSG:2972, EPSG:4471)")
    parser.add_argument("--resolution-m", type=float, default=5.0)
    parser.add_argument("--layer", default=DEFAULT_LAYER)
    parser.add_argument("--output-dir", type=Path, required=True)
    return parser.parse_args()


def parse_points(values: list[str]) -> list[tuple[float, float]]:
    return [(float(v.split(",", 1)[0]), float(v.split(",", 1)[1])) for v in values]


def fetch_tile(*, layer: str, crs: str, bbox: tuple[float, float, float, float], width: int, height: int) -> np.ndarray:
    query = urllib.parse.urlencode(
        [
            ("SERVICE", "WMS"),
            ("VERSION", "1.3.0"),
            ("REQUEST", "GetMap"),
            ("LAYERS", layer),
            ("STYLES", ""),
            ("FORMAT", "image/x-bil;bits=32"),
            ("CRS", crs),
            ("BBOX", ",".join(f"{value:.3f}" for value in bbox)),
            ("WIDTH", str(width)),
            ("HEIGHT", str(height)),
        ]
    )
    url = f"{WMS_URL}?{query}"
    last_error: Exception | None = None
    for attempt in range(1, 7):
        try:
            request = urllib.request.Request(url, headers={"User-Agent": "descente-canyon-app watershed pipeline"})
            with urllib.request.urlopen(request, timeout=300) as response:
                content_type = response.headers.get("Content-Type", "")
                payload = response.read()
            if "bil" not in content_type:
                raise RuntimeError(f"Unexpected WMS answer ({content_type}): {payload[:300]!r}")
            data = np.frombuffer(payload, dtype="<f4")
            if data.size != width * height:
                raise RuntimeError(f"WMS tile has {data.size} values, expected {width * height}")
            return data.reshape(height, width)
        except (HTTPError, URLError, TimeoutError, RuntimeError) as exc:
            # The Geoplateforme also answers 400 now and then to a valid request under load.
            last_error = exc
            time.sleep(min(60, 5 * attempt))
    raise SystemExit(f"IGN WMS request failed: {last_error}")


def main() -> int:
    args = parse_args()
    points = parse_points(args.point)
    xs, ys = transform("EPSG:4326", args.crs, [p[1] for p in points], [p[0] for p in points])
    res = float(args.resolution_m)
    buffer_m = float(args.buffer_km) * 1000.0
    # Snap the extent to the resolution grid so that reruns produce the same cells.
    min_x = math.floor((min(xs) - buffer_m) / res) * res
    max_x = math.ceil((max(xs) + buffer_m) / res) * res
    min_y = math.floor((min(ys) - buffer_m) / res) * res
    max_y = math.ceil((max(ys) + buffer_m) / res) * res
    width = int(round((max_x - min_x) / res))
    height = int(round((max_y - min_y) / res))

    output_path = args.output_dir / "raw" / "ign_wms_dem.tif"
    output_path.parent.mkdir(parents=True, exist_ok=True)
    profile = {
        "driver": "GTiff",
        "width": width,
        "height": height,
        "count": 1,
        "dtype": "float32",
        "crs": args.crs,
        "transform": from_origin(min_x, max_y, res, res),
        "nodata": WMS_NODATA,
        "compress": "lzw",
        "tiled": True,
        "blockxsize": 512,
        "blockysize": 512,
        "BIGTIFF": "IF_SAFER",
    }
    tile_count = 0
    with rasterio.open(output_path, "w", **profile) as dst:
        for row_off in range(0, height, TILE_PX):
            tile_height = min(TILE_PX, height - row_off)
            for col_off in range(0, width, TILE_PX):
                tile_width = min(TILE_PX, width - col_off)
                bbox = (
                    min_x + col_off * res,
                    max_y - (row_off + tile_height) * res,
                    min_x + (col_off + tile_width) * res,
                    max_y - row_off * res,
                )
                data = fetch_tile(layer=args.layer, crs=args.crs, bbox=bbox, width=tile_width, height=tile_height)
                # The server blends nodata into the edge pixels of the coverage (sea, borders): values
                # such as -9691 m would become deep pits in the flow routing.
                data = np.where(data < MIN_VALID_ELEVATION_M, WMS_NODATA, data).astype("float32")
                dst.write(data, 1, window=Window(col_off, row_off, tile_width, tile_height))
                tile_count += 1

    summary = {
        "dem": str(output_path),
        "crs": args.crs,
        "layer": args.layer,
        "resolutionM": res,
        "bounds": [min_x, min_y, max_x, max_y],
        "width": width,
        "height": height,
        "tiles": tile_count,
    }
    write_json(args.output_dir / "request.json", summary)
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
