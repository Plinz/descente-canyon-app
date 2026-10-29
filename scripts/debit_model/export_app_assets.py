"""Export the v3 debit model and its runtime data to ``modele_statistique/`` (Android assets).

Also writes the golden vectors checked by the Kotlin unit test and verifies that the ONNX model
reproduces CatBoost probabilities.
"""
from __future__ import annotations

import argparse
import json
import math
import shutil
from datetime import date, datetime, timezone
from pathlib import Path

import numpy as np
import onnxruntime as ort
import pandas as pd
from catboost import CatBoostClassifier

import evaluation as E
import features as F
import history as H
import meteofrance as M
import neighbors as N
import runtime_reference as R
from label_quality import is_message_only_high_report
from weather_store import WeatherStore

UNKNOWN = "__UNKNOWN__"
OBSOLETE_ASSETS = [
    "high_risk_overlay.onnx",
    "high_risk_overlay.json",
    "high_risk_overlay_feature_spec.json",
    "high_risk_overlay_metrics.json",
    "runtime-lookups",
]


def clean_number(value):
    if value is None:
        return None
    if isinstance(value, bool):
        return 1.0 if value else 0.0
    number = float(value)
    if math.isnan(number) or math.isinf(number):
        return None
    return round(number, 6)


def write_json(path: Path, payload, minify: bool = False) -> None:
    text = json.dumps(payload, ensure_ascii=False, separators=(",", ":")) if minify else json.dumps(payload, ensure_ascii=False, indent=2)
    path.write_text(text, encoding="utf-8")


def export_static(dataset_dir: Path, output_dir: Path) -> dict[str, dict]:
    static = json.loads((dataset_dir / "canyon_static.json").read_text())
    keys = set(F.STATIC_FEATURES) | {f"hydroBand{i}ElevationM" for i in range(1, 6)} | {"hydroSoilCapacityMm"}
    keys |= {f"hydroLog{n}{s}" for n in F.RESERVOIR_NAMES for s in ("Mean", "Std")}
    exported = {}
    for canyon_id, values in static.items():
        entry = {key: clean_number(values.get(key)) for key in sorted(keys)}
        exported[canyon_id] = {key: value for key, value in entry.items() if value is not None}
    write_json(output_dir / "canyon_static_features.json", exported, minify=True)
    return exported


def lookup_payload(
    histories: dict[int, list[tuple[int, int]]],
    canyons: dict[int, dict],
    neighbor_table: dict[int, list[tuple[int, float]]],
    cell_aggregates: dict[int, tuple[list[float], list[float]]],
    rain_nearby: dict[int, list[tuple[str, float]]],
) -> dict:
    observations = H.observations_frame(histories, canyons)
    massifs, regions, global_ = H.build_lookups(observations, None)
    canyon_entries = {}
    for canyon_id in sorted(set(histories) | set(neighbor_table) | set(rain_nearby)):
        canyon = canyons.get(canyon_id, {})
        entry = {"regionKey": canyon.get("region") or UNKNOWN, "massifKey": canyon.get("massif") or UNKNOWN}
        for index, (day, rank) in enumerate(histories.get(canyon_id, [])):
            entry[f"h{index}"] = day * 10 + rank
        for neighbor_id, distance in neighbor_table.get(canyon_id, []):
            entry[f"n{neighbor_id}"] = round(distance, 2)
        for station, distance in rain_nearby.get(canyon_id, []):
            entry[f"mf{station}"] = round(distance, 2)
        canyon_entries[str(canyon_id)] = entry
    return {
        "schemaVersion": 3,
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "targetMode": "six_ordinal_cumulative",
        "labels": F.LEVELS,
        "unknownKeys": {"region": UNKNOWN, "massif": UNKNOWN},
        "lookupFeatureNames": [],
        "defaults": {},
        "global": {f"g.{k}": v for k, v in global_.to_lookup().items()},
        "regions": {name: {f"r.{k}": v for k, v in agg.to_lookup().items()} for name, agg in sorted(regions.items())},
        "massifs": {name: {f"s.{k}": v for k, v in agg.to_lookup().items()} for name, agg in sorted(massifs.items())},
        # Monthly (count, level sum) of each canyon's grid cells: neighbours' usual level.
        "cellSeasonal": {str(c): [*counts, *sums] for c, (counts, sums) in sorted(cell_aggregates.items())},
        "canyons": canyon_entries,
    }


def resolve_lookup(payload: dict, canyon_id: int, canyon: dict) -> dict[str, float]:
    """Python twin of EmbeddedDebitRuntimeLookupStore.resolve (flat merged values)."""
    values: dict[str, float] = {}
    values.update(payload["defaults"])
    values.update(payload["global"])
    values.update(payload["regions"].get(canyon.get("region") or UNKNOWN, {}))
    values.update(payload["massifs"].get(canyon.get("massif") or UNKNOWN, {}))
    entry = payload["canyons"].get(str(canyon_id))
    if entry:
        values.update({k: v for k, v in entry.items() if isinstance(v, (int, float))})
    return values


def rain_inputs(payload: dict, canyon_id: int) -> list[tuple[str, float]]:
    entry = payload["canyons"].get(str(canyon_id), {})
    return sorted(((k[2:], float(v)) for k, v in entry.items() if is_rain_station_key(k)), key=lambda x: x[1])


def is_rain_station_key(key: str) -> bool:
    """Lookup keys ``mf<code>``: Météo-France codes are digits, MeteoSwiss ones ``CHS_``/``CHP_``."""
    code = key[2:]
    return key.startswith("mf") and (code.isdigit() or code.startswith(("CHS_", "CHP_")))


def neighbor_inputs(payload: dict, canyon_id: int) -> tuple[list[tuple[int, float]], dict[int, tuple[list[float], list[float]]]]:
    entry = payload["canyons"].get(str(canyon_id), {})
    neighbors = sorted(((int(k[1:]), float(v)) for k, v in entry.items() if k.startswith("n") and k[1:].isdigit()), key=lambda x: x[1])
    aggregates = {}
    for neighbor_id, _ in neighbors:
        values = payload["cellSeasonal"].get(str(neighbor_id))
        if values:
            aggregates[neighbor_id] = (values[:12], values[12:])
    return neighbors, aggregates


def export_model(final_dir: Path, output_dir: Path, sample: pd.DataFrame, model_name: str = "today", onnx_name: str = "model.onnx") -> dict:
    model = CatBoostClassifier()
    model.load_model(str(final_dir / f"model_{model_name}.cbm"))
    onnx_path = output_dir / onnx_name
    model.save_model(str(onnx_path), format="onnx", export_parameters={"onnx_domain": "ai.catboost", "onnx_model_version": 3, "onnx_doc_string": "Descente-Canyon debit model v3"})
    matrix = sample[F.FEATURE_NAMES].to_numpy(dtype=np.float64)
    matrix = np.where(np.isnan(matrix), F.MISSING, matrix).astype(np.float32)
    expected = model.predict_proba(matrix)
    order = [list(model.classes_).index(level) for level in F.LEVELS]
    expected = expected[:, order]
    session = ort.InferenceSession(str(onnx_path), providers=["CPUExecutionProvider"])
    outputs = session.run(None, {session.get_inputs()[0].name: matrix})
    probabilities = outputs[1]
    if isinstance(probabilities, list):  # zipmap: list of {label: probability}
        probabilities = np.array([[row[level] for level in F.LEVELS] for row in probabilities])
    else:
        probabilities = np.asarray(probabilities)[:, order]
    max_diff = float(np.abs(probabilities - expected).max())
    if max_diff > 1e-4:
        raise SystemExit(f"ONNX/CatBoost mismatch: {max_diff}")
    return {
        "onnxMaxAbsDiff": max_diff,
        "onnxOutputs": [o.name for o in session.get_outputs()],
        "classes": [str(c) for c in model.classes_],
        "importances": model.get_feature_importance().tolist(),
    }


def golden_vectors(
    dataset_dir: Path,
    weather_path: Path,
    targets_path: Path,
    lookups: dict,
    static: dict[str, dict],
    canyons: dict[int, dict],
    histories: dict[int, list[tuple[int, int]]],
    output_path: Path,
    count: int,
    models: dict[int, CatBoostClassifier],
    thresholds: dict[int, list[float]],
    cells: dict[int, list[tuple[int, int]]] | None = None,
    rain_series: dict | None = None,
    hourly_codes: set[str] | None = None,
) -> int:
    base = pd.read_pickle(dataset_dir / "base.pkl")
    store = WeatherStore.load(weather_path)
    targets = {}
    for line in targets_path.read_text().splitlines():
        if line.strip():
            target = json.loads(line)
            for canyon_id in target["canyonIds"]:
                targets[int(canyon_id)] = target["targetId"]
    rng = np.random.default_rng(11)
    snowy = base[base["hSwe"] > 50].sample(min(count // 3, 50), random_state=3)
    others = base.sample(count - len(snowy), random_state=5)
    picks = pd.concat([snowy, others])
    vectors = []
    for pick_number, (_, row) in enumerate(picks.iterrows()):
        horizon = 1 if pick_number % 3 == 2 else 0
        canyon_id = int(row["canyonId"])
        series = store.get(targets[canyon_id])
        target = F.from_epoch_day(int(row["day"]))
        weather = {}
        first_day = int(row["day"]) - 420
        for day in range(first_day, int(row["day"]) + 1):
            index = day - series.start_day
            if 0 <= index < len(series.precipitation):
                p = series.precipitation[index]
                t = series.temperature[index]
                # punch a few holes to exercise the fill rule
                if rng.random() < 0.01:
                    t = np.nan
                weather[F.from_epoch_day(day)] = (None if np.isnan(p) else float(p), None if np.isnan(t) else float(t))
        canyon = canyons.get(canyon_id, {})
        static_values = static.get(str(canyon_id), {})
        lookup_values = resolve_lookup(lookups, canyon_id, canyon)
        history = histories.get(canyon_id, [])
        neighbor_list, aggregates = neighbor_inputs(lookups, canyon_id)
        day0 = int(row["day"])
        recent = [
            (n, d, lv) for n, _ in neighbor_list for d, lv in (cells or {}).get(n, [])
            if day0 - 16 <= d <= day0
        ]
        stations = rain_inputs(lookups, canyon_id)
        rain_values = {}
        for code, _ in stations:
            if rain_series and code in rain_series:
                start, values_rr = rain_series[code]
                by_day = {}
                for d in range(day0 - 32, day0 + 1):
                    index = d - start
                    if 0 <= index < len(values_rr) and not np.isnan(values_rr[index]):
                        by_day[d] = float(values_rr[index])
                rain_values[code] = by_day
        automatic_rain = {
            code: by_day[day0 - horizon - 1]
            for code, by_day in rain_values.items()
            if M.is_automatic(code, hourly_codes or set()) and (day0 - horizon - 1) in by_day
        }
        values = R.compute_features(
            target, weather, series.latitude, series.longitude, series.elevation,
            static_values, canyon, history, lookup_values,
            canyon_id=canyon_id, neighbor_list=neighbor_list, recent_cells=recent, cell_aggregates=aggregates,
            rain_stations=stations, rain_values=rain_values, horizon=horizon, automatic_rain=automatic_rain,
        )
        if values is None:
            continue
        vector = np.array([[F.finalize_value(values.get(name)) for name in F.FEATURE_NAMES]], dtype=np.float32)
        model = models[min(horizon, 1)]
        tau = np.array(thresholds[min(horizon, 1)])
        order = [list(model.classes_).index(level) for level in F.LEVELS]
        probabilities = model.predict_proba(vector)[:, order]
        vectors.append({
            "horizon": horizon,
            "probabilities": [round(float(p), 6) for p in probabilities[0]],
            "level": int(E.decide(probabilities, tau)[0]),
            "displayScore": round(float(E.display_score(probabilities, tau)[0]), 6),
            "canyonId": canyon_id,
            "targetDate": target.isoformat(),
            "grid": {"latitude": series.latitude, "longitude": series.longitude, "elevation": series.elevation},
            "canyon": {k: canyon.get(k) for k in ("altitudeDepart", "denivele", "longueur", "cascadeMax")},
            "weather": [[d.isoformat(), p, t] for d, (p, t) in sorted(weather.items())],
            "static": static_values,
            "history": [[d, r] for d, r in history],
            "lookup": {k: v for k, v in lookup_values.items() if not ((k[:1] in ("h", "n") and k[1:].isdigit()) or is_rain_station_key(k))},
            "rainStations": [[c, km] for c, km in stations],
            "automaticRain": automatic_rain,
            "rainValues": {c: [[d, rr] for d, rr in sorted(v.items())] for c, v in rain_values.items()},
            "neighbors": [[n, d] for n, d in neighbor_list],
            "cellSeasonal": {str(n): [*a[0], *a[1]] for n, a in aggregates.items()},
            "recentCells": [[n, d, lv] for n, d, lv in recent],
            "expected": {name: clean_number(values.get(name)) for name in F.FEATURE_NAMES},
        })
    output_path.parent.mkdir(parents=True, exist_ok=True)
    write_json(output_path, {"featureNames": F.FEATURE_NAMES, "missing": F.MISSING, "vectors": vectors}, minify=True)
    return len(vectors)


def report_quality_cases(observations_path: Path, golden_path: Path, sample: int = 400) -> int:
    """Add message-only filter cases (all positives + a sample of high non-descended reports)."""
    candidates = []
    with observations_path.open("r", encoding="utf-8") as handle:
        for line in handle:
            row = json.loads(line)
            rank = F.RANK_BY_LEVEL.get(row.get("niveau"))
            if rank is None or rank < 3 or row.get("isDescended") is not False or not row.get("comment"):
                continue
            candidates.append((rank, row["comment"], is_message_only_high_report(rank, False, row["comment"])))
    rng = np.random.default_rng(17)
    positives = [c for c in candidates if c[2]]
    negatives = [c for c in candidates if not c[2]]
    picked = positives + [negatives[i] for i in rng.choice(len(negatives), size=min(sample, len(negatives)), replace=False)]
    payload = json.loads(golden_path.read_text())
    payload["reportQuality"] = [{"rank": r, "isDescended": False, "comment": c, "expected": e} for r, c, e in picked]
    write_json(golden_path, payload, minify=True)
    return len(picked)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset-dir", default="build/debit-model/dataset")
    parser.add_argument("--final-dir", default="build/debit-model/final")
    parser.add_argument("--walkforward-dir", default="build/debit-model/walkforward")
    parser.add_argument("--weather", default="build/debit-model/weather/era5_daily-2026-10-29.npz")
    parser.add_argument("--targets", default="build/debit-pipeline/weather-planning-2026-10-29/weather_targets.jsonl")
    parser.add_argument("--output-dir", default="modele_statistique")
    parser.add_argument("--golden-path", default="core/data/src/test/resources/debit/golden_vectors.json")
    parser.add_argument("--golden-count", type=int, default=24)
    parser.add_argument("--history-observations", default="build/debit-pipeline/descente-refresh-2026-10-13/all_debit_observations.jsonl")
    parser.add_argument("--meteofrance-cache", default="build/debit-model/meteofrance-cache/rain_stations_fr_ch.pkl")
    parser.add_argument("--hourly-stations", default="build/debit-model/meteofrance-cache/hourly_stations.json")
    args = parser.parse_args()

    dataset_dir, final_dir, output_dir = Path(args.dataset_dir), Path(args.final_dir), Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    for name in OBSOLETE_ASSETS:
        path = output_dir / name
        if path.is_dir():
            shutil.rmtree(path)
        elif path.exists():
            path.unlink()

    canyons = {int(row["id"]): row for row in json.loads(Path("offline-data/full/room-import/canyons.json").read_text())}
    histories = pd.read_pickle(dataset_dir / "canyon_history.pkl")
    summary = json.loads((final_dir / "summary.json").read_text())
    walk = json.loads((Path(args.walkforward_dir) / "report.json").read_text())

    model_info = export_model(final_dir, output_dir, pd.read_pickle(final_dir / "sample_rows_today.pkl"), "today", "model.onnx")
    horizon_info = export_model(final_dir, output_dir, pd.read_pickle(final_dir / "sample_rows_horizon.pkl"), "horizon", "model_horizon.onnx")
    static = export_static(dataset_dir, output_dir)
    points = N.canyon_points(Path("offline-data/full/room-import/geo_points.json"), Path(args.targets))
    neighbor_table = N.neighbor_table(points)
    cells = N.day_cells(N.load_raw_reports([Path(args.history_observations)]))
    rain_cache = pd.read_pickle(args.meteofrance_cache)
    lookups = lookup_payload(histories, canyons, neighbor_table, N.monthly_aggregates(cells), rain_cache["nearby"])
    write_json(output_dir / "runtime_feature_lookups.json", lookups, minify=True)

    sources = {name: "computed" for name in F.TIME_FEATURES}
    sources.update({name: "static" for name in F.STATIC_FEATURES})
    sources.update({name: "weather" for name in F.WEATHER_FEATURES})
    sources.update({name: "lookup" for name in F.HISTORY_FEATURES})
    sources.update({name: "lookup" for name in F.NEIGHBOR_FEATURES})
    sources.update({name: "weather" for name in F.RAIN_FEATURES})
    sources.update({name: "weather" for name in F.AUTO_RAIN_FEATURES})
    sources.update({name: "computed" for name in F.INFO_FEATURES})
    write_json(output_dir / "feature_spec.json", {
        "schemaVersion": 3,
        "labels": F.LEVELS,
        "features": [{"name": name, "source": sources[name], "default": F.MISSING} for name in F.FEATURE_NAMES],
        "staticFeatureNames": F.STATIC_FEATURES,
        "lookupFeatureNames": F.HISTORY_FEATURES,
        "dynamicFeatureNames": F.WEATHER_FEATURES,
    })
    risk = summary["today"]["riskThresholds"]
    write_json(output_dir / "thresholds.json", {
        "schemaVersion": 4,
        "targetMode": "six_ordinal_cumulative",
        "defaultPolicy": "balanced",
        "policies": {name: {"highThreshold": value} for name, value in risk.items()},
        "cumulativeThresholds": [round(v, 4) for v in summary["today"]["thresholds"]],
        "ordinalCutpoints": {"secFilet": 0.5, "filetCorrect": 1.5, "correctGros": 2.5, "grosTresGros": 3.5, "tresGrosCrue": 4.5},
        # J+1 / J+2 model (model_horizon.onnx): same contract, real-time information lagged.
        "horizonModel": {
            "modelAsset": "model_horizon.onnx",
            "policies": {name: {"highThreshold": value} for name, value in summary["horizon"]["riskThresholds"].items()},
            "cumulativeThresholds": [round(v, 4) for v in summary["horizon"]["thresholds"]],
        },
    })
    importances = sorted(zip(F.FEATURE_NAMES, model_info["importances"]), key=lambda item: -item[1])
    pooled = walk.get("pooled", {})
    write_json(output_dir / "metrics.json", {
        "schemaVersion": 3,
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "model": "catboost_hydro_ordinal_v3",
        "targetMode": "six_ordinal_cumulative",
        "labels": F.LEVELS,
        "featureCount": len(F.FEATURE_NAMES),
        "features": F.FEATURE_NAMES,
        "trainRowCount": summary["rowCount"],
        "calibrationRowCount": 0,
        "testRowCount": int(pooled.get("model", {}).get("n", 0)),
        "trainingPeriod": {"first": summary["firstDay"], "last": summary["lastDay"]},
        "iterations": {"today": summary["today"]["iterations"], "horizon": summary["horizon"]["iterations"]},
        "weatherSources": {"history": "Open-Meteo archive, models=era5", "recentAndForecast": "Open-Meteo forecast, models=ecmwf_ifs025"},
        "decision": {
            "today": {"cumulativeThresholds": summary["today"]["thresholds"], "riskThresholds": risk},
            "horizon": {"cumulativeThresholds": summary["horizon"]["thresholds"], "riskThresholds": summary["horizon"]["riskThresholds"]},
        },
        "realtimeSources": {
            "neighbourReports": "descente-canyon.com 'derniers débits' day grid, canyons within 30 km",
            "rainGauges": "Météo-France daily climatological network + MeteoSwiss SwissMetNet/precipitation networks, within 25 km",
        },
        "walkForward": {
            "pooled": pooled,
            "pooledHorizonJ1": walk.get("pooledHorizonJ1", {}),
            "folds": {name: {key: block[key] for key in ("model", "persistence", "seasonalClimatology", "segments", "seasons", "calibrationHigh", "horizons", "coldCanyon") if key in block} for name, block in walk["folds"].items()},
        },
        "topFeatureImportances": [{"feature": name, "importance": round(value, 4)} for name, value in importances[:30]],
        "runtimeLookupMetadata": {
            "regionCount": len(lookups["regions"]),
            "massifCount": len(lookups["massifs"]),
            "canyonCount": len(lookups["canyons"]),
        },
        "onnx": {"maxAbsDiffVsCatBoost": max(model_info["onnxMaxAbsDiff"], horizon_info["onnxMaxAbsDiff"]), "outputs": model_info["onnxOutputs"]},
        "files": {"model": "model.onnx", "horizonModel": "model_horizon.onnx", "featureSpec": "feature_spec.json", "canyonStaticFeatures": "canyon_static_features.json", "runtimeLookups": "runtime_feature_lookups.json", "thresholds": "thresholds.json"},
    })
    models = {}
    for horizon, name in ((0, "today"), (1, "horizon")):
        models[horizon] = CatBoostClassifier()
        models[horizon].load_model(str(final_dir / f"model_{name}.cbm"))
    golden = golden_vectors(
        dataset_dir, Path(args.weather), Path(args.targets), lookups, static, canyons, histories,
        Path(args.golden_path), args.golden_count, models,
        {0: summary["today"]["thresholds"], 1: summary["horizon"]["thresholds"]}, cells, rain_cache["series"],
        {code for codes in json.loads(Path(args.hourly_stations).read_text()).values() for code in codes},
    )
    quality = report_quality_cases(Path(args.history_observations), Path(args.golden_path))
    print(json.dumps({"onnx": model_info["onnxMaxAbsDiff"], "outputs": model_info["onnxOutputs"], "goldenVectors": golden, "reportQualityCases": quality}, indent=1))


if __name__ == "__main__":
    main()
