"""The app's debit model, run on the server with the same assets and the same feature path.

Everything comes from the app repository: the Python reference of the app's feature path
(scripts/debit_model/runtime_reference.py, checked against the Kotlin code by golden vectors), the
model files the APK ships (modele_statistique/) and the lookups. The estimate of a canyon is thus the
one the phone would compute from the same inputs; a model retrained and pushed to the repository is
used at the next run, without an app release.
"""
from __future__ import annotations

import hashlib
import json
import sys
from dataclasses import dataclass, field
from datetime import date, timedelta
from pathlib import Path
from typing import Any

import numpy as np

DEBIT_MODEL_DIR = Path("scripts/debit_model")
ASSETS_DIR = Path("modele_statistique")
HIGH_RANK = 3
LOW_RANK = 1
HORIZONS = (0, 1, 2)

# Fields of the J0 feature values the app shows in "Sur quoi repose l'estimation" (DebitPredictionRepositoryImpl.basisOf).
BASIS_FEATURES = (
    "histDaysSinceLast",
    "histLastRank",
    "histCount30d",
    "nb7Count",
    "nb7MeanAnom",
    "wPrecip3d",
    "wPrecipDay0",
    "mfCount",
    "mfAutoRain1",
    "hSwe",
    "hMelt7d",
)


@dataclass
class CanyonInputs:
    """Everything the feature path reads for one canyon (the app's DebitPredictionSupport)."""

    canyon_id: int
    canyon: dict[str, Any]
    weather: dict[date, tuple[float | None, float | None]]
    grid_latitude: float | None
    grid_longitude: float | None
    grid_elevation: float | None
    static: dict[str, float]
    lookup: dict[str, float]
    history: list[tuple[int, int]]
    neighbors: list[tuple[int, float]] = field(default_factory=list)
    cell_aggregates: dict[int, tuple[list[float], list[float]]] = field(default_factory=dict)
    recent_cells: list[tuple[int, int, int]] = field(default_factory=list)
    rain_stations: list[tuple[str, float]] = field(default_factory=list)
    rain_values: dict[str, dict[int, float]] = field(default_factory=dict)
    automatic_rain: dict[str, float] = field(default_factory=dict)


@dataclass
class DayEstimate:
    date: date
    horizon: int
    probabilities: list[float]  # SEC..CRUE
    rank: int
    score: float
    high_probability: float
    high_threshold: float
    level: str  # LOW / MEDIUM / HIGH


class ModelRunner:
    """Loads the repository's model once and estimates any number of canyons."""

    def __init__(self, repo: Path) -> None:
        module_dir = str((repo / DEBIT_MODEL_DIR).resolve())
        if module_dir not in sys.path:
            sys.path.insert(0, module_dir)
        import evaluation
        import features
        import label_quality
        import network
        import runtime_inputs
        import runtime_reference
        import tree_model

        self.F, self.R, self.E, self.RI, self.LQ = features, runtime_reference, evaluation, runtime_inputs, label_quality
        assets = repo / ASSETS_DIR
        spec = json.loads((assets / "feature_spec.json").read_text(encoding="utf-8"))
        self.feature_names = [feature["name"] for feature in spec["features"]]
        thresholds = json.loads((assets / "thresholds.json").read_text(encoding="utf-8"))
        self.policy = thresholds["defaultPolicy"]
        self.ordinal_cutpoints = list(thresholds.get("ordinalCutpoints", {}).values())
        horizon = thresholds["horizonModel"]
        self.models = {
            0: self._load(assets, tree_model, network, "model.trees", thresholds.get("network")),
            1: self._load(assets, tree_model, network, horizon["modelAsset"], horizon.get("network")),
        }
        self.cumulative = {0: np.array(thresholds["cumulativeThresholds"]), 1: np.array(horizon["cumulativeThresholds"])}
        self.high_threshold = {
            0: thresholds["policies"][self.policy]["highThreshold"],
            1: horizon["policies"][self.policy]["highThreshold"],
        }
        self.static = json.loads((assets / "canyon_static_features.json").read_text(encoding="utf-8"))
        self.lookups = json.loads((assets / "runtime_feature_lookups.json").read_text(encoding="utf-8"))
        digest = hashlib.sha256()
        for name in sorted(p.name for p in assets.iterdir() if p.suffix in {".trees", ".mlp"} or p.name == "thresholds.json"):
            digest.update((assets / name).read_bytes())
        self.version = digest.hexdigest()[:12]

    @staticmethod
    def _load(assets: Path, tree_model, network, trees_name: str, network_entry: dict | None):
        trees = tree_model.read(assets / trees_name)
        net = network.Network.read(assets / network_entry["asset"]) if network_entry else None
        weight = network_entry.get("weight", 0.5) if network_entry else None
        return trees, net, weight, network

    # Inputs read from the lookups (EmbeddedDebitRuntimeLookupStore twins)

    def static_values(self, canyon_id: int) -> dict[str, float]:
        return self.static.get(str(canyon_id), {})

    def lookup_values(self, canyon_id: int, canyon: dict) -> dict[str, float]:
        return self.RI.resolve_lookup(self.lookups, canyon_id, canyon)

    def lookup_source(self, canyon_id: int, canyon: dict) -> str:
        if str(canyon_id) in self.lookups["canyons"]:
            return "CANYON"
        if canyon.get("massif") and canyon["massif"] in self.lookups["massifs"]:
            return "MASSIF"
        if canyon.get("region") and canyon["region"] in self.lookups["regions"]:
            return "REGION"
        return "GLOBAL"

    def neighbors(self, canyon_id: int) -> tuple[list[tuple[int, float]], dict[int, tuple[list[float], list[float]]]]:
        return self.RI.neighbor_inputs(self.lookups, canyon_id)

    def rain_stations(self, canyon_id: int) -> list[tuple[str, float]]:
        return self.RI.rain_inputs(self.lookups, canyon_id)

    def shipped_history(self, lookup_values: dict[str, float]) -> list[tuple[int, int]]:
        return self.RI.decode_history(lookup_values)

    # Estimate

    def estimate(self, inputs: CanyonInputs, today: date) -> tuple[list[DayEstimate], dict[str, float | None] | None]:
        """J0, J+1, J+2 estimates of one canyon (fewer when the weather window is short), with the
        J0 feature values (the basis shown with the estimate)."""
        return self.estimate_many([(inputs, today)])[0]

    def estimate_many(self, items: list[tuple[CanyonInputs, date]]) -> list[tuple[list[DayEstimate], dict[str, float | None] | None]]:
        """[estimate] of several canyons: the models run once per horizon group on all of them (a
        tree pass costs the same for one row or a thousand)."""
        rows: dict[int, list[tuple[int, int, date, list[float]]]] = {0: [], 1: []}
        bases: list[dict[str, float | None] | None] = []
        for index, (inputs, today) in enumerate(items):
            first_values = None
            for horizon in HORIZONS:
                target = today + timedelta(days=horizon)
                values = self.R.compute_features(
                    target, inputs.weather, inputs.grid_latitude, inputs.grid_longitude, inputs.grid_elevation,
                    inputs.static, inputs.canyon, inputs.history, inputs.lookup,
                    canyon_id=inputs.canyon_id, neighbor_list=inputs.neighbors, recent_cells=inputs.recent_cells,
                    cell_aggregates=inputs.cell_aggregates, rain_stations=inputs.rain_stations, rain_values=inputs.rain_values,
                    horizon=horizon, automatic_rain=inputs.automatic_rain,
                )
                if values is None:
                    continue
                if first_values is None:
                    first_values = values
                rows[min(horizon, 1)].append((index, horizon, target, [self.F.finalize_value(values.get(n)) for n in self.feature_names]))
            bases.append({name: first_values.get(name) for name in BASIS_FEATURES} if first_values else None)
        estimates: list[list[DayEstimate]] = [[] for _ in items]
        for group, group_rows in rows.items():
            if not group_rows:
                continue
            matrix = np.array([row[3] for row in group_rows], dtype=np.float32)
            decided = self._decide(group, [(h, t, None) for _, h, t, _ in group_rows], self.probabilities(group, matrix))
            for (index, _, _, _), estimate in zip(group_rows, decided):
                estimates[index].append(estimate)
        for found in estimates:
            found.sort(key=lambda e: e.horizon)
        return list(zip(estimates, bases))

    def probabilities(self, group: int, matrix: np.ndarray) -> np.ndarray:
        trees, net, weight, network = self.models[group]
        probabilities = trees.predict_proba(matrix)
        if net is not None:
            probabilities = network.blend(probabilities, net.predict_proba(matrix), weight)
        return probabilities

    def _decide(self, group: int, rows: list, probabilities: np.ndarray) -> list[DayEstimate]:
        tau = self.cumulative[group]
        ranks = self.E.decide(probabilities, tau)
        scores = self.E.display_score(probabilities, tau)
        threshold = self.high_threshold[group]
        out = []
        for (horizon, target, _), p, rank, score in zip(rows, probabilities, ranks, scores):
            high = float(p[HIGH_RANK:].sum())
            rank = int(rank)
            if rank >= HIGH_RANK or high >= threshold:
                level = "HIGH"
            elif rank <= LOW_RANK:
                level = "LOW"
            else:
                level = "MEDIUM"
            out.append(DayEstimate(target, horizon, [float(x) for x in p], rank, float(score), high, float(threshold), level))
        return out


def weather_target(canyon_id: int, watershed_bbox: tuple[float, float, float, float] | None, points: list[tuple[str, float, float]]) -> tuple[float, float, str] | None:
    """WeatherTargetResolver: the watershed's centre, else the best point of the sheet."""
    if watershed_bbox is not None:
        min_lon, min_lat, max_lon, max_lat = watershed_bbox
        return (min_lat + max_lat) / 2.0, (min_lon + max_lon) / 2.0, "WATERSHED_CENTER"
    priority = {"ENTREE": 0, "PARKING_AMONT": 1, "SORTIE": 2, "PARKING_AVAL": 3, "POINT_REMARQUABLE": 4, "ECHAPPATOIRE": 5}
    source = {
        "ENTREE": "ENTRY",
        "PARKING_AMONT": "UPSTREAM_PARKING",
        "SORTIE": "EXIT",
        "PARKING_AVAL": "DOWNSTREAM_PARKING",
        "POINT_REMARQUABLE": "REMARKABLE_POINT",
        "ECHAPPATOIRE": "ESCAPE",
    }
    if not points:
        return None
    kind, latitude, longitude = min(points, key=lambda p: priority.get(p[0], 6))
    return latitude, longitude, source.get(kind, "UNKNOWN")
