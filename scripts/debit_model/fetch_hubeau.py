"""Download French hydrometric data (Hub'Eau, free, no key) around the canyons.

1. referential of hydrometric sites (coordinates, upstream basin area, influence);
2. for every canyon, the gauged sites within ``--radius-km``;
3. validated daily mean discharge (QmnJ) of those sites since ``--start``.

Outputs (``--output-dir``): ``sites.json``, ``canyon_sites.json``, ``series/<code_site>.json``.
Resumable: series already downloaded are skipped.
"""
from __future__ import annotations

import argparse
import json
import math
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

API = "https://hubeau.eaufrance.fr/api/v2/hydrometrie"
POINT_PREFERENCE = ["ENTREE", "PARKING_AMONT", "SORTIE", "PARKING_AVAL", "POINT_REMARQUABLE"]


def get(url: str, params: dict | None = None, retries: int = 5) -> dict:
    full = url if params is None else f"{url}?{urllib.parse.urlencode(params)}"
    for attempt in range(retries):
        try:
            request = urllib.request.Request(full, headers={"User-Agent": "DescenteCanyonDebitModel/3"})
            with urllib.request.urlopen(request, timeout=120) as response:
                return json.loads(response.read().decode("utf-8"))
        except urllib.error.HTTPError as error:
            print(f"HTTP {error.code} {full[:140]}", flush=True)
            time.sleep(5 * (attempt + 1))
        except Exception as error:  # noqa: BLE001
            print(f"error {error!r} {full[:140]}", flush=True)
            time.sleep(5 * (attempt + 1))
    raise RuntimeError(full)


def paginate(url: str, params: dict) -> list[dict]:
    rows: list[dict] = []
    payload = get(url, params)
    rows.extend(payload.get("data") or [])
    while payload.get("next") and payload.get("data"):
        payload = get(payload["next"])
        rows.extend(payload.get("data") or [])
    return rows


def haversine_km(lat1, lon1, lat2, lon2) -> float:
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = p2 - p1, math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 6371.0 * 2 * math.asin(math.sqrt(a))


def canyon_points(geo_points_path: Path) -> dict[int, tuple[float, float]]:
    by_canyon: dict[int, dict[str, tuple[float, float]]] = {}
    for point in json.loads(geo_points_path.read_text()):
        if point.get("latitude") is None or point.get("longitude") is None:
            continue
        by_canyon.setdefault(int(point["canyonId"]), {}).setdefault(point.get("type") or "", (point["latitude"], point["longitude"]))
    output = {}
    for canyon_id, points in by_canyon.items():
        for kind in POINT_PREFERENCE:
            if kind in points:
                output[canyon_id] = points[kind]
                break
        else:
            output[canyon_id] = next(iter(points.values()))
    return output


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--geo-points", default="offline-data/full/room-import/geo_points.json")
    parser.add_argument("--output-dir", default="build/debit-model/hubeau")
    parser.add_argument("--radius-km", type=float, default=30.0)
    parser.add_argument("--max-sites-per-canyon", type=int, default=6)
    parser.add_argument("--start", default="2000-01-01")
    parser.add_argument("--delay-s", type=float, default=0.3)
    parser.add_argument("--workers", type=int, default=4)
    args = parser.parse_args()

    output_dir = Path(args.output_dir)
    (output_dir / "series").mkdir(parents=True, exist_ok=True)
    sites_path = output_dir / "sites.json"
    if sites_path.exists():
        sites = json.loads(sites_path.read_text())
    else:
        sites = paginate(f"{API}/referentiel/sites", {"format": "json", "size": 5000})
        sites_path.write_text(json.dumps(sites))
    sites = [s for s in sites if s.get("latitude_site") is not None and s.get("grandeur_hydro") in (None, "Q", "QH")]
    print(f"sites with coordinates: {len(sites)}", flush=True)

    points = canyon_points(Path(args.geo_points))
    canyon_sites: dict[str, list[dict]] = {}
    for canyon_id, (lat, lon) in points.items():
        candidates = []
        for site in sites:
            if abs(site["latitude_site"] - lat) > 0.4 or abs(site["longitude_site"] - lon) > 0.6:
                continue
            distance = haversine_km(lat, lon, site["latitude_site"], site["longitude_site"])
            if distance <= args.radius_km:
                candidates.append({
                    "code_site": site["code_site"],
                    "distanceKm": round(distance, 3),
                    "surfaceBvKm2": site.get("surface_bv"),
                    "influence": site.get("influence_generale_site"),
                    "altitude": site.get("altitude_site"),
                })
        candidates.sort(key=lambda c: c["distanceKm"])
        if candidates:
            canyon_sites[str(canyon_id)] = candidates[: args.max_sites_per_canyon]
    (output_dir / "canyon_sites.json").write_text(json.dumps(canyon_sites))
    wanted = sorted({c["code_site"] for values in canyon_sites.values() for c in values})
    print(f"canyons with a site within {args.radius_km} km: {len(canyon_sites)}; sites to download: {len(wanted)}", flush=True)

    fields = "code_site,code_station,date_obs_elab,resultat_obs_elab"
    pending = [code for code in wanted if not (output_dir / "series" / f"{code}.json").exists()]

    def download(code: str) -> int:
        target = output_dir / "series" / f"{code}.json"
        rows = paginate(f"{API}/obs_elab", {
            "code_entite": code,
            "grandeur_hydro_elab": "QmnJ",
            "date_debut_obs_elab": args.start,
            "fields": fields,
            "size": 20000,
        })
        site_rows = [r for r in rows if r.get("code_station") is None]
        if not site_rows:
            stations = sorted({r["code_station"] for r in rows if r.get("code_station")})
            site_rows = [r for r in rows if stations and r.get("code_station") == stations[0]]
        series = sorted((r["date_obs_elab"][:10], r["resultat_obs_elab"]) for r in site_rows if r.get("resultat_obs_elab") is not None)
        target.write_text(json.dumps({"code_site": code, "dates": [d for d, _ in series], "q": [q for _, q in series]}))
        time.sleep(args.delay_s)
        return len(series)

    from concurrent.futures import ThreadPoolExecutor
    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        for number, count in enumerate(pool.map(download, pending), start=1):
            if number % 50 == 0:
                print(f"[{number}/{len(pending)}] downloaded", flush=True)
    print("done", flush=True)


if __name__ == "__main__":
    main()
