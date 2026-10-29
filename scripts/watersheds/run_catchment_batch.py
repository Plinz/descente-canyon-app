from __future__ import annotations

import argparse
import concurrent.futures
import collections
import copy
import json
import math
import os
import shutil
import subprocess
import sys
import threading
import time
import traceback
from concurrent.futures.process import BrokenProcessPool
from dataclasses import asdict
from pathlib import Path
import sys

_SCRIPT_ROOT = next(parent for parent in Path(__file__).resolve().parents if (parent / "common").is_dir())
if str(_SCRIPT_ROOT) not in sys.path:
    sys.path.insert(0, str(_SCRIPT_ROOT))
from typing import Any

import numpy as np
import rasterio
from rasterio.crs import CRS
from rasterio.features import shapes
from rasterio.warp import transform, transform_geom

from common.cli_tools import default_gdal_translate, default_gdalwarp, resolve_executable
from compute_entry_watersheds import FLOW_DIRECTION_OFFSETS, EntryPoint, create_raster, evaluate_entry, load_canyons
from osm_regulation import query_osm_regulation, summarize_osm_regulation
from run_local_ign_workflow import DEFAULT_LAMBERT93_PROJ4
from features import (
    build_watershed_mask_data,
    compute_watershed_descriptors,
    mask_to_geometry,
    _advanced_regulation_metrics,
)


POINT_TYPE_PRIORITY = {
    "ENTREE": 0,
    "SORTIE": 1,
    "PARKING_AMONT": 2,
    "PARKING_AVAL": 3,
    "POINT_REMARQUABLE": 4,
}

HYDRO_TYPE_BONUS = {
    "ENTREE": 6.0,
    "SORTIE": 5.0,
    "PARKING_AVAL": 3.0,
    "POINT_REMARQUABLE": 2.0,
    "PARKING_AMONT": 1.0,
    "REVIEW_GPS": 10.0,
}

DYNAMIC_AUTOPREPARE_PROVIDERS = {
    "switzerland-stac",
    "spain-wcs",
    "austria-als",
    "slovenia-jgp",
    "liguria-wcs",
    "madeira-wcs",
    "copernicus",
    "ign-wms",
}

DEFAULT_REVIEW_FILE = Path("watershed-review/watershed-review.json")
REVIEW_HYDRO_SNAP_SEARCH_RADII_M = (15.0, 20.0, 30.0)
ENTRY_GUIDED_MAX_OFFSET_M = 150.0
ENTRY_GUIDED_REFERENCE_TYPES = ("SORTIE", "PARKING_AVAL")
ENTRY_GUIDED_MIN_UPA_KM2 = 0.05
AUTOPREPARE_CACHE_LOCK = threading.Lock()
AUTOPREPARE_CACHE: dict[tuple[Any, ...], dict[str, Any]] = {}


def default_executor_mode() -> str:
    return "thread"


def configure_runtime_caches() -> None:
    os.environ.setdefault("GDAL_CACHEMAX", "1024")
    os.environ.setdefault("VSI_CACHE", "TRUE")
    os.environ.setdefault("VSI_CACHE_SIZE", str(128 * 1024 * 1024))
    os.environ.setdefault("CPL_VSIL_CURL_CACHE_SIZE", str(128 * 1024 * 1024))


def load_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, data: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")


def load_json_if_exists(path: Path) -> Any | None:
    if not path.exists():
        return None
    try:
        return load_json(path)
    except json.JSONDecodeError:
        return None


def append_text(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a", encoding="utf-8") as handle:
        handle.write(text)


def load_canyon_ids_from_file(path: Path) -> list[int]:
    canyon_ids: list[int] = []
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        left = line.split("|", 1)[0].strip()
        if left:
            canyon_ids.append(int(left))
    return canyon_ids


def _normalize_gps(gps: dict[str, Any] | None) -> tuple[float, float] | None:
    if not isinstance(gps, dict):
        return None
    try:
        return (round(float(gps["latitude"]), 6), round(float(gps["longitude"]), 6))
    except (KeyError, TypeError, ValueError):
        return None


def _extract_existing_selected_gps(payload: dict[str, Any]) -> tuple[float, float] | None:
    candidate = payload.get("bestHydroProxyCandidate")
    if isinstance(candidate, dict):
        try:
            return (round(float(candidate["latitude"]), 6), round(float(candidate["longitude"]), 6))
        except (KeyError, TypeError, ValueError):
            pass

    review_override = payload.get("reviewOverride")
    normalized_review_gps = _normalize_gps(review_override.get("gps") if isinstance(review_override, dict) else None)
    if normalized_review_gps is not None:
        return normalized_review_gps
    return None


def _existing_forced_review_point(payload: dict[str, Any]) -> dict[str, Any] | None:
    points = payload.get("points")
    if isinstance(points, list):
        for point in points:
            if isinstance(point, dict) and point.get("forcedByReview"):
                return point

    for key in ("reviewTruthCandidate", "bestHydroProxyCandidate"):
        candidate = payload.get(key)
        if isinstance(candidate, dict) and candidate.get("forcedByReview"):
            return candidate
    return None


def _existing_has_review_hydrology_override(payload: dict[str, Any]) -> bool:
    if payload.get("forcedByReview"):
        return True
    if isinstance(payload.get("reviewOverride"), dict):
        return True
    return _existing_forced_review_point(payload) is not None


def _existing_review_override_has_snap_metadata(
    payload: dict[str, Any],
    review_override: dict[str, Any],
) -> bool:
    forced_point = _existing_forced_review_point(payload)
    if forced_point is None:
        return False
    if forced_point.get("reviewStatus") != review_override.get("status"):
        return False
    if bool(forced_point.get("reviewAdminPlaced", False)) != bool(review_override.get("adminPlaced", False)):
        return False
    attempted_radii = forced_point.get("reviewSnapAttemptedRadiiM")
    return isinstance(attempted_radii, list) and len(attempted_radii) > 0


def should_skip_existing_canyon(
    path: Path,
    skip_existing: bool,
    review_override: dict[str, Any] | None = None,
) -> bool:
    if not skip_existing or not path.exists():
        return False
    try:
        payload = load_json(path)
    except Exception:
        return False
    status = payload.get("status", "ok")
    if status == "error":
        return False

    if review_override is None:
        return not _existing_has_review_hydrology_override(payload)

    if not review_override.get("applyHydrologyOverride", False):
        return not _existing_has_review_hydrology_override(payload)

    current_review_gps = _normalize_gps(review_override.get("gps"))
    if current_review_gps is None:
        return True

    if not _existing_review_override_has_snap_metadata(payload, review_override):
        return False

    existing_selected_gps = _extract_existing_selected_gps(payload)
    if existing_selected_gps is None:
        return False
    return existing_selected_gps == current_review_gps


def load_review_overrides(review_file: Path) -> dict[int, dict[str, Any]]:
    reviews = load_json(review_file)
    if not isinstance(reviews, list):
        raise SystemExit(f"Invalid review file format: {review_file}")

    overrides: dict[int, dict[str, Any]] = {}
    for review in reviews:
        if not isinstance(review, dict):
            continue
        status = str(review.get("status", "")).lower()
        if status not in {"good", "bad", "pending"}:
            continue
        gps = review.get("gps")
        normalized_gps = None
        if isinstance(gps, dict):
            try:
                normalized_gps = {
                    "latitude": float(gps["latitude"]),
                    "longitude": float(gps["longitude"]),
                }
            except (KeyError, TypeError, ValueError):
                normalized_gps = None
        try:
            canyon_id = int(review["canyonId"])
        except (KeyError, TypeError, ValueError):
            continue
        overrides[canyon_id] = {
            "canyonId": canyon_id,
            "status": status,
            "gps": normalized_gps,
            "adminPlaced": bool(review.get("adminPlaced", False)),
            "pointType": review.get("pointType"),
            "label": review.get("label"),
            "applyHydrologyOverride": bool(
                normalized_gps is not None
                and status in {"bad", "pending"}
                and bool(review.get("adminPlaced", False))
            ),
        }
    return overrides


def is_review_hydrology_override(point: dict[str, Any]) -> bool:
    return bool(point.get("_reviewHydroOverride") or point.get("_forceExactCell"))


def review_snap_search_radii_m(point: dict[str, Any]) -> tuple[float, ...]:
    raw = point.get("_reviewSnapSearchRadiiM")
    if isinstance(raw, list):
        radii: list[float] = []
        for value in raw:
            try:
                radius = float(value)
            except (TypeError, ValueError):
                continue
            if radius > 0:
                radii.append(radius)
        if radii:
            return tuple(radii)
    return REVIEW_HYDRO_SNAP_SEARCH_RADII_M


def apply_review_overrides(
    points_by_canyon: dict[int, list[dict[str, Any]]],
    review_overrides: dict[int, dict[str, Any]],
    *,
    review_file: Path,
) -> dict[int, list[dict[str, Any]]]:
    merged = {canyon_id: list(points) for canyon_id, points in points_by_canyon.items()}
    review_file_str = str(review_file).replace("\\", "/")
    for canyon_id, review in review_overrides.items():
        if not review.get("applyHydrologyOverride"):
            continue
        gps = review.get("gps")
        if not isinstance(gps, dict):
            continue
        merged.setdefault(canyon_id, [])
        merged[canyon_id].insert(
            0,
            {
                "canyonId": canyon_id,
                "type": review.get("pointType") or "REVIEW_GPS",
                "label": review.get("label") or "review_truth",
                "latitude": gps["latitude"],
                "longitude": gps["longitude"],
                "_reviewHydroOverride": True,
                "_reviewSnapSearchRadiiM": list(REVIEW_HYDRO_SNAP_SEARCH_RADII_M),
                "_reviewStatus": review.get("status"),
                "_reviewAdminPlaced": bool(review.get("adminPlaced", False)),
                "_reviewSourceFile": review_file_str,
            },
        )
    return merged


def source_with_buffer(source: dict[str, Any], buffer_km: float) -> dict[str, Any]:
    widened = dict(source)
    widened["bufferKm"] = float(buffer_km)
    auto_prepare = source.get("autoPrepare")
    if isinstance(auto_prepare, dict):
        widened_auto_prepare = dict(auto_prepare)
        widened_auto_prepare["bufferKm"] = float(buffer_km)
        widened["autoPrepare"] = widened_auto_prepare
    return widened


def find_coarser_ign_source(source: dict[str, Any], sources: list[dict[str, Any]]) -> dict[str, Any] | None:
    source_name = str(source.get("name") or "")
    if not source_name.startswith("ign-rgealti5m-"):
        return None

    target_name = source_name.replace("ign-rgealti5m-", "ign-bdalti-", 1)
    for candidate in sources:
        if str(candidate.get("name") or "") == target_name:
            return candidate

    source_match = source.get("match")
    for candidate in sources:
        candidate_name = str(candidate.get("name") or "")
        candidate_provider = (candidate.get("autoPrepare") or {}).get("provider")
        candidate_dataset = (candidate.get("autoPrepare") or {}).get("dataset")
        if candidate_name.startswith("ign-bdalti-") and candidate_provider == "ign" and candidate_dataset == "bdalti":
            if candidate.get("match") == source_match:
                return candidate
    return None


def find_named_source(sources: list[dict[str, Any]], *names: str) -> dict[str, Any] | None:
    wanted = set(names)
    for candidate in sources:
        if str(candidate.get("name") or "") in wanted:
            return candidate
    return None


def choose_retry_hydrology_source(
    *,
    source: dict[str, Any],
    sources: list[dict[str, Any]],
    next_buffer_km: float,
) -> tuple[dict[str, Any], dict[str, Any] | None]:
    widened_source = source_with_buffer(source, next_buffer_km)
    if next_buffer_km < 40.0:
        return widened_source, None

    provider = (source.get("autoPrepare") or {}).get("provider")
    if provider == "spain-wcs":
        copernicus_source = find_named_source(sources, "copernicus-europe", "copernicus-global")
        if copernicus_source is not None:
            switched_source = source_with_buffer(copernicus_source, next_buffer_km)
            return switched_source, {
                "reason": "edge_retry_buffer_gte_40km",
                "fromSource": str(source.get("name") or ""),
                "toSource": str(copernicus_source.get("name") or ""),
                "bufferKm": float(next_buffer_km),
            }

    coarser_source = find_coarser_ign_source(source, sources)
    if coarser_source is None:
        return widened_source, None

    switched_source = source_with_buffer(coarser_source, next_buffer_km)
    return switched_source, {
        "reason": "edge_retry_buffer_gte_40km",
        "fromSource": str(source.get("name") or ""),
        "toSource": str(coarser_source.get("name") or ""),
        "bufferKm": float(next_buffer_km),
    }


def watershed_mask_touches_raster_edge(mask_data: dict[str, Any] | None) -> bool:
    if not mask_data:
        return False
    mask = mask_data.get("mask")
    if not isinstance(mask, np.ndarray) or mask.ndim != 2 or mask.size == 0:
        return False
    return bool(
        np.any(mask[0, :])
        or np.any(mask[-1, :])
        or np.any(mask[:, 0])
        or np.any(mask[:, -1])
    )


def bbox_for_points_in_crs(points: list[dict[str, Any]], target_crs: str, buffer_km: float) -> tuple[float, float, float, float]:
    lats = [float(point["latitude"]) for point in points]
    lons = [float(point["longitude"]) for point in points]
    xs, ys = transform("EPSG:4326", target_crs, lons, lats)
    min_x = min(xs) - buffer_km * 1000.0
    max_x = max(xs) + buffer_km * 1000.0
    min_y = min(ys) - buffer_km * 1000.0
    max_y = max(ys) + buffer_km * 1000.0
    return min_x, min_y, max_x, max_y


def crs_is_geographic(crs_value: Any) -> bool:
    if not crs_value:
        return False
    try:
        return bool(CRS.from_user_input(crs_value).is_geographic)
    except Exception:
        return False


def local_projected_crs_for_points(points: list[dict[str, Any]]) -> str:
    mean_lat = sum(float(point["latitude"]) for point in points) / len(points)
    mean_lon = sum(float(point["longitude"]) for point in points) / len(points)
    if -80.0 <= mean_lat <= 84.0:
        zone = max(1, min(60, int(math.floor((mean_lon + 180.0) / 6.0)) + 1))
        epsg = 32600 + zone if mean_lat >= 0 else 32700 + zone
        return f"EPSG:{epsg}"
    return "EPSG:3857"


def effective_processing_crs(source: dict[str, Any], points: list[dict[str, Any]]) -> str | None:
    processing_crs = source.get("processingCrs")
    processing_resolution = source.get("processingResolutionM")
    if str(processing_crs or "").upper() == "AUTO_LOCAL_UTM":
        return local_projected_crs_for_points(points)
    if processing_crs and not crs_is_geographic(processing_crs):
        return str(processing_crs)
    if processing_resolution and crs_is_geographic(source.get("srs")):
        return local_projected_crs_for_points(points)
    if processing_crs:
        return str(processing_crs)
    return None


def merge_dem_paths(
    *,
    dem_paths: list[Path],
    target_path: Path,
    target_srs: str,
    points: list[dict[str, Any]],
    buffer_km: float,
    resolution_m: float,
    gdal_warp: str,
) -> Path:
    target_path.parent.mkdir(parents=True, exist_ok=True)
    min_x, min_y, max_x, max_y = bbox_for_points_in_crs(points, target_srs, buffer_km)
    command = [
        gdal_warp,
        "-overwrite",
        "-multi",
        "-wo",
        "NUM_THREADS=ALL_CPUS",
        "-r",
        "bilinear",
        "-dstnodata",
        "-9999",
        "-t_srs",
        target_srs,
        "-tr",
        str(resolution_m),
        str(resolution_m),
        "-te",
        str(min_x),
        str(min_y),
        str(max_x),
        str(max_y),
        *[str(path) for path in dem_paths],
        str(target_path),
    ]
    subprocess.run(command, check=True)
    return target_path


def normalized_path(value: str | Path | None) -> Path | None:
    if value is None:
        return None
    return Path(str(value).replace("\\", "/"))


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Batch canyon-by-canyon resumable catchment computation across the whole base."
    )
    parser.add_argument(
        "--source-config",
        type=Path,
        default=Path("scripts/watersheds/source_config.example.json"),
    )
    parser.add_argument(
        "--canyons-json",
        type=Path,
        default=Path("offline-data/full/room-import/canyons.json"),
    )
    parser.add_argument(
        "--geo-points-json",
        type=Path,
        default=Path("offline-data/full/room-import/geo_points.json"),
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=Path("build/watersheds/batch-run"),
    )
    parser.add_argument(
        "--extra-points-json",
        type=Path,
        help="Points absents du site (texte du topo, OpenCanyon...), utilises pour le calcul seulement: liste de {canyonId, type, latitude, longitude, label, source}.",
    )
    parser.add_argument("--only-canyon-id", type=int, action="append")
    parser.add_argument("--only-canyon-id-file", type=Path)
    parser.add_argument(
        "--review-file",
        type=Path,
        default=DEFAULT_REVIEW_FILE,
        help="Fichier de verite review utilise pour forcer les points valides/admin.",
    )
    parser.add_argument("--max-canyons", type=int)
    parser.add_argument("--jobs", type=int, default=1)
    parser.add_argument(
        "--executor",
        choices=["thread", "process"],
        default=default_executor_mode(),
        help="Type de parallellisme canyon-level. 'process' exploite mieux le CPU sur Linux.",
    )
    parser.add_argument("--aggregate-every", type=int, default=25)
    parser.add_argument("--skip-existing", dest="skip_existing", action="store_true", default=True)
    parser.add_argument("--reprocess-existing", dest="skip_existing", action="store_false")
    parser.add_argument(
        "--point-type",
        action="append",
        default=["ENTREE", "SORTIE", "PARKING_AMONT", "PARKING_AVAL", "POINT_REMARQUABLE"],
    )
    parser.add_argument(
        "--gdal-translate",
        default=default_gdal_translate(),
    )
    parser.add_argument("--gdal-warp", default=default_gdalwarp())
    parser.add_argument("--country", action="append")
    parser.add_argument("--france-only", action="store_true")
    parser.add_argument("--world", action="store_true")
    parser.add_argument("--enable-osm-regulation", action="store_true")
    parser.add_argument("--prepare-france-ign-first", action="store_true")
    parser.add_argument("--keep-work", action="store_true")
    parser.add_argument(
        "--entry-guided-outlet",
        action="store_true",
        help="Place l'exutoire sur le chemin d'ecoulement D8 des points ENTREE, au plus pres du point de sortie (review, SORTIE, parking aval).",
    )
    parser.add_argument(
        "--entry-guided-min-upa-km2",
        type=float,
        default=ENTRY_GUIDED_MIN_UPA_KM2,
        help="Surface amont minimale de l'exutoire guide (plus bas pour les ravines seches sur un MNT a 5 m).",
    )
    parser.add_argument(
        "--skip-descriptors",
        action="store_true",
        help="Calcule seulement la surface et le polygone, sans les descripteurs (sols, climat, occupation du sol...).",
    )
    return parser.parse_args()


def canyon_matches(canyon: dict[str, Any], match: dict[str, Any]) -> bool:
    if match.get("default"):
        return True
    for key, expected in match.items():
        if key == "default":
            continue
        if key.endswith("NotIn"):
            field_name = key[: -len("NotIn")]
            actual = canyon.get(field_name)
            if actual in expected:
                return False
            continue
        actual = canyon.get(key)
        if key in {"departement", "region"} and isinstance(actual, str) and "," in actual:
            actual_values = [part.strip() for part in actual.split(",") if part.strip()]
        else:
            actual_values = [actual]
        if isinstance(expected, list):
            if not any(value in expected for value in actual_values):
                return False
            continue
        if expected not in actual_values:
            return False
    return True


def source_is_available(source: dict[str, Any]) -> bool:
    if source.get("preparedDynamically"):
        dem_path = normalized_path(source.get("dem"))
        return dem_path is not None and dem_path.exists()
    mode = source.get("mode")
    if mode == "derive_local_hydrology":
        dynamic_provider = (source.get("autoPrepare") or {}).get("provider") in {
            "switzerland-stac",
            "spain-wcs",
            "austria-als",
            "slovenia-jgp",
            "liguria-wcs",
        }
        if dynamic_provider:
            return False
        availability_path = normalized_path(source.get("availabilityPath"))
        if availability_path is not None:
            return availability_path.exists()
        dem = source.get("dem")
        dem_path = normalized_path(dem)
        return dem_path is not None and dem_path.exists()
    if mode == "precomputed_hydrology":
        required = [source.get("upaRaster")]
        optional = [source.get("flowdirRaster"), source.get("elevationRaster")]
        if not all(required):
            return False
        if not all(normalized_path(path).exists() for path in required if path):
            return False
        if not all(normalized_path(path).exists() for path in optional if path):
            return False
        return True
    return False


def copernicus_cell_name(latitude: float, longitude: float) -> str:
    lat_prefix = "N" if latitude >= 0 else "S"
    lon_prefix = "E" if longitude >= 0 else "W"
    lat_degree = abs(math.floor(float(latitude)))
    lon_degree = abs(math.floor(float(longitude)))
    return f"{lat_prefix}{lat_degree:02d}_{lon_prefix}{lon_degree:03d}"


def points_copernicus_cells(points: list[dict[str, Any]]) -> list[str]:
    return sorted({copernicus_cell_name(float(point["latitude"]), float(point["longitude"])) for point in points})


def ign_prepare_department(source: dict[str, Any], canyon: dict[str, Any]) -> str:
    source_match = source.get("match")
    if isinstance(source_match, dict):
        source_department = source_match.get("departement")
        if isinstance(source_department, str) and source_department.strip():
            return source_department.strip()
    return str(canyon.get("departement") or "").strip()


def auto_prepare_cache_key(
    *,
    source: dict[str, Any],
    canyon: dict[str, Any],
    points: list[dict[str, Any]],
) -> tuple[Any, ...] | None:
    auto_prepare = source.get("autoPrepare") or {}
    provider = auto_prepare.get("provider")
    if provider == "ign":
        return (
            provider,
            auto_prepare.get("outputDir", "build/watersheds/ign-data"),
            auto_prepare.get("dataset", "rgealti5m"),
            ign_prepare_department(source, canyon),
        )
    if provider == "copernicus":
        return (
            provider,
            auto_prepare.get("outputDir", "build/watersheds/copernicus-data"),
            tuple(points_copernicus_cells(points)),
        )
    if provider == "merit-ihu-global":
        return (provider, auto_prepare.get("outputDir", "build/watersheds/merit-ihu-global"))
    if provider == "tinitaly-bulk":
        return (provider, auto_prepare.get("outputDir", "build/watersheds/italy-national-dem"))
    if provider == "national-dem":
        units: list[str] = []
        for field_name in auto_prepare.get("unitFields", ["departement", "region"]):
            value = canyon.get(field_name)
            if value:
                units.append(str(value))
        for value in auto_prepare.get("extraUnits", []):
            units.append(str(value))
        return (
            provider,
            auto_prepare.get("outputDir"),
            auto_prepare.get("manifest"),
            tuple(sorted(unit for unit in units if unit)),
        )
    return None


def auto_prepare_source_cached(
    *,
    source: dict[str, Any],
    canyon: dict[str, Any],
    points: list[dict[str, Any]],
    output_dir: Path,
    gdal_translate: str,
) -> dict[str, Any]:
    cache_key = auto_prepare_cache_key(source=source, canyon=canyon, points=points)
    if cache_key is None:
        return auto_prepare_source(
            source=source,
            canyon=canyon,
            points=points,
            output_dir=output_dir,
            gdal_translate=gdal_translate,
        )

    with AUTOPREPARE_CACHE_LOCK:
        cached = AUTOPREPARE_CACHE.get(cache_key)
    if cached is not None:
        if cached["status"] == "ok":
            return copy.deepcopy(cached["source"])
        raise RuntimeError(str(cached["error"]))

    try:
        prepared = auto_prepare_source(
            source=source,
            canyon=canyon,
            points=points,
            output_dir=output_dir,
            gdal_translate=gdal_translate,
        )
    except Exception as exc:
        with AUTOPREPARE_CACHE_LOCK:
            AUTOPREPARE_CACHE[cache_key] = {"status": "error", "error": f"{type(exc).__name__}: {exc}"}
        raise

    with AUTOPREPARE_CACHE_LOCK:
        AUTOPREPARE_CACHE[cache_key] = {"status": "ok", "source": copy.deepcopy(prepared)}
    return prepared


def slovenia_quadrant_file_ids(points: list[dict[str, Any]]) -> list[int]:
    avg_lat = sum(float(point["latitude"]) for point in points) / len(points)
    avg_lon = sum(float(point["longitude"]) for point in points) / len(points)
    if avg_lat >= 46.2:
        return [469] if avg_lon < 14.2 else [518]
    return [517] if avg_lon < 14.2 else [516]


def planned_source_sort_key(canyon: dict[str, Any], points: list[dict[str, Any]], sources: list[dict[str, Any]]) -> tuple[Any, ...]:
    for source in sources:
        if not canyon_matches(canyon, source.get("match", {})):
            continue
        provider = (source.get("autoPrepare") or {}).get("provider")
        if provider == "ign":
            return (0, str(canyon.get("departement") or ""), int(canyon["id"]))
        if provider == "copernicus":
            return (1, ",".join(points_copernicus_cells(points)), int(canyon["id"]))
        if provider == "merit-ihu-global":
            return (2, source.get("name", ""), int(canyon["id"]))
        return (3, source.get("name", ""), int(canyon["id"]))
    return (9, int(canyon["id"]))


def auto_prepare_source(
    *,
    source: dict[str, Any],
    canyon: dict[str, Any],
    points: list[dict[str, Any]],
    output_dir: Path,
    gdal_translate: str,
) -> dict[str, Any]:
    auto_prepare = source.get("autoPrepare")
    if not auto_prepare:
        return source

    provider = auto_prepare.get("provider")
    if provider == "ign":
        manifest_path = Path(auto_prepare.get("manifest", "build/watersheds/ign-plan/ign_download_manifest.json"))
        if not manifest_path.exists():
            subprocess.run(
                [sys.executable, "scripts/watersheds/sources/fetch_ign_alti_catalog.py"],
                check=True,
            )
            subprocess.run(
                [sys.executable, "scripts/watersheds/sources/plan_ign_downloads.py"],
                check=True,
            )
        subprocess.run(
            [
                sys.executable,
                "scripts/watersheds/sources/prepare_ign_department_dem.py",
                "--manifest",
                str(manifest_path),
                "--dataset",
                auto_prepare.get("dataset", "rgealti5m"),
                "--department",
                ign_prepare_department(source, canyon),
                "--output-dir",
                auto_prepare.get("outputDir", "build/watersheds/ign-data"),
            ],
            check=True,
        )
        return source

    if provider == "copernicus":
        command = [
            sys.executable,
            "scripts/watersheds/sources/prepare_copernicus_dem.py",
            "--output-dir",
            auto_prepare.get("outputDir", "build/watersheds/copernicus-data"),
        ]
        for cell in points_copernicus_cells(points):
            command.extend(["--cell", cell])
        subprocess.run(command, check=True)
        selected = load_json_if_exists(Path(auto_prepare.get("outputDir", "build/watersheds/copernicus-data")) / "downloaded_cells.json") or {}
        prepared = dict(source)
        prepared["coverageIncomplete"] = bool(selected.get("missing"))
        return prepared

    if provider == "switzerland-stac":
        local_output_dir = output_dir / "prepared_sources" / f"switzerland-{canyon['id']}"
        command = [
            sys.executable,
            "scripts/watersheds/sources/prepare_switzerland_dem.py",
            "--output-dir",
            str(local_output_dir),
            "--cache-dir",
            str(Path(auto_prepare.get("outputDir", "build/watersheds/switzerland-national-dem")) / "raw"),
            "--buffer-km",
            str(auto_prepare.get("bufferKm", source.get("bufferKm", 10.0))),
        ]
        for point in points:
            command.extend(["--point", f"{point['latitude']},{point['longitude']}"])
        subprocess.run(command, check=True)
        prepared = dict(source)
        prepared["dem"] = str(local_output_dir / "vrt" / "_all_downloaded.vrt")
        prepared["preparedDynamically"] = True
        return prepared

    if provider == "spain-wcs":
        local_output_dir = output_dir / "prepared_sources" / f"spain-{canyon['id']}"
        command = [
            sys.executable,
            "scripts/watersheds/sources/prepare_spain_dem.py",
            "--output-dir",
            str(local_output_dir),
            "--buffer-km",
            str(auto_prepare.get("bufferKm", source.get("bufferKm", 20.0))),
        ]
        for point in points:
            command.extend(["--point", f"{point['latitude']},{point['longitude']}"])
        subprocess.run(command, check=True)
        prepared = dict(source)
        prepared["dem"] = str(local_output_dir / "raw" / "spain_4258_25m.tif")
        prepared["preparedDynamically"] = True
        return prepared

    if provider == "austria-als":
        local_output_dir = output_dir / "prepared_sources" / f"austria-{canyon['id']}"
        command = [
            sys.executable,
            "scripts/watersheds/sources/prepare_austria_dem.py",
            "--output-dir",
            str(local_output_dir),
            "--cache-dir",
            str(Path(auto_prepare.get("outputDir", "build/watersheds/austria-national-dem")) / "raw"),
            "--buffer-km",
            str(auto_prepare.get("bufferKm", source.get("bufferKm", 20.0))),
        ]
        for point in points:
            command.extend(["--point", f"{point['latitude']},{point['longitude']}"])
        subprocess.run(command, check=True)
        selected_tiles = load_json_if_exists(local_output_dir / "selected_tiles.json") or {}
        prepared = dict(source)
        prepared["dem"] = str(local_output_dir / "vrt" / "_all_downloaded.vrt")
        prepared["preparedDynamically"] = True
        prepared["coverageIncomplete"] = int(selected_tiles.get("missingTileCount") or 0) > 0
        return prepared

    if provider == "slovenia-jgp":
        local_output_dir = output_dir / "prepared_sources" / f"slovenia-{canyon['id']}"
        command = [
            sys.executable,
            "scripts/watersheds/sources/prepare_slovenia_dem.py",
            "--output-dir",
            str(local_output_dir),
            "--buffer-km",
            str(auto_prepare.get("bufferKm", source.get("bufferKm", 10.0))),
        ]
        for point in points:
            command.extend(["--point", f"{point['latitude']},{point['longitude']}"])
        for file_id in slovenia_quadrant_file_ids(points):
            command.extend(["--file-id", str(file_id)])
        subprocess.run(command, check=True)
        prepared = dict(source)
        prepared["dem"] = str(local_output_dir / "vrt" / "_all_downloaded.vrt")
        prepared["preparedDynamically"] = True
        return prepared

    if provider == "liguria-wcs":
        local_output_dir = output_dir / "prepared_sources" / f"liguria-{canyon['id']}"
        command = [
            sys.executable,
            "scripts/watersheds/sources/prepare_liguria_dem.py",
            "--output-dir",
            str(local_output_dir),
            "--buffer-km",
            str(auto_prepare.get("bufferKm", source.get("bufferKm", 20.0))),
        ]
        for point in points:
            command.extend(["--point", f"{point['latitude']},{point['longitude']}"])
        subprocess.run(command, check=True)
        prepared = dict(source)
        prepared["dem"] = str(local_output_dir / "raw" / "liguria_5m.tif")
        prepared["preparedDynamically"] = True
        return prepared

    if provider == "ign-wms":
        # Kept in the canyon work dir: a fresh extract per canyon, removed with the work files.
        local_output_dir = output_dir / "work" / str(canyon["id"]) / "ign-wms"
        command = [
            sys.executable,
            "scripts/watersheds/sources/prepare_ign_wms_dem.py",
            "--output-dir",
            str(local_output_dir),
            "--buffer-km",
            str(auto_prepare.get("bufferKm", source.get("bufferKm", 8.0))),
            "--crs",
            str(auto_prepare.get("crs", "EPSG:2154")),
            "--resolution-m",
            str(auto_prepare.get("resolutionM", 5.0)),
        ]
        for point in points:
            command.extend(["--point", f"{point['latitude']},{point['longitude']}"])
        subprocess.run(command, check=True)
        prepared = dict(source)
        prepared["dem"] = str(local_output_dir / "raw" / "ign_wms_dem.tif")
        prepared["preparedDynamically"] = True
        return prepared

    if provider == "madeira-wcs":
        local_output_dir = output_dir / "prepared_sources" / f"madeira-{canyon['id']}"
        command = [
            sys.executable,
            "scripts/watersheds/sources/prepare_madeira_dem.py",
            "--output-dir",
            str(local_output_dir),
            "--buffer-km",
            str(auto_prepare.get("bufferKm", source.get("bufferKm", 10.0))),
        ]
        for point in points:
            command.extend(["--point", f"{point['latitude']},{point['longitude']}"])
        subprocess.run(command, check=True)
        prepared = dict(source)
        prepared["dem"] = str(local_output_dir / "raw" / "madeira_5m.tif")
        prepared["preparedDynamically"] = True
        return prepared

    if provider == "tinitaly-bulk":
        command = [
            sys.executable,
            "scripts/watersheds/sources/prepare_tinitaly_dem.py",
            "--output-dir",
            auto_prepare.get("outputDir", "build/watersheds/italy-national-dem"),
        ]
        subprocess.run(command, check=True)
        return source

    if provider == "national-dem":
        units: list[str] = []
        for field_name in auto_prepare.get("unitFields", ["departement", "region"]):
            value = canyon.get(field_name)
            if value:
                units.append(str(value))
        for value in auto_prepare.get("extraUnits", []):
            units.append(str(value))
        units = [unit for unit in units if unit]
        if not units:
            raise SystemExit(f"No units resolved for national DEM source {source['name']}")
        command = [
            sys.executable,
            "scripts/watersheds/sources/prepare_national_dem.py",
            "--manifest",
            auto_prepare.get("manifest"),
            "--output-dir",
            auto_prepare.get("outputDir"),
        ]
        for unit in units:
            command.extend(["--unit", unit])
        subprocess.run(command, check=True)
        return source

    if provider == "merit-ihu-global":
        output_dir_path = auto_prepare.get("outputDir", "build/watersheds/merit-ihu-global")
        command = [
            sys.executable,
            "scripts/watersheds/sources/prepare_merit_ihu_global.py",
            "--output-dir",
            output_dir_path,
        ]
        subprocess.run(command, check=True)
        prepared = dict(source)
        prepared["upaRaster"] = str(Path(output_dir_path) / "raw" / "30sec_uparea.tif")
        prepared["flowdirRaster"] = str(Path(output_dir_path) / "raw" / "30sec_flwdir.tif")
        prepared["elevationRaster"] = str(Path(output_dir_path) / "raw" / "30sec_elevtn.tif")
        return prepared

    raise SystemExit(f"Unsupported autoPrepare provider: {provider}")


def bootstrap_ign_manifest(manifest_path: Path) -> None:
    if manifest_path.exists():
        return
    subprocess.run([sys.executable, "scripts/watersheds/sources/fetch_ign_alti_catalog.py"], check=True)
    subprocess.run([sys.executable, "scripts/watersheds/sources/plan_ign_downloads.py"], check=True)


def prepare_all_france_ign_sources(canyon_ids: list[int], canyons: dict[int, dict[str, Any]], output_dir: Path) -> None:
    manifest_path = Path("build/watersheds/ign-plan/ign_download_manifest.json")
    bootstrap_ign_manifest(manifest_path)
    manifest = load_json(manifest_path)
    by_department = {item["department"]: item for item in manifest}
    departments = sorted(
        {
            canyons[canyon_id].get("departement")
            for canyon_id in canyon_ids
            if canyons[canyon_id].get("pays") == "France"
            and canyons[canyon_id].get("departement")
            and "," not in str(canyons[canyon_id].get("departement"))
        }
    )

    for department in departments:
        item = by_department.get(department)
        if not item:
            continue
        for dataset_field, dataset_name in (("rgeAlti5m", "rgealti5m"), ("bdAlti", "bdalti")):
            if not item.get(dataset_field):
                continue
            subprocess.run(
                [
                    sys.executable,
                    "scripts/watersheds/sources/prepare_ign_department_dem.py",
                    "--manifest",
                    str(manifest_path),
                    "--dataset",
                    dataset_name,
                    "--department",
                    department,
                    "--output-dir",
                    "build/watersheds/ign-data",
                ],
                check=True,
            )


def resolve_source_for_canyon(
    *,
    canyon: dict[str, Any],
    points: list[dict[str, Any]],
    sources: list[dict[str, Any]],
    output_dir: Path,
    gdal_translate: str,
    gdal_warp: str,
) -> tuple[dict[str, Any] | None, list[dict[str, Any]]]:
    attempts: list[dict[str, Any]] = []
    for source in sources:
        if not canyon_matches(canyon, source.get("match", {})):
            continue
        provider = (source.get("autoPrepare") or {}).get("provider")
        force_prepare = bool((source.get("autoPrepare") or {}).get("alwaysPrepare", False)) or provider in DYNAMIC_AUTOPREPARE_PROVIDERS
        attempt = {
            "sourceName": source.get("name"),
            "provider": provider,
            "matched": True,
            "availableBefore": source_is_available(source),
            "forcePrepare": force_prepare,
        }
        resolved_source = source
        if force_prepare or not source_is_available(source):
            try:
                resolved_source = auto_prepare_source_cached(
                    source=source,
                    canyon=canyon,
                    points=points,
                    output_dir=output_dir,
                    gdal_translate=gdal_translate,
                )
            except Exception as exc:
                attempt["prepareError"] = f"{type(exc).__name__}: {exc}"
                attempts.append(attempt)
                continue
        attempt["availableAfter"] = source_is_available(resolved_source)
        if resolved_source.get("coverageIncomplete") and source.get("supplementProviders"):
            merged_dem_paths = [normalized_path(resolved_source["dem"]).resolve()]
            attempt["coverageIncomplete"] = True
            attempt["supplementsTried"] = []
            for supplement_provider in source.get("supplementProviders", []):
                supplement_source = next(
                    (
                        candidate
                        for candidate in sources
                        if (candidate.get("autoPrepare") or {}).get("provider") == supplement_provider
                    ),
                    None,
                )
                if supplement_source is None:
                    attempt["supplementsTried"].append({"provider": supplement_provider, "status": "missing_config"})
                    continue
                try:
                    prepared_supplement = auto_prepare_source_cached(
                        source=supplement_source,
                        canyon=canyon,
                        points=points,
                        output_dir=output_dir,
                        gdal_translate=gdal_translate,
                    )
                    supplement_dem = normalized_path(prepared_supplement["dem"]).resolve()
                    if supplement_dem.exists():
                        merged_dem_paths.append(supplement_dem)
                        attempt["supplementsTried"].append({"provider": supplement_provider, "status": "added", "dem": str(supplement_dem)})
                    else:
                        attempt["supplementsTried"].append({"provider": supplement_provider, "status": "missing_dem"})
                except Exception as exc:
                    attempt["supplementsTried"].append({"provider": supplement_provider, "status": "error", "error": f"{type(exc).__name__}: {exc}"})

            if len(merged_dem_paths) > 1:
                merged_path = output_dir / "prepared_sources" / f"merged-{source.get('name','source')}-{canyon['id']}" / "merged_dem.tif"
                merge_dem_paths(
                    dem_paths=merged_dem_paths,
                    target_path=merged_path,
                    target_srs=str(source.get("srs") or supplement_source.get("srs") or "EPSG:4326"),
                    points=points,
                    buffer_km=float(source.get("bufferKm", 20.0)),
                    resolution_m=float(source.get("processingResolutionM") or 10.0),
                    gdal_warp=gdal_warp,
                )
                resolved_source = dict(resolved_source)
                resolved_source["dem"] = str(merged_path)
                resolved_source["skipClipIfLocalDem"] = True
                resolved_source["processingResolutionM"] = None
                resolved_source["coverageIncomplete"] = False
                attempt["mergedDem"] = str(merged_path)
        attempts.append(attempt)
        if source_is_available(resolved_source):
            return resolved_source, attempts
    return None, attempts


def choose_source(canyon: dict[str, Any], sources: list[dict[str, Any]]) -> dict[str, Any] | None:
    for source in sources:
        if canyon_matches(canyon, source.get("match", {})) and source_is_available(source):
            return source
    return None


def all_canyon_points(geo_points: list[dict[str, Any]], point_types: set[str]) -> dict[int, list[dict[str, Any]]]:
    by_canyon: dict[int, list[dict[str, Any]]] = {}
    for point in geo_points:
        if point.get("type") not in point_types:
            continue
        by_canyon.setdefault(int(point["canyonId"]), []).append(point)
    return by_canyon


def ensure_local_hydrology(
    *,
    source: dict[str, Any],
    canyon_id: int,
    output_dir: Path,
    points: list[dict[str, Any]],
    gdal_translate: str,
    force_rebuild: bool = False,
) -> tuple[dict[str, Path], dict[str, Any]]:
    from run_local_ign_workflow import clip_dem, reproject_dem, resample_dem

    dem_path = normalized_path(source["dem"]).resolve()
    if not dem_path.exists():
        raise SystemExit(f"DEM source not found: {dem_path}")

    canyon_work_dir = output_dir / "work" / str(canyon_id)
    clip_path = canyon_work_dir / "clip_dem.tif"
    projected_clip_path = canyon_work_dir / "clip_dem_projected.tif"
    processing_clip_path = canyon_work_dir / "clip_dem_processing.tif"
    hydrology_dir = canyon_work_dir / "hydrology"
    upa_path = hydrology_dir / "ign_upstream_area_km2.tif"
    flowdir_path = hydrology_dir / "ign_d8_pointer_esri.tif"
    elevation_path = hydrology_dir / "ign_breached_dem.tif"

    timings = {
        "clipDemSec": 0.0,
        "resampleDemSec": 0.0,
        "deriveHydrologySec": 0.0,
    }
    processing_crs = effective_processing_crs(source, points)
    reused_hydrology = (not force_rebuild) and upa_path.exists() and flowdir_path.exists() and elevation_path.exists()

    if not reused_hydrology:
        shutil.rmtree(hydrology_dir, ignore_errors=True)
        started = time.perf_counter()
        print(f"CLIP canyon {canyon_id} start", flush=True)
        clip_dem(
            dem_path,
            clip_path,
            [(float(point["latitude"]), float(point["longitude"])) for point in points],
            float(source.get("bufferKm", 20.0)) * 1000.0,
            source_srs=source.get("srs"),
        )
        timings["clipDemSec"] = time.perf_counter() - started
        print(f"CLIP canyon {canyon_id} done", flush=True)
        processing_dem = clip_path
        if processing_crs:
            started = time.perf_counter()
            print(f"REPROJECT canyon {canyon_id} start", flush=True)
            reproject_dem(clip_path, projected_clip_path, processing_crs)
            processing_dem = projected_clip_path
            timings["resampleDemSec"] = time.perf_counter() - started
            print(f"REPROJECT canyon {canyon_id} done", flush=True)
        if source.get("processingResolutionM"):
            started = time.perf_counter()
            print(f"RESAMPLE canyon {canyon_id} start", flush=True)
            resample_dem(processing_dem, processing_clip_path, float(source["processingResolutionM"]))
            processing_dem = processing_clip_path
            timings["resampleDemSec"] = time.perf_counter() - started
            print(f"RESAMPLE canyon {canyon_id} done", flush=True)
        command = [
            sys.executable,
            "scripts/watersheds/sources/derive_ign_hydrology.py",
            "--dem",
            str(processing_dem),
            "--output-dir",
            str(hydrology_dir),
            "--work-dir",
            str(hydrology_dir / "work"),
        ]
        if source.get("srs"):
            command.extend(["--srs", source["srs"]])
        started = time.perf_counter()
        print(f"HYDRO canyon {canyon_id} start", flush=True)
        subprocess.run(command, check=True)
        timings["deriveHydrologySec"] = time.perf_counter() - started
        print(f"HYDRO canyon {canyon_id} done", flush=True)

    return (
        {
            "upa": upa_path,
            "flowdir": flowdir_path,
            "elevation": elevation_path,
        },
        {
            "timingsSec": rounded_stage_values(timings),
            "reusedExistingHydrology": reused_hydrology,
            "processingCrs": processing_crs,
        },
    )


def ensure_worldcover(points: list[dict[str, Any]]) -> dict[str, Any]:
    if not points:
        return {"path": None, "elapsedSec": 0.0}
    output_dir = Path("build/watersheds/worldcover")
    deduped_points = {
        (round(float(point["latitude"]), 6), round(float(point["longitude"]), 6))
        for point in points
        if point.get("latitude") is not None and point.get("longitude") is not None
    }
    points_file = output_dir / "requested_points.txt"
    points_file.parent.mkdir(parents=True, exist_ok=True)
    points_file.write_text(
        "\n".join(f"{latitude},{longitude}" for latitude, longitude in sorted(deduped_points)) + "\n",
        encoding="utf-8",
    )
    command = [
        sys.executable,
        "scripts/watersheds/sources/prepare_worldcover.py",
        "--output-dir",
        str(output_dir),
        "--points-file",
        str(points_file),
    ]
    started = time.perf_counter()
    subprocess.run(command, check=True)
    elapsed = time.perf_counter() - started
    return {
        "path": str(output_dir / "vrt" / "_all_downloaded.vrt"),
        "elapsedSec": round(elapsed, 3),
    }


def ensure_worldclim_climate() -> dict[str, Any]:
    output_dir = Path("build/watersheds/worldclim-climate")
    command = [
        sys.executable,
        "scripts/watersheds/sources/prepare_worldclim_climate.py",
        "--output-dir",
        str(output_dir),
    ]
    started = time.perf_counter()
    subprocess.run(command, check=True)
    ready = load_json_if_exists(output_dir / "ready.json") or {}
    return {
        "monthly": ready.get("monthly", {}),
        "resolution": ready.get("resolution"),
        "elapsedSec": round(time.perf_counter() - started, 3),
    }


def ensure_hydrolakes() -> dict[str, Any]:
    output_dir = Path("build/watersheds/hydrolakes")
    command = [
        sys.executable,
        "scripts/watersheds/sources/prepare_hydrolakes.py",
        "--output-dir",
        str(output_dir),
    ]
    started = time.perf_counter()
    subprocess.run(command, check=True)
    ready = load_json_if_exists(output_dir / "ready.json") or {}
    return {
        "path": ready.get("shapefile"),
        "elapsedSec": round(time.perf_counter() - started, 3),
    }


def ensure_glim() -> dict[str, Any]:
    output_dir = Path("build/watersheds/glim")
    command = [
        sys.executable,
        "scripts/watersheds/sources/prepare_glim_lithology.py",
        "--output-dir",
        str(output_dir),
    ]
    started = time.perf_counter()
    subprocess.run(command, check=True)
    ready = load_json_if_exists(output_dir / "ready.json") or {}
    return {
        "path": ready.get("raster"),
        "classMap": ready.get("classMap", {}),
        "elapsedSec": round(time.perf_counter() - started, 3),
    }


def ensure_ghsl_built() -> dict[str, Any]:
    output_dir = Path("build/watersheds/ghsl-built")
    command = [
        sys.executable,
        "scripts/watersheds/sources/prepare_ghsl_built.py",
        "--output-dir",
        str(output_dir),
    ]
    started = time.perf_counter()
    subprocess.run(command, check=True)
    ready = load_json_if_exists(output_dir / "ready.json") or {}
    return {
        "path": ready.get("raster"),
        "elapsedSec": round(time.perf_counter() - started, 3),
    }


def ensure_gdw() -> dict[str, Any]:
    output_dir = Path("build/watersheds/gdw")
    command = [
        sys.executable,
        "scripts/watersheds/sources/prepare_gdw.py",
        "--output-dir",
        str(output_dir),
    ]
    started = time.perf_counter()
    subprocess.run(command, check=True)
    ready = load_json_if_exists(output_dir / "ready.json") or {}
    return {
        "barriers": ready.get("barriers"),
        "reservoirs": ready.get("reservoirs"),
        "elapsedSec": round(time.perf_counter() - started, 3),
    }


def ensure_rgi() -> dict[str, Any]:
    output_dir = Path("build/watersheds/rgi")
    command = [
        sys.executable,
        "scripts/watersheds/sources/prepare_rgi.py",
        "--output-dir",
        str(output_dir),
    ]
    started = time.perf_counter()
    subprocess.run(command, check=True)
    ready = load_json_if_exists(output_dir / "ready.json") or {}
    shapefiles = [
        path
        for path in ready.get("shapefiles", [])
        if isinstance(path, str) and "/00_rgi62_regions/" not in path.replace("\\", "/").lower()
    ]
    return {
        "shapefiles": shapefiles,
        "elapsedSec": round(time.perf_counter() - started, 3),
    }


def prepare_shared_descriptor_inputs(
    output_dir: Path,
    pending: list[tuple[int, dict[str, Any], list[dict[str, Any]]]],
) -> dict[str, Any]:
    shared: dict[str, Any] = {
        "climate": None,
        "worldcover": None,
        "ghsl": None,
        "hydrolakes": None,
        "gdw": None,
        "rgi": None,
        "glim": None,
        "errors": {},
    }
    shared_points = [point for _, _, points in pending for point in points]
    preparations: list[tuple[str, Any]] = [
        ("climate", ensure_worldclim_climate),
        ("ghsl", ensure_ghsl_built),
        ("hydrolakes", ensure_hydrolakes),
        ("gdw", ensure_gdw),
        ("rgi", ensure_rgi),
        ("glim", ensure_glim),
    ]
    if shared_points:
        preparations.append(("worldcover", lambda: ensure_worldcover(shared_points)))

    with concurrent.futures.ThreadPoolExecutor(max_workers=min(len(preparations), 6)) as executor:
        future_map = {executor.submit(loader): key for key, loader in preparations}
        for future in concurrent.futures.as_completed(future_map):
            key = future_map[future]
            try:
                shared[key] = future.result()
            except Exception as exc:
                shared["errors"][key] = f"{type(exc).__name__}: {exc}"
                print(f"WARN shared descriptor prepare failed for {key}: {type(exc).__name__}: {exc}", flush=True)
    write_json(output_dir / "global_prepare.json", shared)
    return shared


def evaluate_points_for_canyon(
    *,
    canyon: dict[str, Any],
    points: list[dict[str, Any]],
    source: dict[str, Any],
    raster_paths: dict[str, Path],
) -> list[dict[str, Any]]:
    upa_raster = create_raster(raster_paths["upa"], band_index=int(source.get("upaBand", 1)), name="upa")
    flowdir_raster = create_raster(raster_paths.get("flowdir"), band_index=int(source.get("flowdirBand", 1)), name="dir")
    elevation_raster = create_raster(raster_paths.get("elevation"), band_index=int(source.get("elevationBand", 1)), name="elv")
    try:
        results = []
        for point_index, point in enumerate(points, start=1):
            entry = EntryPoint(
                canyon_id=int(canyon["id"]),
                canyon_name=str(canyon.get("nomComplet") or canyon.get("nom") or canyon["id"]),
                entry_index=point_index,
                geo_point_index=point_index,
                latitude=float(point["latitude"]),
                longitude=float(point["longitude"]),
                label=point.get("label"),
            )
            review_snap_attempted_radii_m: list[float] | None = None
            review_snap_radius_m: float | None = None
            if is_review_hydrology_override(point):
                search_radius_cells = 0
                channel_min_upa_km2 = source.get("channelMinUpaKm2")
                review_snap_attempted_radii_m = []
                evaluated = None
                for snap_radius_m in review_snap_search_radii_m(point):
                    review_snap_attempted_radii_m.append(float(snap_radius_m))
                    evaluated = evaluate_entry(
                        entry,
                        upa_raster,
                        flowdir_raster,
                        elevation_raster,
                        search_radius_cells=search_radius_cells,
                        search_radius_m=float(snap_radius_m),
                        candidate_strategy="nearest_channel_strict",
                        channel_min_upa_km2=channel_min_upa_km2,
                    )
                    if evaluated.snapped_upa_km2 is not None:
                        review_snap_radius_m = float(snap_radius_m)
                        break
            else:
                search_radius_cells = int(source.get("searchRadiusCells", 2))
                search_radius_m = source.get("searchRadiusM")
                candidate_strategy = str(source.get("candidateStrategy", "max_upa"))
                channel_min_upa_km2 = source.get("channelMinUpaKm2")
                evaluated = evaluate_entry(
                    entry,
                    upa_raster,
                    flowdir_raster,
                    elevation_raster,
                    search_radius_cells=search_radius_cells,
                    search_radius_m=search_radius_m,
                    candidate_strategy=candidate_strategy,
                    channel_min_upa_km2=channel_min_upa_km2,
                )
            results.append(
                {
                    "pointType": point.get("type"),
                    "label": point.get("label"),
                    "latitude": point.get("latitude"),
                    "longitude": point.get("longitude"),
                    "forcedByReview": is_review_hydrology_override(point),
                    "reviewStatus": point.get("_reviewStatus"),
                    "reviewAdminPlaced": bool(point.get("_reviewAdminPlaced", False)),
                    "reviewSourceFile": point.get("_reviewSourceFile"),
                    "reviewSnapAttemptedRadiiM": review_snap_attempted_radii_m,
                    "reviewSnapRadiusM": review_snap_radius_m,
                    "evaluation": asdict(evaluated),
                }
            )
        return results
    finally:
        upa_raster.close()
        if flowdir_raster is not None:
            flowdir_raster.close()
        if elevation_raster is not None:
            elevation_raster.close()


def analyze_point_candidates(points: list[dict[str, Any]]) -> dict[str, Any]:
    review_truth_candidate = next((point for point in points if point.get("forcedByReview")), None)
    review_truth_candidate_is_valid = bool(
        review_truth_candidate
        and review_truth_candidate.get("evaluation", {}).get("snapped_upa_km2") is not None
    )
    valid = [point for point in points if point["evaluation"]["snapped_upa_km2"] is not None]
    if not valid:
        return {
            "points": points,
            "bestHydroProxyCandidate": review_truth_candidate if review_truth_candidate_is_valid else None,
            "suggestedCandidate": None,
            "reviewTruthCandidate": review_truth_candidate,
            "strictTopoCandidate": None,
            "analysisSummary": {
                "validCandidateCount": 0,
                "maxUpaKm2": None,
                "reviewTruthUsed": review_truth_candidate_is_valid,
                "reviewTruthStatus": review_truth_candidate.get("reviewStatus") if review_truth_candidate else None,
            },
        }

    max_upa = max(float(point["evaluation"]["snapped_upa_km2"]) for point in valid)
    analyzed_points = []

    for point in points:
        evaluation = point["evaluation"]
        snapped_upa = evaluation.get("snapped_upa_km2")
        snap_distance = evaluation.get("snap_distance_m")
        raw_to_snapped_ratio = evaluation.get("raw_to_snapped_upa_ratio")
        point_type = str(point.get("pointType") or "")

        verdict = "invalid"
        reasons: list[str] = []
        score = float("-inf")
        relative_upa_ratio = None

        if snapped_upa is not None:
            snapped_upa = float(snapped_upa)
            relative_upa_ratio = snapped_upa / max(max_upa, 1e-9)
            score = min(relative_upa_ratio, 1.25) * 100.0
            score += HYDRO_TYPE_BONUS.get(point_type, 0.0)

            if snap_distance is not None:
                score -= min(float(snap_distance) / 5.0, 40.0)
                if float(snap_distance) > 80.0:
                    reasons.append("long_snap_distance")
                elif float(snap_distance) > 30.0:
                    reasons.append("moderate_snap_distance")

            if raw_to_snapped_ratio is not None:
                ratio_value = float(raw_to_snapped_ratio)
                if ratio_value >= 100.0:
                    score -= 35.0
                    reasons.append("very_large_upa_jump_after_snap")
                elif ratio_value >= 20.0:
                    score -= 18.0
                    reasons.append("large_upa_jump_after_snap")
                elif ratio_value >= 10.0:
                    score -= 10.0
                    reasons.append("moderate_upa_jump_after_snap")

            if snapped_upa < 0.01:
                score -= 120.0
                reasons.append("tiny_absolute_upa")
            elif snapped_upa < 0.05:
                score -= 30.0
                reasons.append("small_absolute_upa")

            if relative_upa_ratio < 0.05:
                score -= 200.0
                reasons.append("upa_far_below_best_candidate")
            elif relative_upa_ratio < 0.20:
                score -= 60.0
                reasons.append("upa_much_lower_than_best_candidate")
            elif relative_upa_ratio < 0.50:
                score -= 15.0
                reasons.append("upa_lower_than_best_candidate")
            else:
                reasons.append("upa_close_to_best_candidate")

            if point_type == "PARKING_AMONT" and relative_upa_ratio < 0.20:
                score -= 30.0
                reasons.append("upstream_parking_unlikely_hydrologic_proxy")
            if point_type == "PARKING_AVAL" and relative_upa_ratio >= 0.75:
                reasons.append("downstream_parking_plausible_proxy")
            if point_type == "ENTREE":
                reasons.append("topo_entry_point")
            if point_type == "SORTIE":
                reasons.append("topo_exit_point")

            if "tiny_absolute_upa" in reasons or "upa_far_below_best_candidate" in reasons:
                verdict = "off_channel"
            elif any(reason in reasons for reason in ["very_large_upa_jump_after_snap", "large_upa_jump_after_snap"]) and (snap_distance or 0.0) > 50.0:
                verdict = "uncertain"
            elif relative_upa_ratio >= 0.75 and (snap_distance or 0.0) <= 80.0:
                verdict = "good_proxy"
            else:
                verdict = "possible_proxy"

        analysis = {
            "selectionScore": None if score == float("-inf") else round(score, 3),
            "selectionVerdict": verdict,
            "selectionReasons": reasons,
            "relativeUpaRatioToBest": None if relative_upa_ratio is None else round(relative_upa_ratio, 6),
        }
        analyzed_point = dict(point)
        analyzed_point["analysis"] = analysis
        analyzed_points.append(analyzed_point)

    ranked_valid = sorted(
        [point for point in analyzed_points if point["evaluation"]["snapped_upa_km2"] is not None],
        key=lambda point: (
            -(point["analysis"]["selectionScore"] or float("-inf")),
            point["evaluation"]["snap_distance_m"] if point["evaluation"]["snap_distance_m"] is not None else float("inf"),
            POINT_TYPE_PRIORITY.get(str(point["pointType"]), 99),
            -(point["evaluation"]["snapped_upa_km2"] or 0.0),
        ),
    )

    for rank, point in enumerate(ranked_valid, start=1):
        point["analysis"]["selectionRank"] = rank

    auto_ranked_valid = [point for point in ranked_valid if not point.get("forcedByReview")]
    algorithm_suggested_candidate = next(
        (point for point in auto_ranked_valid if point["analysis"]["selectionVerdict"] in {"good_proxy", "possible_proxy", "uncertain"}),
        auto_ranked_valid[0] if auto_ranked_valid else None,
    )
    strict_topo_candidate = min(
        ranked_valid,
        key=lambda point: (
            POINT_TYPE_PRIORITY.get(str(point["pointType"]), 99),
            point["analysis"]["selectionRank"],
        ),
    ) if ranked_valid else None

    if review_truth_candidate is not None:
        selected_candidate = review_truth_candidate if review_truth_candidate_is_valid else None
    else:
        selected_candidate = algorithm_suggested_candidate

    return {
        "points": analyzed_points,
        "bestHydroProxyCandidate": selected_candidate,
        "suggestedCandidate": algorithm_suggested_candidate,
        "reviewTruthCandidate": review_truth_candidate,
        "strictTopoCandidate": strict_topo_candidate,
        "analysisSummary": {
            "validCandidateCount": len(ranked_valid),
            "maxUpaKm2": round(max_upa, 6),
            "bestHydroProxyPointType": selected_candidate.get("pointType") if selected_candidate else None,
            "strictTopoPointType": strict_topo_candidate.get("pointType") if strict_topo_candidate else None,
            "algorithmSuggestedPointType": algorithm_suggested_candidate.get("pointType") if algorithm_suggested_candidate else None,
            "reviewTruthUsed": review_truth_candidate_is_valid,
            "reviewTruthStatus": review_truth_candidate.get("reviewStatus") if review_truth_candidate else None,
        },
    }


def review_metadata_for_points(points: list[dict[str, Any]]) -> dict[str, Any] | None:
    review_point = next((point for point in points if is_review_hydrology_override(point)), None)
    if review_point is None:
        return None
    return {
        "status": review_point.get("_reviewStatus"),
        "adminPlaced": bool(review_point.get("_reviewAdminPlaced", False)),
        "gps": {
            "latitude": float(review_point["latitude"]),
            "longitude": float(review_point["longitude"]),
        },
        "pointType": review_point.get("type"),
        "label": review_point.get("label"),
        "sourceFile": review_point.get("_reviewSourceFile"),
    }


def trace_flow_path(flow: np.ndarray, row: int, col: int) -> list[tuple[int, int]]:
    path: list[tuple[int, int]] = []
    seen: set[tuple[int, int]] = set()
    while 0 <= row < flow.shape[0] and 0 <= col < flow.shape[1] and (row, col) not in seen:
        seen.add((row, col))
        path.append((row, col))
        offset = FLOW_DIRECTION_OFFSETS.get(int(flow[row, col]))
        if offset is None:
            break
        row, col = row + offset[0], col + offset[1]
    return path


def entry_guided_candidate(
    *,
    canyon: dict[str, Any],
    points: list[dict[str, Any]],
    raster_paths: dict[str, Path],
) -> dict[str, Any] | None:
    """Outlet on the stream that flows through the canyon entry.

    The usual snap takes a channel cell near the exit point, which lands on a side gully when the point
    sits a few metres off the main stream, and the scoring prefers a downstream parking far from the
    canyon. Here the D8 path of each ENTREE point is followed downstream and the outlet is the path cell
    closest to the exit reference: the review point when one was placed by hand, else the SORTIE points,
    else the downstream parking. None when no path passes within ENTRY_GUIDED_MAX_OFFSET_M of it.
    Without any exit reference, the outlet is where the path has dropped by the canyon's denivele.
    """
    entries = [point for point in points if point.get("type") == "ENTREE"]
    review_point = next((point for point in points if is_review_hydrology_override(point)), None)
    references: list[dict[str, Any]] = [review_point] if review_point is not None else []
    for point_type in ENTRY_GUIDED_REFERENCE_TYPES:
        if references:
            break
        references = [point for point in points if point.get("type") == point_type]
    drop_m = canyon.get("denivele")
    drop_m = float(drop_m) if isinstance(drop_m, (int, float)) and drop_m > 0 else None
    if not entries or raster_paths.get("flowdir") is None or raster_paths.get("elevation") is None:
        return None
    if not references and drop_m is None:
        return None

    with rasterio.open(raster_paths["flowdir"]) as src:
        flow = src.read(1, masked=True).filled(0).astype(np.int16)
        raster_crs = src.crs
        raster_transform = src.transform
    with rasterio.open(raster_paths["upa"]) as src:
        upa = src.read(1, masked=True).astype(np.float64).filled(np.nan)
    with rasterio.open(raster_paths["elevation"]) as src:
        elevation = src.read(1, masked=True).astype(np.float64).filled(np.nan)

    ref_xs, ref_ys = transform(
        "EPSG:4326",
        raster_crs,
        [float(point["longitude"]) for point in references],
        [float(point["latitude"]) for point in references],
    ) if references else ([], [])
    entry_xs, entry_ys = transform(
        "EPSG:4326",
        raster_crs,
        [float(point["longitude"]) for point in entries],
        [float(point["latitude"]) for point in entries],
    )
    best: dict[str, Any] | None = None
    for entry, entry_x, entry_y in zip(entries, entry_xs, entry_ys):
        row, col = rasterio.transform.rowcol(raster_transform, entry_x, entry_y)
        path = trace_flow_path(flow, int(row), int(col))
        if len(path) < 2:
            continue
        path_xs, path_ys = rasterio.transform.xy(
            raster_transform,
            [cell[0] for cell in path],
            [cell[1] for cell in path],
        )
        path_xs = np.asarray(path_xs, dtype=np.float64)
        path_ys = np.asarray(path_ys, dtype=np.float64)
        if not references:
            path_elevations = np.array([elevation[cell] for cell in path])
            below = np.flatnonzero(path_elevations <= path_elevations[0] - drop_m)
            if below.size == 0:
                continue
            index = int(below[0])
            path_length_m = float(np.hypot(np.diff(path_xs[: index + 1]), np.diff(path_ys[: index + 1])).sum())
            length_m = canyon.get("longueur")
            max_length_m = max(3.0 * float(length_m), 3000.0) if isinstance(length_m, (int, float)) and length_m > 0 else 5000.0
            # The highest entry starts the canyon.
            if path_length_m > max_length_m or (best is not None and path_elevations[0] <= best["startElevationM"]):
                continue
            best = {
                "method": "entry_drop",
                "offsetM": 0.0,
                "entry": entry,
                "reference": entry,
                "referenceXY": (entry_x, entry_y),
                "path": path,
                "index": index,
                "distanceM": path_length_m,
                "startElevationM": float(path_elevations[0]),
            }
            continue
        for reference, ref_x, ref_y in zip(references, ref_xs, ref_ys):
            distances = np.hypot(path_xs - ref_x, path_ys - ref_y)
            offset_m = float(distances.min())
            if best is not None and offset_m >= best["offsetM"]:
                continue
            # At a confluence the closest cell can already be on the receiving river: take the first
            # cell about as close, then step back over an inflow that makes the area jump anywhere on
            # the stretch of path that is about as close (the jump can be a few cells upstream).
            index = int(np.argmax(distances <= offset_m + 10.0))
            cell = index
            while cell > 0 and distances[cell - 1] <= offset_m + 30.0:
                if upa[path[cell]] > 1.5 * upa[path[cell - 1]]:
                    index = cell - 1
                cell -= 1
            best = {
                "method": "exit_reference",
                "offsetM": offset_m,
                "entry": entry,
                "reference": reference,
                "referenceXY": (ref_x, ref_y),
                "path": path,
                "index": index,
                "distanceM": float(distances[index]),
            }
    if best is None or best["offsetM"] > ENTRY_GUIDED_MAX_OFFSET_M:
        return None

    row, col = best["path"][best["index"]]
    snapped_upa = float(upa[row, col])
    # A path that never reaches a channel runs down a slope, not along the canyon (coarse DEMs).
    if not math.isfinite(snapped_upa) or snapped_upa < ENTRY_GUIDED_MIN_UPA_KM2:
        return None
    cell_x, cell_y = rasterio.transform.xy(raster_transform, row, col)
    (snapped_longitude,), (snapped_latitude,) = transform(raster_crs, "EPSG:4326", [cell_x], [cell_y])
    reference = best["reference"]
    raw_row, raw_col = (int(value) for value in rasterio.transform.rowcol(raster_transform, *best["referenceXY"]))
    raw_upa = None
    if 0 <= raw_row < upa.shape[0] and 0 <= raw_col < upa.shape[1] and math.isfinite(float(upa[raw_row, raw_col])):
        raw_upa = float(upa[raw_row, raw_col])
    elevation_m = None
    if math.isfinite(float(elevation[row, col])):
        elevation_m = round(float(elevation[row, col]), 2)
    entry = best["entry"]
    return {
        "pointType": reference.get("type"),
        "label": reference.get("label"),
        "latitude": reference.get("latitude"),
        "longitude": reference.get("longitude"),
        "forcedByReview": is_review_hydrology_override(reference),
        "reviewStatus": reference.get("_reviewStatus"),
        "reviewAdminPlaced": bool(reference.get("_reviewAdminPlaced", False)),
        "reviewSourceFile": reference.get("_reviewSourceFile"),
        "reviewSnapAttemptedRadiiM": None,
        "reviewSnapRadiusM": None,
        "evaluation": {
            "canyon_id": int(canyon["id"]),
            "canyon_name": str(canyon.get("nomComplet") or canyon.get("nom") or canyon["id"]),
            "entry_index": 0,
            "geo_point_index": 0,
            "latitude": float(reference["latitude"]),
            "longitude": float(reference["longitude"]),
            "label": reference.get("label"),
            "label_hint": best["method"],
            "raw_upa_km2": raw_upa,
            "snapped_upa_km2": round(snapped_upa, 6),
            "raw_cell": {"row": raw_row, "col": raw_col},
            "snapped_cell": {"row": int(row), "col": int(col)},
            "snapped_latitude": round(float(snapped_latitude), 6),
            "snapped_longitude": round(float(snapped_longitude), 6),
            "snap_distance_m": round(best["distanceM"], 3),
            "pixel_size_m": round(abs(float(raster_transform.a)), 3),
            "candidate_count": len(best["path"]),
            "raw_to_snapped_upa_ratio": round(snapped_upa / raw_upa, 6) if raw_upa else None,
            "elevation_m": elevation_m,
            "flowdir_value": int(flow[row, col]),
            "status": "ok",
            "status_detail": None,
        },
        "analysis": {
            "selectionScore": None,
            "selectionVerdict": "entry_guided",
            "selectionReasons": ["outlet_on_entry_flow_path" if best["method"] == "exit_reference" else "outlet_after_canyon_drop"],
            "relativeUpaRatioToBest": None,
        },
        "entryGuided": {
            "method": best["method"],
            "entryLatitude": float(entry["latitude"]),
            "entryLongitude": float(entry["longitude"]),
            "entryLabel": entry.get("label"),
            "pathOffsetM": round(best["offsetM"], 3),
            "pathIndex": best["index"],
            "pathCellCount": len(best["path"]),
        },
    }


def watershed_max_elevation(mask_data: dict[str, Any], elevation_path: Path | None) -> float | None:
    if elevation_path is None:
        return None
    with rasterio.open(elevation_path) as src:
        elevation = src.read(1, masked=True).astype(np.float64).filled(np.nan)
    mask = mask_data.get("mask")
    if not isinstance(mask, np.ndarray) or mask.shape != elevation.shape or not mask.any():
        return None
    value = float(np.nanmax(np.where(mask, elevation, np.nan)))
    return round(value, 2) if math.isfinite(value) else None


def candidate_diagnostic(candidate: dict[str, Any] | None) -> dict[str, Any] | None:
    if not isinstance(candidate, dict):
        return None
    evaluation = candidate.get("evaluation") or {}
    analysis = candidate.get("analysis") or {}
    return {
        "pointType": candidate.get("pointType"),
        "label": candidate.get("label"),
        "latitude": candidate.get("latitude"),
        "longitude": candidate.get("longitude"),
        "forcedByReview": bool(candidate.get("forcedByReview")),
        "reviewStatus": candidate.get("reviewStatus"),
        "reviewAdminPlaced": bool(candidate.get("reviewAdminPlaced", False)),
        "reviewSnapAttemptedRadiiM": candidate.get("reviewSnapAttemptedRadiiM"),
        "reviewSnapRadiusM": candidate.get("reviewSnapRadiusM"),
        "status": evaluation.get("status"),
        "statusDetail": evaluation.get("status_detail"),
        "snappedLatitude": evaluation.get("snapped_latitude"),
        "snappedLongitude": evaluation.get("snapped_longitude"),
        "snappedUpaKm2": evaluation.get("snapped_upa_km2"),
        "rawUpaKm2": evaluation.get("raw_upa_km2"),
        "snapDistanceM": evaluation.get("snap_distance_m"),
        "candidateCount": evaluation.get("candidate_count"),
        "selectionVerdict": analysis.get("selectionVerdict"),
        "selectionScore": analysis.get("selectionScore"),
        "selectionReasons": analysis.get("selectionReasons"),
    }


def build_watershed_skip_details(
    *,
    selected_candidate: dict[str, Any] | None,
    suggested_candidate: dict[str, Any] | None,
    strict_topo_candidate: dict[str, Any] | None,
    review_truth_candidate: dict[str, Any] | None,
    candidate_analysis: dict[str, Any],
    source: dict[str, Any],
) -> dict[str, Any]:
    return {
        "sourceName": source.get("name"),
        "sourceMode": source.get("mode"),
        "analysisSummary": candidate_analysis.get("analysisSummary"),
        "selectedCandidate": candidate_diagnostic(selected_candidate),
        "suggestedCandidate": candidate_diagnostic(suggested_candidate),
        "strictTopoCandidate": candidate_diagnostic(strict_topo_candidate),
        "reviewTruthCandidate": candidate_diagnostic(review_truth_candidate),
    }


def apply_shared_prepare_errors(
    descriptors: dict[str, Any],
    shared_errors: dict[str, str],
) -> None:
    status_by_key = {
        "climate": "climateDescriptorStatus",
        "worldcover": "landCoverDescriptorStatus",
        "hydrolakes": "hydroLakesStatus",
        "gdw": "gdwStatus",
        "glim": "geologyDescriptorStatus",
        "ghsl": "imperviousDescriptorStatus",
        "rgi": "glacierDescriptorStatus",
    }
    for shared_key, status_key in status_by_key.items():
        error = shared_errors.get(shared_key)
        if not error:
            continue
        current = descriptors.get(status_key)
        if current in {None, "skipped"}:
            descriptors[status_key] = f"error:prepare_failed:{shared_key}"


def perpendicular_distance(point: tuple[float, float], start: tuple[float, float], end: tuple[float, float]) -> float:
    if start == end:
        return math.hypot(point[0] - start[0], point[1] - start[1])
    x0, y0 = point
    x1, y1 = start
    x2, y2 = end
    numerator = abs((y2 - y1) * x0 - (x2 - x1) * y0 + x2 * y1 - y2 * x1)
    denominator = math.hypot(y2 - y1, x2 - x1)
    return numerator / max(denominator, 1e-9)


def douglas_peucker(points: list[tuple[float, float]], tolerance: float) -> list[tuple[float, float]]:
    if len(points) <= 2:
        return points
    start = points[0]
    end = points[-1]
    max_distance = -1.0
    split_index = -1
    for index in range(1, len(points) - 1):
        distance = perpendicular_distance(points[index], start, end)
        if distance > max_distance:
            max_distance = distance
            split_index = index
    if max_distance <= tolerance or split_index < 0:
        return [start, end]
    left = douglas_peucker(points[: split_index + 1], tolerance)
    right = douglas_peucker(points[split_index:], tolerance)
    return left[:-1] + right


def simplify_ring(ring: list[list[float]], tolerance: float) -> list[list[float]]:
    if tolerance <= 0 or len(ring) <= 5:
        return ring
    closed_points = [(float(x), float(y)) for x, y in ring[:-1]] if ring[0] == ring[-1] else [(float(x), float(y)) for x, y in ring]
    simplified = douglas_peucker(closed_points, tolerance)
    if len(simplified) < 3:
        simplified = closed_points
    result = [[x, y] for x, y in simplified]
    if result[0] != result[-1]:
        result.append(result[0])
    if len(result) < 4:
        return ring
    return result


def simplify_projected_geometry(geometry: dict[str, Any], tolerance: float) -> dict[str, Any]:
    if tolerance <= 0:
        return geometry
    if geometry["type"] == "Polygon":
        return {
            "type": "Polygon",
            "coordinates": [simplify_ring(ring, tolerance) for ring in geometry["coordinates"]],
        }
    if geometry["type"] == "MultiPolygon":
        return {
            "type": "MultiPolygon",
            "coordinates": [
                [simplify_ring(ring, tolerance) for ring in polygon]
                for polygon in geometry["coordinates"]
            ],
        }
    return geometry


def geometry_bbox(geometry: dict[str, Any]) -> list[float]:
    coords: list[tuple[float, float]] = []

    def collect(value: Any) -> None:
        if isinstance(value, list) and value and isinstance(value[0], (int, float)) and len(value) >= 2:
            coords.append((float(value[0]), float(value[1])))
            return
        if isinstance(value, list):
            for item in value:
                collect(item)

    collect(geometry.get("coordinates", []))
    longitudes = [point[0] for point in coords]
    latitudes = [point[1] for point in coords]
    return [min(longitudes), min(latitudes), max(longitudes), max(latitudes)]


def geometry_vertex_count(geometry: dict[str, Any]) -> int:
    count = 0

    def collect(value: Any) -> None:
        nonlocal count
        if isinstance(value, list) and value and isinstance(value[0], (int, float)) and len(value) >= 2:
            count += 1
            return
        if isinstance(value, list):
            for item in value:
                collect(item)

    collect(geometry.get("coordinates", []))
    return count


def build_watershed_geometry(
    *,
    flowdir_path: Path,
    snapped_longitude: float,
    snapped_latitude: float,
    source_srs: str | None,
    simplify_tolerance: float,
) -> dict[str, Any] | None:
    with rasterio.open(flowdir_path) as src:
        raster_crs = src.crs or source_srs
        if raster_crs is None:
            raise SystemExit(f"Flow direction raster has no CRS: {flowdir_path}")

        xs, ys = rasterio.warp.transform("EPSG:4326", raster_crs, [snapped_longitude], [snapped_latitude])
        target_cell = src.index(xs[0], ys[0])
        flow = src.read(1, masked=True).filled(0).astype(np.int16)

        visited = np.zeros(flow.shape, dtype=np.uint8)
        queue: collections.deque[tuple[int, int]] = collections.deque()
        target_row, target_col = target_cell
        if target_row < 0 or target_row >= flow.shape[0] or target_col < 0 or target_col >= flow.shape[1]:
            return None

        visited[target_row, target_col] = 1
        queue.append((target_row, target_col))

        while queue:
            row, col = queue.popleft()
            for d_row in (-1, 0, 1):
                for d_col in (-1, 0, 1):
                    if d_row == 0 and d_col == 0:
                        continue
                    n_row = row + d_row
                    n_col = col + d_col
                    if n_row < 0 or n_row >= flow.shape[0] or n_col < 0 or n_col >= flow.shape[1]:
                        continue
                    if visited[n_row, n_col] == 1:
                        continue
                    direction_code = int(flow[n_row, n_col])
                    offset = FLOW_DIRECTION_OFFSETS.get(direction_code)
                    if offset is None:
                        continue
                    if n_row + offset[0] == row and n_col + offset[1] == col:
                        visited[n_row, n_col] = 1
                        queue.append((n_row, n_col))

        polygon_geometries = []
        for geometry, value in shapes(visited, mask=visited == 1, transform=src.transform):
            if int(value) != 1:
                continue
            simplified = simplify_projected_geometry(geometry, simplify_tolerance)
            polygon_geometries.append(transform_geom(raster_crs, "EPSG:4326", simplified, precision=6))

    if not polygon_geometries:
        return None

    polygons: list[Any] = []
    for geometry in polygon_geometries:
        if geometry["type"] == "Polygon":
            polygons.append(geometry["coordinates"])
        elif geometry["type"] == "MultiPolygon":
            polygons.extend(geometry["coordinates"])

    if not polygons:
        return None
    if len(polygons) == 1:
        return {"type": "Polygon", "coordinates": polygons[0]}
    return {"type": "MultiPolygon", "coordinates": polygons}


def aggregate_results(output_dir: Path) -> None:
    canyon_files = sorted((output_dir / "canyons").glob("*.json")) if (output_dir / "canyons").exists() else []
    canyon_results = [load_json(path) for path in canyon_files]
    write_json(output_dir / "all_canyon_point_catchments.json", canyon_results)

    import_rows = []
    watershed_rows = []
    watershed_features = []
    descriptor_rows = []
    status_counts: dict[str, int] = collections.Counter()
    watershed_skip_counts: dict[str, int] = collections.Counter()
    stage_totals: dict[str, float] = collections.defaultdict(float)
    stage_maxima: dict[str, float] = collections.defaultdict(float)
    profiled_canyons = 0
    for item in canyon_results:
        status_counts[str(item.get("status") or "ok")] += 1
        if item.get("watershedStatus") == "skipped" and item.get("watershedSkipReason"):
            watershed_skip_counts[str(item.get("watershedSkipReason"))] += 1
        profiling = item.get("profiling", {})
        stage_values = profiling.get("stagesSec", {})
        if stage_values:
            profiled_canyons += 1
            for key, value in stage_values.items():
                numeric_value = float(value or 0.0)
                stage_totals[key] += numeric_value
                stage_maxima[key] = max(stage_maxima[key], numeric_value)
        candidate = item.get("bestHydroProxyCandidate") or item.get("suggestedCandidate")
        if not candidate:
            continue
        import_rows.append(
            {
                "canyonId": item["canyonId"],
                "canyonName": item["canyonName"],
                "sourceName": item["sourceName"],
                "pointType": candidate["pointType"],
                "label": candidate.get("label"),
                "latitude": candidate.get("latitude"),
                "longitude": candidate.get("longitude"),
                "upstreamCatchmentAreaKm2": candidate["evaluation"]["snapped_upa_km2"],
                "snapDistanceM": candidate["evaluation"]["snap_distance_m"],
                "rawToSnappedUpaRatio": candidate["evaluation"]["raw_to_snapped_upa_ratio"],
                "selectionVerdict": candidate.get("analysis", {}).get("selectionVerdict"),
                "selectionScore": candidate.get("analysis", {}).get("selectionScore"),
                "strictTopoPointType": item.get("strictTopoCandidate", {}).get("pointType") if item.get("strictTopoCandidate") else None,
                "strictTopoUpstreamCatchmentAreaKm2": item.get("strictTopoCandidate", {}).get("evaluation", {}).get("snapped_upa_km2") if item.get("strictTopoCandidate") else None,
                "forcedByReview": item.get("forcedByReview", False),
            }
        )
        descriptors = item.get("descriptors")
        if descriptors:
            descriptor_rows.append(
                {
                    "canyonId": item["canyonId"],
                    "canyonName": item["canyonName"],
                    "sourceName": item["sourceName"],
                    "pointType": candidate["pointType"],
                    "forcedByReview": item.get("forcedByReview", False),
                    **descriptors,
                }
            )
        watershed = item.get("watershed")
        if watershed and watershed.get("geometry"):
            watershed_rows.append(
                {
                    "canyonId": item["canyonId"],
                    "canyonName": item["canyonName"],
                    "sourceName": item["sourceName"],
                    "bbox": watershed.get("bbox"),
                    "vertexCount": watershed.get("vertexCount"),
                    "selectedPointType": watershed.get("selectedPointType"),
                    "selectedPointLabel": watershed.get("selectedPointLabel"),
                    "upstreamCatchmentAreaKm2": watershed.get("selectedUpstreamCatchmentAreaKm2"),
                    "selectionVerdict": watershed.get("selectedSelectionVerdict"),
                    "geometry": watershed.get("geometry"),
                }
            )
            watershed_features.append(
                {
                    "type": "Feature",
                    "geometry": watershed.get("geometry"),
                    "properties": {
                        "canyonId": item["canyonId"],
                        "canyonName": item["canyonName"],
                        "sourceName": item["sourceName"],
                        "vertexCount": watershed.get("vertexCount"),
                        "upstreamCatchmentAreaKm2": watershed.get("selectedUpstreamCatchmentAreaKm2"),
                        "selectionVerdict": watershed.get("selectedSelectionVerdict"),
                    },
                }
            )
        elif item.get("watershedStatus") == "skipped":
            import_rows[-1]["watershedSkipReason"] = item.get("watershedSkipReason")
            if item.get("watershedSkipDetails"):
                import_rows[-1]["watershedSkipDetails"] = item.get("watershedSkipDetails")

    write_json(output_dir / "import_ready_catchments.json", import_rows)
    write_json(output_dir / "import_ready_watershed_descriptors.json", descriptor_rows)
    write_json(output_dir / "import_ready_watersheds.json", watershed_rows)
    write_json(
        output_dir / "watershed_polygons.geojson",
        {"type": "FeatureCollection", "features": watershed_features},
    )
    write_json(
        output_dir / "summary.json",
        {
            "canyonsWithResults": len(canyon_results),
            "importReadyRows": len(import_rows),
            "descriptorRows": len(descriptor_rows),
            "watershedPolygonRows": len(watershed_rows),
            "statusCounts": dict(status_counts),
            "watershedSkipCounts": dict(watershed_skip_counts),
            "profiling": {
                "profiledCanyons": profiled_canyons,
                "stageTotalsSec": rounded_stage_values(dict(stage_totals)),
                "stageAveragesSec": rounded_stage_values(
                    {
                        key: (value / profiled_canyons)
                        for key, value in stage_totals.items()
                    }
                ) if profiled_canyons else {},
                "stageMaxSec": rounded_stage_values(dict(stage_maxima)),
            },
        },
    )


def write_progress(output_dir: Path, *, processed: int, pending: int) -> None:
    canyon_count = len(list((output_dir / "canyons").glob("*.json"))) if (output_dir / "canyons").exists() else 0
    write_json(
        output_dir / "progress.json",
        {
            "processedThisRun": processed,
            "pendingThisRun": pending,
            "canyonResultFiles": canyon_count,
        },
    )


def rounded_stage_values(values: dict[str, float]) -> dict[str, float]:
    return {key: round(value, 3) for key, value in values.items()}


def process_single_canyon(
    *,
    canyon_id: int,
    canyon: dict[str, Any],
    points: list[dict[str, Any]],
    sources: list[dict[str, Any]],
    output_dir: Path,
    gdal_translate: str,
    gdal_warp: str,
    enable_osm_regulation: bool,
    keep_work: bool,
    shared_descriptor_inputs: dict[str, Any],
    entry_guided_outlet: bool = False,
    skip_descriptors: bool = False,
) -> str:
    canyon_file = output_dir / "canyons" / f"{canyon_id}.json"
    print(f"START canyon {canyon_id} {canyon.get('nomComplet') or canyon.get('nom')}", flush=True)
    total_started = time.perf_counter()
    stage_timings: dict[str, float] = {}

    started = time.perf_counter()
    source, source_attempts = resolve_source_for_canyon(
        canyon=canyon,
        points=points,
        sources=sources,
        output_dir=output_dir,
        gdal_translate=gdal_translate,
        gdal_warp=gdal_warp,
    )
    stage_timings["resolveSourceSec"] = time.perf_counter() - started
    if source is None:
        write_json(
            canyon_file,
            {
                "canyonId": canyon_id,
                "canyonName": canyon.get("nomComplet") or canyon.get("nom"),
                "status": "no_matching_source",
                "sourceAttempts": source_attempts,
                "profiling": {
                    "stagesSec": rounded_stage_values(
                        {
                            **stage_timings,
                            "totalSec": time.perf_counter() - total_started,
                        }
                    )
                },
            },
        )
        append_text(
            output_dir / "source_resolution.log",
            f"[{canyon_id}] {canyon.get('nomComplet') or canyon.get('nom')} -> no_matching_source | attempts={json.dumps(source_attempts, ensure_ascii=False)}\n",
        )
        print(f"DONE canyon {canyon_id} no_matching_source", flush=True)
        return "no_matching_source"

    if source["mode"] == "derive_local_hydrology":
        started = time.perf_counter()
        raster_paths, hydrology_profile = ensure_local_hydrology(
            source=source,
            canyon_id=canyon_id,
            output_dir=output_dir,
            points=points,
            gdal_translate=gdal_translate,
        )
        stage_timings["ensureLocalHydrologySec"] = time.perf_counter() - started
        for key, value in hydrology_profile.get("timingsSec", {}).items():
            stage_timings[key] = float(value)
    elif source["mode"] == "precomputed_hydrology":
        raster_paths = {
            "upa": normalized_path(source["upaRaster"]).resolve(),
            "flowdir": normalized_path(source["flowdirRaster"]).resolve() if source.get("flowdirRaster") else None,
            "elevation": normalized_path(source["elevationRaster"]).resolve() if source.get("elevationRaster") else None,
        }
        hydrology_profile = {"reusedExistingHydrology": True, "timingsSec": {}}
    else:
        raise SystemExit(f"Unsupported source mode: {source['mode']}")

    review_metadata = review_metadata_for_points(points)
    watershed = None
    descriptors = None
    osm_regulation = None
    watershed_status = "skipped"
    watershed_skip_reason = None
    watershed_skip_details = None
    mask_data = None
    analyzed_points = []
    selected_candidate = None
    suggested_candidate = None
    review_truth_candidate = None
    strict_topo_candidate = None
    candidate_analysis = {
        "analysisSummary": {},
        "points": [],
        "bestHydroProxyCandidate": None,
        "suggestedCandidate": None,
        "reviewTruthCandidate": None,
        "strictTopoCandidate": None,
    }
    watershed_edge_retry = None
    hydrology_resolution_strategy = {
        "initialSource": str(source.get("name") or ""),
        "initialBufferKm": float(source.get("bufferKm", 20.0)),
        "fallbacks": [],
    }

    max_edge_retries = 2 if source["mode"] == "derive_local_hydrology" else 0
    current_buffer_km = float(source.get("bufferKm", 20.0))
    for edge_retry_index in range(max_edge_retries + 1):
        started = time.perf_counter()
        point_results = evaluate_points_for_canyon(
            canyon=canyon,
            points=points,
            source=source,
            raster_paths=raster_paths,
        )
        stage_timings["evaluatePointsSec"] = stage_timings.get("evaluatePointsSec", 0.0) + (time.perf_counter() - started)
        started = time.perf_counter()
        candidate_analysis = analyze_point_candidates(point_results)
        stage_timings["analyzeCandidatesSec"] = stage_timings.get("analyzeCandidatesSec", 0.0) + (time.perf_counter() - started)
        if entry_guided_outlet and source["mode"] == "derive_local_hydrology":
            guided_candidate = entry_guided_candidate(canyon=canyon, points=points, raster_paths=raster_paths)
            candidate_analysis["analysisSummary"]["entryGuidedUsed"] = guided_candidate is not None
            if guided_candidate is not None:
                candidate_analysis["bestHydroProxyCandidate"] = guided_candidate
                candidate_analysis["analysisSummary"]["bestHydroProxyPointType"] = guided_candidate["pointType"]
        analyzed_points = candidate_analysis["points"]
        selected_candidate = candidate_analysis["bestHydroProxyCandidate"]
        suggested_candidate = candidate_analysis.get("suggestedCandidate")
        review_truth_candidate = candidate_analysis.get("reviewTruthCandidate")
        strict_topo_candidate = candidate_analysis["strictTopoCandidate"]

        if not (
            selected_candidate is not None
            and source["mode"] == "derive_local_hydrology"
            and raster_paths.get("flowdir") is not None
            and selected_candidate["evaluation"].get("snapped_longitude") is not None
            and selected_candidate["evaluation"].get("snapped_latitude") is not None
        ):
            mask_data = None
            break

        print(f"WATERSHED canyon {canyon_id} start", flush=True)
        started = time.perf_counter()
        mask_data = build_watershed_mask_data(
            flowdir_path=raster_paths["flowdir"],
            snapped_longitude=float(selected_candidate["evaluation"]["snapped_longitude"]),
            snapped_latitude=float(selected_candidate["evaluation"]["snapped_latitude"]),
            source_srs=source.get("srs"),
        )
        stage_timings["buildWatershedMaskSec"] = stage_timings.get("buildWatershedMaskSec", 0.0) + (time.perf_counter() - started)
        touches_edge = watershed_mask_touches_raster_edge(mask_data)
        if not touches_edge or edge_retry_index >= max_edge_retries:
            watershed_edge_retry = {
                "attempts": edge_retry_index,
                "finalBufferKm": current_buffer_km,
                "touchesRasterEdge": touches_edge,
            }
            break

        next_buffer_km = min(max(current_buffer_km * 2.0, current_buffer_km + 10.0), 80.0)
        if next_buffer_km <= current_buffer_km:
            watershed_edge_retry = {
                "attempts": edge_retry_index,
                "finalBufferKm": current_buffer_km,
                "touchesRasterEdge": touches_edge,
            }
            break

        print(
            f"RETRY canyon {canyon_id} watershed touches raster edge with buffer={current_buffer_km}km; retrying with {next_buffer_km}km",
            flush=True,
        )
        current_buffer_km = next_buffer_km
        source, source_switch = choose_retry_hydrology_source(
            source=source,
            sources=sources,
            next_buffer_km=current_buffer_km,
        )
        if source_switch is not None:
            hydrology_resolution_strategy["fallbacks"].append(source_switch)
            print(
                f"FALLBACK canyon {canyon_id} hydrology source {source_switch['fromSource']} -> {source_switch['toSource']} at buffer={current_buffer_km}km",
                flush=True,
            )
        if source.get("autoPrepare"):
            source = auto_prepare_source_cached(
                source=source,
                canyon=canyon,
                points=points,
                output_dir=output_dir,
                gdal_translate=gdal_translate,
            )
        started = time.perf_counter()
        raster_paths, hydrology_profile = ensure_local_hydrology(
            source=source,
            canyon_id=canyon_id,
            output_dir=output_dir,
            points=points,
            gdal_translate=gdal_translate,
            force_rebuild=True,
        )
        stage_timings["ensureLocalHydrologySec"] = stage_timings.get("ensureLocalHydrologySec", 0.0) + (time.perf_counter() - started)
        for key, value in hydrology_profile.get("timingsSec", {}).items():
            stage_timings[key] = stage_timings.get(key, 0.0) + float(value)

    hydrology_resolution_strategy["finalSource"] = str(source.get("name") or "")
    hydrology_resolution_strategy["finalBufferKm"] = float(current_buffer_km)
    hydrology_resolution_strategy["fallbackUsed"] = bool(hydrology_resolution_strategy["fallbacks"])

    if (
        selected_candidate is not None
        and source["mode"] == "derive_local_hydrology"
        and raster_paths.get("flowdir") is not None
        and selected_candidate["evaluation"].get("snapped_longitude") is not None
        and selected_candidate["evaluation"].get("snapped_latitude") is not None
    ):
        geometry = None
        if mask_data is not None:
            started = time.perf_counter()
            geometry = mask_to_geometry(mask_data, float(source.get("watershedSimplifyToleranceM", 10.0)))
            stage_timings["buildWatershedGeometrySec"] = time.perf_counter() - started
        else:
            stage_timings["buildWatershedGeometrySec"] = 0.0

        if geometry is not None:
            watershed = {
                "geometry": geometry,
                "bbox": geometry_bbox(geometry),
                "vertexCount": geometry_vertex_count(geometry),
                "selectedPointType": selected_candidate["pointType"],
                "selectedPointLabel": selected_candidate.get("label"),
                "selectedUpstreamCatchmentAreaKm2": selected_candidate["evaluation"].get("snapped_upa_km2"),
                "selectedSelectionVerdict": selected_candidate.get("analysis", {}).get("selectionVerdict"),
                "simplifyToleranceM": float(source.get("watershedSimplifyToleranceM", 10.0)),
                "basinMaxElevationM": watershed_max_elevation(mask_data, raster_paths.get("elevation")),
            }
            watershed_status = "built"
        else:
            watershed_status = "skipped"
            watershed_skip_reason = "empty_geometry"
        print(f"WATERSHED canyon {canyon_id} done", flush=True)
    else:
        stage_timings["buildWatershedMaskSec"] = 0.0
        stage_timings["buildWatershedGeometrySec"] = 0.0
        if selected_candidate is None:
            watershed_skip_reason = (
                "review_override_no_channel_within_30m"
                if review_truth_candidate is not None
                else "no_selected_candidate"
            )
        elif source["mode"] != "derive_local_hydrology":
            watershed_skip_reason = "source_mode_not_local_hydrology"
        elif raster_paths.get("flowdir") is None:
            watershed_skip_reason = "missing_flowdir_raster"
        elif selected_candidate["evaluation"].get("snapped_longitude") is None or selected_candidate["evaluation"].get("snapped_latitude") is None:
            watershed_skip_reason = (
                "review_truth_missing_snapped_coordinates"
                if selected_candidate.get("forcedByReview")
                else "missing_snapped_coordinates"
            )
        else:
            watershed_skip_reason = "condition_not_met"
        watershed_skip_details = build_watershed_skip_details(
            selected_candidate=selected_candidate,
            suggested_candidate=suggested_candidate,
            strict_topo_candidate=strict_topo_candidate,
            review_truth_candidate=review_truth_candidate,
            candidate_analysis=candidate_analysis,
            source=source,
        )

    if (
        not skip_descriptors
        and mask_data is not None
        and raster_paths.get("elevation") is not None
        and selected_candidate is not None
    ):
        climate_info = shared_descriptor_inputs.get("climate")
        worldcover_info = shared_descriptor_inputs.get("worldcover")
        ghsl_info = shared_descriptor_inputs.get("ghsl")
        hydrolakes_info = shared_descriptor_inputs.get("hydrolakes")
        gdw_info = shared_descriptor_inputs.get("gdw")
        glim_info = shared_descriptor_inputs.get("glim")
        rgi_info = shared_descriptor_inputs.get("rgi")
        stage_timings["prepareClimateSec"] = 0.0
        stage_timings["prepareWorldCoverSec"] = 0.0
        stage_timings["prepareGHSLBuiltSec"] = 0.0
        stage_timings["prepareHydroLakesSec"] = 0.0
        stage_timings["prepareGDWSec"] = 0.0
        stage_timings["prepareRGISec"] = 0.0
        stage_timings["prepareGLiMSec"] = 0.0
        started = time.perf_counter()
        descriptors = compute_watershed_descriptors(
            dem_path=str(raster_paths["elevation"]),
            uparea_path=str(raster_paths["upa"]),
            flowdir_path=str(raster_paths["flowdir"]),
            climate_monthly_paths=climate_info["monthly"] if climate_info else None,
            worldcover_path=worldcover_info["path"] if worldcover_info else None,
            ghsl_built_path=ghsl_info["path"] if ghsl_info else None,
            hydrolakes_path=hydrolakes_info["path"] if hydrolakes_info else None,
            gdw_barriers_path=gdw_info["barriers"] if gdw_info else None,
            gdw_reservoirs_path=gdw_info["reservoirs"] if gdw_info else None,
            glim_path=glim_info["path"] if glim_info else None,
            rgi_glacier_paths=rgi_info["shapefiles"] if rgi_info else None,
            watershed_geometry=watershed["geometry"] if watershed else None,
            mask_data=mask_data,
            selected_candidate=selected_candidate,
        )
        stage_timings["computeDescriptorsSec"] = time.perf_counter() - started

        if enable_osm_regulation and watershed and watershed.get("geometry") is not None:
            started = time.perf_counter()
            try:
                osm_probe = query_osm_regulation(
                    canyon_id=canyon_id,
                    canyon_name=canyon.get("nomComplet") or canyon.get("nom") or str(canyon_id),
                    country=str(canyon.get("pays") or ""),
                    watershed_geometry=watershed["geometry"],
                    cache_dir=output_dir / "osm_regulation_cache",
                )
                osm_regulation = osm_probe
                if osm_probe.get("status") == "ok":
                    descriptors.update(summarize_osm_regulation(osm_probe.get("matches", [])))
                else:
                    descriptors.update(
                        {
                            "osmRegulationStatus": osm_probe.get("status"),
                            "osmRegulationPresent": None,
                            "osmDamCountUpstream": None,
                            "osmWeirCountUpstream": None,
                            "osmReservoirCountUpstream": None,
                            "osmCanalCountUpstream": None,
                            "osmPenstockCountUpstream": None,
                            "osmHydropowerPlantCountUpstream": None,
                            "osmOperatorEdfCountUpstream": None,
                            "osmLikelyHydropowerScheme": None,
                            "osmRegulationConfidence": None,
                            "osmExampleNames": [],
                        }
                    )
            except Exception as exc:
                descriptors.update(
                    {
                        "osmRegulationStatus": f"error:{type(exc).__name__}",
                        "osmRegulationPresent": None,
                        "osmDamCountUpstream": None,
                        "osmWeirCountUpstream": None,
                        "osmReservoirCountUpstream": None,
                        "osmCanalCountUpstream": None,
                        "osmPenstockCountUpstream": None,
                        "osmHydropowerPlantCountUpstream": None,
                        "osmOperatorEdfCountUpstream": None,
                        "osmLikelyHydropowerScheme": None,
                        "osmRegulationConfidence": None,
                        "osmExampleNames": [],
                    }
                )
            stage_timings["computeOsmRegulationSec"] = time.perf_counter() - started
        else:
            stage_timings["computeOsmRegulationSec"] = 0.0

        if descriptors is not None:
            apply_shared_prepare_errors(descriptors, shared_descriptor_inputs.get("errors", {}))
            descriptors.update(_advanced_regulation_metrics(descriptors))
            descriptors.pop("hydroLakesNearestRegulationDistanceKm", None)
            descriptors.pop("gdwNearestRegulationDistanceKm", None)
    else:
        stage_timings["computeDescriptorsSec"] = 0.0
        stage_timings["computeOsmRegulationSec"] = 0.0

    stage_timings["totalSec"] = time.perf_counter() - total_started
    result = {
        "canyonId": canyon_id,
        "canyonName": canyon.get("nomComplet") or canyon.get("nom"),
        "country": canyon.get("pays"),
        "department": canyon.get("departement"),
        "sourceName": source["name"],
        "sourceMode": source["mode"],
        "points": analyzed_points,
        "analysisSummary": candidate_analysis["analysisSummary"],
        "bestHydroProxyCandidate": selected_candidate,
        "strictTopoCandidate": strict_topo_candidate,
        "suggestedCandidate": suggested_candidate,
        "reviewTruthCandidate": review_truth_candidate,
        "reviewOverride": review_metadata,
        "watershedEdgeRetry": watershed_edge_retry,
        "hydrologyResolutionStrategy": hydrology_resolution_strategy,
        "watershed": watershed,
        "descriptors": descriptors,
        "osmRegulationProbe": osm_regulation,
        "watershedStatus": watershed_status,
        "watershedSkipReason": watershed_skip_reason,
        "watershedSkipDetails": watershed_skip_details,
        "forcedByReview": any(is_review_hydrology_override(point) for point in points),
        "profiling": {
            "sourceMode": source["mode"],
            "reusedExistingHydrology": hydrology_profile.get("reusedExistingHydrology", False),
            "stagesSec": rounded_stage_values(stage_timings),
        },
    }
    started = time.perf_counter()
    write_json(canyon_file, result)
    stage_timings["writeResultSec"] = time.perf_counter() - started
    stage_timings["totalSec"] = time.perf_counter() - total_started
    result["profiling"]["stagesSec"] = rounded_stage_values(stage_timings)
    write_json(canyon_file, result)
    if not keep_work:
        shutil.rmtree(output_dir / "work" / str(canyon_id), ignore_errors=True)
    print(
        f"DONE canyon {canyon_id} ok total={result['profiling']['stagesSec']['totalSec']}s "
        f"hydro={result['profiling']['stagesSec'].get('ensureLocalHydrologySec', 0.0)}s "
        f"points={result['profiling']['stagesSec'].get('evaluatePointsSec', 0.0)}s "
        f"watershed={result['profiling']['stagesSec'].get('buildWatershedGeometrySec', 0.0)}s",
        flush=True,
    )
    return "ok"


def process_single_canyon_safe(
    *,
    canyon_id: int,
    canyon: dict[str, Any],
    points: list[dict[str, Any]],
    sources: list[dict[str, Any]],
    output_dir: Path,
    gdal_translate: str,
    gdal_warp: str,
    enable_osm_regulation: bool,
    keep_work: bool,
    shared_descriptor_inputs: dict[str, Any],
    entry_guided_outlet: bool = False,
    skip_descriptors: bool = False,
) -> str:
    try:
        return process_single_canyon(
            canyon_id=canyon_id,
            canyon=canyon,
            points=points,
            sources=sources,
            output_dir=output_dir,
            gdal_translate=gdal_translate,
            gdal_warp=gdal_warp,
            enable_osm_regulation=enable_osm_regulation,
            keep_work=keep_work,
            shared_descriptor_inputs=shared_descriptor_inputs,
            entry_guided_outlet=entry_guided_outlet,
            skip_descriptors=skip_descriptors,
        )
    except KeyboardInterrupt:
        raise
    except BaseException as exc:
        error_text = traceback.format_exc()
        error_payload = {
            "canyonId": canyon_id,
            "canyonName": canyon.get("nomComplet") or canyon.get("nom"),
            "country": canyon.get("pays"),
            "department": canyon.get("departement"),
            "status": "error",
            "error": {
                "type": type(exc).__name__,
                "message": str(exc),
                "traceback": error_text,
            },
            "availablePointTypes": sorted({str(point.get("type")) for point in points}),
        }
        write_json(output_dir / "canyons" / f"{canyon_id}.json", error_payload)
        append_text(
            output_dir / "errors.log",
            f"[{canyon_id}] {canyon.get('nomComplet') or canyon.get('nom')} - {type(exc).__name__}: {exc}\n{error_text}\n",
        )
        print(f"ERROR canyon {canyon_id} {type(exc).__name__}: {exc}", flush=True)
        if not keep_work:
            shutil.rmtree(output_dir / "work" / str(canyon_id), ignore_errors=True)
        return "error"


def main() -> int:
    global ENTRY_GUIDED_MIN_UPA_KM2
    args = parse_args()
    ENTRY_GUIDED_MIN_UPA_KM2 = args.entry_guided_min_upa_km2
    configure_runtime_caches()
    output_dir = args.output_dir.resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    gdal_translate = resolve_executable(args.gdal_translate, extra_candidates=[default_gdal_translate()])
    gdal_warp = resolve_executable(args.gdal_warp, extra_candidates=[default_gdalwarp()])

    config = load_json(args.source_config)
    sources = config["sources"]
    if args.france_only:
        sources = [
            source
            for source in sources
            if source.get("match", {}).get("pays") == "France"
        ]
    canyons = load_canyons(args.canyons_json)
    point_types = set(args.point_type)
    geo_points = load_json(args.geo_points_json)
    if args.extra_points_json:
        geo_points = [*geo_points, *load_json(args.extra_points_json)]
    points_by_canyon = all_canyon_points(geo_points, point_types)
    review_overrides: dict[int, dict[str, Any]] = {}
    if args.review_file and args.review_file.exists():
        review_overrides = load_review_overrides(args.review_file)
        points_by_canyon = apply_review_overrides(points_by_canyon, review_overrides, review_file=args.review_file)

    canyon_ids = sorted(points_by_canyon)
    countries = set(args.country or [])
    if args.france_only:
        countries.add("France")
    if countries:
        canyon_ids = [canyon_id for canyon_id in canyon_ids if (canyons.get(canyon_id, {}).get("pays") in countries)]
    if args.only_canyon_id:
        allowed = set(args.only_canyon_id)
        canyon_ids = [canyon_id for canyon_id in canyon_ids if canyon_id in allowed]
    if args.only_canyon_id_file:
        allowed_from_file = set(load_canyon_ids_from_file(args.only_canyon_id_file))
        canyon_ids = [canyon_id for canyon_id in canyon_ids if canyon_id in allowed_from_file]
    canyon_ids = sorted(
        canyon_ids,
        key=lambda canyon_id: planned_source_sort_key(
            canyons[canyon_id],
            points_by_canyon.get(canyon_id, []),
            sources,
        ),
    )
    if args.max_canyons is not None:
        canyon_ids = canyon_ids[: args.max_canyons]

    if args.france_only and args.prepare_france_ign_first:
        prepare_all_france_ign_sources(canyon_ids, canyons, output_dir)

    processed = 0
    try:
        pending = []
        for canyon_id in canyon_ids:
            canyon_file = output_dir / "canyons" / f"{canyon_id}.json"
            if should_skip_existing_canyon(canyon_file, args.skip_existing, review_overrides.get(canyon_id)):
                continue
            canyon = canyons.get(canyon_id)
            if canyon is None:
                continue
            points = points_by_canyon.get(canyon_id, [])
            if not points:
                continue
            pending.append((canyon_id, canyon, points))

        shared_descriptor_inputs = prepare_shared_descriptor_inputs(output_dir, pending) if pending and not args.skip_descriptors else {
            "climate": None,
            "worldcover": None,
            "ghsl": None,
            "hydrolakes": None,
            "gdw": None,
            "rgi": None,
            "glim": None,
            "errors": {},
        }

        if args.jobs <= 1:
            pending_total = len(pending)
            for canyon_id, canyon, points in pending:
                process_single_canyon_safe(
                    canyon_id=canyon_id,
                    canyon=canyon,
                    points=points,
                    sources=sources,
                    output_dir=output_dir,
                    gdal_translate=gdal_translate,
                    gdal_warp=gdal_warp,
                    enable_osm_regulation=args.enable_osm_regulation,
                    keep_work=args.keep_work,
                    shared_descriptor_inputs=shared_descriptor_inputs,
                    entry_guided_outlet=args.entry_guided_outlet,
                    skip_descriptors=args.skip_descriptors,
                )
                processed += 1
                write_progress(output_dir, processed=processed, pending=max(pending_total - processed, 0))
                if args.aggregate_every > 0 and processed % args.aggregate_every == 0:
                    aggregate_results(output_dir)
        else:
            executor_cls: type[concurrent.futures.Executor]
            executor_cls = (
                concurrent.futures.ProcessPoolExecutor
                if args.executor == "process"
                else concurrent.futures.ThreadPoolExecutor
            )
            broken_pool_exc: BrokenProcessPool | None = None
            remaining_after_broken_pool: list[tuple[int, dict[str, Any], list[dict[str, Any]]]] = []
            with executor_cls(max_workers=args.jobs) as executor:
                pending_total = len(pending)
                pending_iter = iter(pending)
                in_flight: dict[
                    concurrent.futures.Future[str],
                    tuple[int, dict[str, Any], list[dict[str, Any]]],
                ] = {}

                for _ in range(min(args.jobs, len(pending))):
                    canyon_id, canyon, points = next(pending_iter)
                    future = executor.submit(
                        process_single_canyon_safe,
                        canyon_id=canyon_id,
                        canyon=canyon,
                        points=points,
                        sources=sources,
                        output_dir=output_dir,
                        gdal_translate=gdal_translate,
                        gdal_warp=gdal_warp,
                        enable_osm_regulation=args.enable_osm_regulation,
                        keep_work=args.keep_work,
                        shared_descriptor_inputs=shared_descriptor_inputs,
                        entry_guided_outlet=args.entry_guided_outlet,
                        skip_descriptors=args.skip_descriptors,
                    )
                    in_flight[future] = (canyon_id, canyon, points)

                while in_flight:
                    done, _ = concurrent.futures.wait(
                        in_flight,
                        return_when=concurrent.futures.FIRST_COMPLETED,
                    )
                    for future in done:
                        try:
                            future.result()
                        except BrokenProcessPool as exc:
                            broken_pool_exc = exc
                            remaining_after_broken_pool = list(in_flight.values()) + list(pending_iter)
                            in_flight.clear()
                            break
                        processed += 1
                        write_progress(output_dir, processed=processed, pending=max(pending_total - processed, 0))
                        if args.aggregate_every > 0 and processed % args.aggregate_every == 0:
                            aggregate_results(output_dir)
                        in_flight.pop(future, None)
                        try:
                            canyon_id, canyon, points = next(pending_iter)
                        except StopIteration:
                            continue
                        new_future = executor.submit(
                            process_single_canyon_safe,
                            canyon_id=canyon_id,
                            canyon=canyon,
                            points=points,
                            sources=sources,
                            output_dir=output_dir,
                            gdal_translate=gdal_translate,
                            gdal_warp=gdal_warp,
                            enable_osm_regulation=args.enable_osm_regulation,
                            keep_work=args.keep_work,
                            shared_descriptor_inputs=shared_descriptor_inputs,
                            entry_guided_outlet=args.entry_guided_outlet,
                            skip_descriptors=args.skip_descriptors,
                        )
                        in_flight[new_future] = (canyon_id, canyon, points)
                    if broken_pool_exc is not None:
                        break
            if broken_pool_exc is not None:
                print(
                    f"WARN process executor broke ({broken_pool_exc}); falling back to sequential processing for {len(remaining_after_broken_pool)} canyon(s)",
                    flush=True,
                )
                for canyon_id, canyon, points in remaining_after_broken_pool:
                    process_single_canyon_safe(
                        canyon_id=canyon_id,
                        canyon=canyon,
                        points=points,
                        sources=sources,
                        output_dir=output_dir,
                        gdal_translate=gdal_translate,
                        gdal_warp=gdal_warp,
                        enable_osm_regulation=args.enable_osm_regulation,
                        keep_work=args.keep_work,
                        shared_descriptor_inputs=shared_descriptor_inputs,
                        entry_guided_outlet=args.entry_guided_outlet,
                        skip_descriptors=args.skip_descriptors,
                    )
                    processed += 1
                    write_progress(output_dir, processed=processed, pending=max(pending_total - processed, 0))
                    if args.aggregate_every > 0 and processed % args.aggregate_every == 0:
                        aggregate_results(output_dir)
    except KeyboardInterrupt:
        print("Interrupted cleanly. Resume with the same command to continue.")
    finally:
        aggregate_results(output_dir)

    print(json.dumps({"processedThisRun": processed}, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
