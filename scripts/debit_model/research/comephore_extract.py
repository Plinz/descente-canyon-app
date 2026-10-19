"""Extract COMEPHORE (Météo-France radar + gauge hourly reanalysis, 1 km) rain over canyon catchments.

Monthly tars are streamed from the open-data bucket (never written to disk). For every canyon:
* catchment pixels: its IGN/Copernicus watershed polygon rasterised on the 1 km grid (all touched),
  else the pixel of its point; buffer = catchment dilated by 5 km;
* hourly catchment-mean rain (uint16, 0.1 mm; 65535 = missing) -> hourly.npy (memmap);
* per 06-06 UTC day (like Météo-France daily RR): max pixel 24 h total in the catchment and in the
  buffer (float32 mm) -> daily_pixmax.npy / daily_nearmax.npy.

Usage: python comephore_extract.py OUT_DIR START_YYYYMM END_YYYYMM
"""
from __future__ import annotations

import io
import json
import queue
import sys
import tarfile
import threading
import time
import urllib.request
from datetime import date, datetime, timedelta
from pathlib import Path

import numpy as np
import rasterio
from rasterio import features
from rasterio.io import MemoryFile
from rasterio.warp import transform, transform_geom
from scipy import ndimage, sparse

REPO = Path(__file__).resolve().parents[3]
URL = "https://meteofrance.s3.sbg.io.cloud.ovh.net/data/synchro_ftp/REANALYSES/COMEPHORE/H_COMEPHORE_{ym}.tar"
GRID = 1536
BUFFER_PX = 5
MISSING = 65535


def months(start: str, end: str) -> list[str]:
    y, m = int(start[:4]), int(start[4:])
    out = []
    while (y, m) <= (int(end[:4]), int(end[4:])):
        out.append(f"{y:04d}{m:02d}")
        m += 1
        if m == 13:
            y, m = y + 1, 1
    return out


def fetch(ym: str, attempts: int = 4) -> bytes | None:
    for attempt in range(attempts):
        try:
            with urllib.request.urlopen(URL.format(ym=ym), timeout=300) as response:
                return response.read()
        except Exception as error:  # network hiccup: retry
            print(f"{ym}: download error {error!r} (attempt {attempt + 1})", flush=True)
            time.sleep(10 * (attempt + 1))
    return None


def canyon_geometries() -> tuple[list[int], dict[int, dict], dict[int, tuple[float, float]]]:
    sys.path.insert(0, str(REPO / "scripts/debit_model"))
    import neighbors as N
    points = N.canyon_points(REPO / "offline-data/full/room-import/geo_points.json", REPO / "build/debit-pipeline/weather-planning/weather_targets.jsonl")
    polygons: dict[int, dict] = {}
    for path in sorted((REPO / "build/watersheds").glob("batch-run-*/watershed_polygons.geojson")):
        payload = json.loads(path.read_text())
        for feature in payload["features"]:
            canyon = int(feature["properties"]["canyonId"])
            # France first (sorted: austria, europe-rest, france, ...): keep the first non-empty geometry
            if canyon not in polygons and feature.get("geometry"):
                polygons[canyon] = feature["geometry"]
    ids = sorted(points)
    return ids, polygons, points


def masks(ids, polygons, points, crs, affine):
    """Flat pixel indices of the catchment and of its 5 km buffer, per canyon (inside the grid)."""
    lons = [points[c][1] for c in ids]
    lats = [points[c][0] for c in ids]
    xs, ys = transform("EPSG:4326", crs, lons, lats)
    inv = ~affine
    catchment, buffer, source = [], [], []
    for c, x, y in zip(ids, xs, ys):
        col_f, row_f = inv * (x, y)
        row0, col0 = int(np.floor(row_f)), int(np.floor(col_f))
        pixels = None
        if c in polygons:
            geom = transform_geom("EPSG:4326", crs, polygons[c])
            coords = np.array([pt for ring in _rings(geom) for pt in ring])
            cols, rows = [], []
            for px, py in coords:
                cf, rf = inv * (px, py)
                cols.append(cf)
                rows.append(rf)
            r1, r2 = int(np.floor(min(rows))) - BUFFER_PX - 1, int(np.ceil(max(rows))) + BUFFER_PX + 1
            c1, c2 = int(np.floor(min(cols))) - BUFFER_PX - 1, int(np.ceil(max(cols))) + BUFFER_PX + 1
            if r2 > 0 and c2 > 0 and r1 < GRID and c1 < GRID:
                window_affine = affine * rasterio.Affine.translation(c1, r1)
                window = features.rasterize([(geom, 1)], out_shape=(r2 - r1, c2 - c1), transform=window_affine, all_touched=True, fill=0, dtype="uint8")
                if window.any():
                    pixels = (window.astype(bool), r1, c1)
                    source.append("polygon")
        if pixels is None:
            r1, c1 = row0 - BUFFER_PX - 1, col0 - BUFFER_PX - 1
            window = np.zeros((2 * BUFFER_PX + 3, 2 * BUFFER_PX + 3), dtype=bool)
            window[BUFFER_PX + 1, BUFFER_PX + 1] = True
            pixels = (window, r1, c1)
            source.append("point")
        mask, r1, c1 = pixels
        dilated = ndimage.binary_dilation(mask, iterations=BUFFER_PX)
        catchment.append(_flat(mask, r1, c1))
        buffer.append(_flat(dilated, r1, c1))
    return catchment, buffer, source


def _rings(geom):
    if geom["type"] == "Polygon":
        return geom["coordinates"]
    return [ring for polygon in geom["coordinates"] for ring in polygon]


def _flat(mask, r1, c1):
    rows, cols = np.nonzero(mask)
    rows, cols = rows + r1, cols + c1
    keep = (rows >= 0) & (rows < GRID) & (cols >= 0) & (cols < GRID)
    return (rows[keep] * GRID + cols[keep]).astype(np.int64)


def reduce_max(values: np.ndarray, index_lists, concat, offsets, empty) -> np.ndarray:
    out = np.maximum.reduceat(values[concat], offsets) if len(concat) else np.zeros(len(index_lists))
    out = out.astype(np.float32)
    out[empty] = np.nan
    return out


def main():
    out_dir = Path(sys.argv[1])
    out_dir.mkdir(parents=True, exist_ok=True)
    month_list = months(sys.argv[2], sys.argv[3])
    ids, polygons, points = canyon_geometries()

    # grid georeferencing from the first month
    first = fetch(month_list[0])
    with tarfile.open(fileobj=io.BytesIO(first)) as archive:
        member = next(m for m in archive.getmembers() if m.name.endswith("_RR.gtif"))
        with MemoryFile(archive.extractfile(member).read()) as memfile, memfile.open() as src:
            crs, affine = src.crs, src.transform
    catchment, buffer, source = masks(ids, polygons, points, crs, affine)
    inside = [i for i, p in enumerate(catchment) if len(p) > 0]
    ids = [ids[i] for i in inside]
    catchment = [catchment[i] for i in inside]
    buffer = [buffer[i] for i in inside]
    source = [source[i] for i in inside]
    n = len(ids)
    print(f"{n} canyons inside the grid ({source.count('polygon')} with a catchment polygon)", flush=True)
    weights = sparse.csr_matrix(
        (np.concatenate([np.full(len(p), 1.0 / max(len(p), 1)) for p in catchment]),
         np.concatenate(catchment),
         np.concatenate([[0], np.cumsum([len(p) for p in catchment])])),
        shape=(n, GRID * GRID),
    )
    cat_concat = np.concatenate(catchment)
    cat_offsets = np.concatenate([[0], np.cumsum([len(p) for p in catchment])[:-1]])
    buf_concat = np.concatenate(buffer)
    buf_offsets = np.concatenate([[0], np.cumsum([len(p) for p in buffer])[:-1]])
    cat_empty = np.array([len(p) == 0 for p in catchment])
    buf_empty = np.array([len(p) == 0 for p in buffer])
    meta = {"canyonIds": ids, "source": source, "catchmentPixels": [int(len(p)) for p in catchment],
            "start": month_list[0], "end": month_list[-1], "crs": crs.to_wkt(), "transform": list(affine)[:6]}
    (out_dir / "meta.json").write_text(json.dumps(meta))

    start_hour = datetime(int(month_list[0][:4]), int(month_list[0][4:]), 1)  # hour ending 01 UTC of day 1 -> index 0
    end_y, end_m = int(month_list[-1][:4]), int(month_list[-1][4:])
    end_hour = datetime(end_y + (end_m == 12), end_m % 12 + 1, 1)
    total_hours = int((end_hour - start_hour).total_seconds() // 3600)
    hourly = np.lib.format.open_memmap(out_dir / "hourly.npy", mode="w+", dtype=np.uint16, shape=(total_hours, n))
    hourly[:] = MISSING
    first_day = start_hour.date() - timedelta(days=1)
    total_days = (end_hour.date() - first_day).days + 1
    pixmax = np.lib.format.open_memmap(out_dir / "daily_pixmax.npy", mode="w+", dtype=np.float32, shape=(total_days, n))
    nearmax = np.lib.format.open_memmap(out_dir / "daily_nearmax.npy", mode="w+", dtype=np.float32, shape=(total_days, n))
    pixmax[:] = np.nan
    nearmax[:] = np.nan

    accumulators: dict[date, list] = {}  # window day -> [sum grid, valid-hour count grid, hours seen]

    def finalize(day: date):
        total, count, seen = accumulators.pop(day)
        if seen < 18:
            return
        complete = np.where(count >= 18, total * 24.0 / np.maximum(count, 1), np.nan).ravel()
        filled = np.nan_to_num(complete, nan=-1.0)
        index = (day - first_day).days
        p = reduce_max(filled, catchment, cat_concat, cat_offsets, cat_empty)
        q = reduce_max(filled, buffer, buf_concat, buf_offsets, buf_empty)
        p[p < 0] = np.nan
        q[q < 0] = np.nan
        pixmax[index] = p
        nearmax[index] = q

    downloads: queue.Queue = queue.Queue(maxsize=2)

    def downloader():
        downloads.put((month_list[0], first))
        for ym in month_list[1:]:
            downloads.put((ym, fetch(ym)))
        downloads.put((None, None))

    threading.Thread(target=downloader, daemon=True).start()
    started = time.time()
    while True:
        ym, payload = downloads.get()
        if ym is None:
            break
        if payload is None:
            print(f"{ym}: skipped (download failed)", flush=True)
            continue
        month_started = time.time()
        hours = 0
        with tarfile.open(fileobj=io.BytesIO(payload)) as archive:
            members = sorted((m for m in archive.getmembers() if m.name.endswith("_RR.gtif")), key=lambda m: m.name)
            for member in members:
                stamp = Path(member.name).name[:10]
                end = datetime(int(stamp[:4]), int(stamp[4:6]), int(stamp[6:8])) + timedelta(hours=int(stamp[8:10]))
                with MemoryFile(archive.extractfile(member).read()) as memfile, memfile.open() as src:
                    raw = src.read(1)
                valid = raw != MISSING
                rain = np.where(valid, raw, 0).astype(np.float32) * 0.1
                flat_rain, flat_valid = rain.ravel(), valid.ravel().astype(np.float32)
                cover = weights @ flat_valid
                mean = (weights @ flat_rain) / np.maximum(cover, 1e-9)
                encoded = np.where(cover >= 0.5, np.clip(np.rint(mean * 10), 0, MISSING - 1), MISSING).astype(np.uint16)
                hour_index = int((end - start_hour).total_seconds() // 3600) - 1  # row i = [start + i h, start + (i + 1) h]
                if 0 <= hour_index < total_hours:
                    hourly[hour_index] = encoded
                window_day = (end - timedelta(hours=7)).date()
                if window_day not in accumulators:
                    for old in [d for d in accumulators if d < window_day - timedelta(days=1)]:
                        finalize(old)
                    accumulators[window_day] = [np.zeros((GRID, GRID), np.float32), np.zeros((GRID, GRID), np.float32), 0]
                acc = accumulators[window_day]
                acc[0] += rain
                acc[1] += valid
                acc[2] += 1
                if acc[2] == 24:
                    finalize(window_day)
                hours += 1
        hourly.flush()
        print(f"{ym}: {hours} h in {time.time() - month_started:.0f}s (total {time.time() - started:.0f}s)", flush=True)
    for day in list(accumulators):
        finalize(day)
    hourly.flush()
    pixmax.flush()
    nearmax.flush()
    print("done", flush=True)


if __name__ == "__main__":
    main()
