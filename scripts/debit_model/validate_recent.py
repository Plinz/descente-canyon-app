"""Out-of-time validation through the exact app path.

For the most observed canyons, scrapes the live debit reports published after the training period,
fetches weather exactly like the app (ERA5 archive + ECMWF IFS 0.25 deg for the last days), builds
features with ``runtime_reference`` from the exported assets (static features, frozen lookups) and
scores the exported ONNX model with the exported thresholds.
"""
from __future__ import annotations

import argparse
import json
import sys
import time
import urllib.parse
import urllib.request
from collections import Counter
from datetime import date, timedelta
from pathlib import Path

import numpy as np
import onnxruntime as ort
import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "debits"))
from pipeline_lib import fetch_text, parse_canyon_debit_page  # noqa: E402

import evaluation as E  # noqa: E402
from label_quality import is_message_only_high_report  # noqa: E402
import features as F  # noqa: E402
import runtime_reference as R  # noqa: E402
from export_app_assets import resolve_lookup  # noqa: E402

DEBIT_URL = "https://www.descente-canyon.com/canyoning/canyon-debit/{id}/observations.html"


def get_json(url: str, params: dict) -> dict:
    full = f"{url}?{urllib.parse.urlencode(params)}"
    for attempt in range(4):
        try:
            with urllib.request.urlopen(full, timeout=60) as response:
                return json.loads(response.read().decode("utf-8"))
        except Exception as error:  # noqa: BLE001
            print(f"weather retry {attempt}: {error}", flush=True)
            time.sleep(20 * (attempt + 1))
    raise RuntimeError(full)


def app_weather(latitude: float, longitude: float, start: date, today: date, cache: Path) -> tuple[dict, dict]:
    """Same merge rule as DebitPredictionSupportRepositoryImpl: ERA5 when complete, else IFS."""
    if cache.exists():
        payload = json.loads(cache.read_text())
    else:
        archive = get_json("https://archive-api.open-meteo.com/v1/archive", {
            "latitude": latitude, "longitude": longitude, "start_date": start.isoformat(),
            "end_date": (today - timedelta(days=1)).isoformat(), "daily": "precipitation_sum,temperature_2m_mean",
            "models": "era5", "timezone": "auto",
        })
        forecast = get_json("https://api.open-meteo.com/v1/forecast", {
            "latitude": latitude, "longitude": longitude, "past_days": 14, "forecast_days": 3,
            "daily": "precipitation_sum,temperature_2m_mean", "models": "ecmwf_ifs025", "timezone": "auto",
        })
        payload = {"archive": archive, "forecast": forecast}
        cache.parent.mkdir(parents=True, exist_ok=True)
        cache.write_text(json.dumps(payload))
    merged: dict[date, tuple[float | None, float | None]] = {}
    for source in ("forecast", "archive"):  # archive overrides forecast when complete
        daily = payload[source].get("daily") or {}
        for day, p, t in zip(daily.get("time", []), daily.get("precipitation_sum", []), daily.get("temperature_2m_mean", [])):
            if p is not None and t is not None:
                merged[date.fromisoformat(day)] = (p, t)
    grid = {k: payload["forecast"].get(k) for k in ("latitude", "longitude", "elevation")}
    return merged, grid


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset-dir", default="build/debit-model/dataset")
    parser.add_argument("--model-dir", default="modele_statistique")
    parser.add_argument("--targets", default="build/debit-pipeline/weather-planning/weather_targets.jsonl")
    parser.add_argument("--output-dir", default="build/debit-model/recent-validation")
    parser.add_argument("--after", default="2026-06-04", help="Only score reports strictly after this day")
    parser.add_argument("--canyon-count", type=int, default=60)
    parser.add_argument("--delay-s", type=float, default=2.0)
    args = parser.parse_args()

    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    model_dir = Path(args.model_dir)
    histories = pd.read_pickle(Path(args.dataset_dir) / "canyon_history.pkl")
    canyons = {int(row["id"]): row for row in json.loads(Path("offline-data/full/room-import/canyons.json").read_text())}
    static = json.loads((model_dir / "canyon_static_features.json").read_text())
    lookups = json.loads((model_dir / "runtime_feature_lookups.json").read_text())
    thresholds = np.array(json.loads((model_dir / "thresholds.json").read_text())["cumulativeThresholds"])
    spec = json.loads((model_dir / "feature_spec.json").read_text())
    names = [feature["name"] for feature in spec["features"]]
    session = ort.InferenceSession(str(model_dir / "model.onnx"), providers=["CPUExecutionProvider"])
    targets = {}
    for line in Path(args.targets).read_text().splitlines():
        if line.strip():
            target = json.loads(line)
            for canyon_id in target["canyonIds"]:
                targets[int(canyon_id)] = target

    after = F.to_epoch_day(date.fromisoformat(args.after))
    recent_counts = Counter({cid: sum(1 for d, _ in h if d >= after - 730) for cid, h in histories.items() if cid in targets})
    chosen = [cid for cid, _ in recent_counts.most_common(args.canyon_count)]
    today = date.today()

    rows = []
    for number, canyon_id in enumerate(chosen, start=1):
        canyon = canyons.get(canyon_id, {})
        cache = output_dir / "html" / f"{canyon_id}.html"
        html = fetch_text(DEBIT_URL.format(id=canyon_id), delay_seconds=0 if cache.exists() else args.delay_s, cache_path=cache)
        reports = parse_canyon_debit_page(canyon_id=canyon_id, canyon_name=canyon.get("nom"), html=html, source_url=DEBIT_URL.format(id=canyon_id))
        history = sorted({
            (F.to_epoch_day(date.fromisoformat(r["date"])), F.RANK_BY_LEVEL[r["niveau"]])
            for r in reports
            if r.get("date") and r.get("niveau") in F.RANK_BY_LEVEL
            and not is_message_only_high_report(F.RANK_BY_LEVEL[r["niveau"]], r.get("isDescended"), r.get("comment"))
        })
        recent = [(d, r) for d, r in history if after < d < F.to_epoch_day(today)]
        if not recent:
            continue
        target = targets[canyon_id]
        first = F.from_epoch_day(min(d for d, _ in recent)) - timedelta(days=410)
        weather, grid = app_weather(target["latitude"], target["longitude"], first, today, output_dir / "weather" / f"{canyon_id}.json")
        lookup_values = resolve_lookup(lookups, canyon_id, canyon)
        for day, rank in recent:
            values = R.compute_features(
                F.from_epoch_day(day), weather, grid["latitude"], grid["longitude"], grid["elevation"],
                static.get(str(canyon_id), {}), canyon, history, lookup_values,
            )
            if values is None:
                continue
            past = [(d, r) for d, r in history if d < day]
            last = max(past)[1] if past else None
            rows.append({
                "canyonId": canyon_id, "day": day, "rank": rank,
                "persistence": last if last is not None else int(round(values["histSeasonMeanRank"])),
                "daysSinceLast": values.get("histDaysSinceLast"),
                "vector": [F.finalize_value(values.get(name)) for name in names],
            })
        print(f"[{number}/{len(chosen)}] canyon {canyon_id} {canyon.get('nom')}: {len(recent)} recent reports", flush=True)

    if not rows:
        raise SystemExit("no recent reports scored")
    matrix = np.array([row["vector"] for row in rows], dtype=np.float32)
    outputs = session.run(None, {session.get_inputs()[0].name: matrix})[1]
    probabilities = np.array([[row[level] for level in F.LEVELS] for row in outputs])
    y = np.array([row["rank"] for row in rows])
    persistence = np.array([row["persistence"] for row in rows])
    predicted = E.decide(probabilities, thresholds)
    report = {
        "after": args.after,
        "until": str(today - timedelta(days=1)),
        "canyons": len({row["canyonId"] for row in rows}),
        "model": {**E.level_metrics(y, predicted), **E.probabilistic_metrics(y, probabilities)},
        "persistence": E.level_metrics(y, persistence),
        "labelCounts": {F.LEVELS[k]: int(v) for k, v in zip(*np.unique(y, return_counts=True))},
        "confusion": pd.crosstab(pd.Series(y, name="true"), pd.Series(predicted, name="pred")).to_dict(),
    }
    scored = pd.DataFrame({
        "canyonId": [row["canyonId"] for row in rows],
        "day": [row["day"] for row in rows],
        "rank": y,
        "predicted": predicted,
        "persistence": persistence,
        "daysSinceLast": [row["daysSinceLast"] for row in rows],
        **{f"p{k}": probabilities[:, k] for k in range(len(F.LEVELS))},
        **{name: matrix[:, index] for index, name in enumerate(names)},
    })
    scored.to_pickle(output_dir / "scored.pkl")
    (output_dir / "report.json").write_text(json.dumps(report, indent=1, default=str))
    print(json.dumps({k: report[k] for k in ("after", "until", "canyons", "model", "persistence", "labelCounts")}, indent=1, default=str))


if __name__ == "__main__":
    main()
