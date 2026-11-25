from __future__ import annotations

import json
from datetime import date
from pathlib import Path

import numpy as np
import pytest

from dcserver.predictions.model import CanyonInputs, ModelRunner, weather_target

REPO = Path(__file__).resolve().parents[2]
GOLDEN = REPO / "core/data/src/test/resources/debit/golden_vectors.json"


@pytest.fixture(scope="module")
def runner() -> ModelRunner:
    return ModelRunner(REPO)


def golden_inputs(vector: dict) -> CanyonInputs:
    return CanyonInputs(
        canyon_id=vector["canyonId"],
        canyon=vector["canyon"],
        weather={date.fromisoformat(d): (p, t) for d, p, t in vector["weather"]},
        grid_latitude=vector["grid"]["latitude"],
        grid_longitude=vector["grid"]["longitude"],
        grid_elevation=vector["grid"]["elevation"],
        static=vector["static"],
        lookup=vector["lookup"],
        history=[tuple(h) for h in vector["history"]],
        neighbors=[tuple(n) for n in vector["neighbors"]],
        cell_aggregates={int(k): (v[:12], v[12:]) for k, v in vector["cellSeasonal"].items()},
        recent_cells=[tuple(c) for c in vector["recentCells"]],
        rain_stations=[tuple(s) for s in vector["rainStations"]],
        rain_values={code: {int(d): rr for d, rr in values} for code, values in vector["rainValues"].items()},
        automatic_rain=vector["automaticRain"],
    )


@pytest.mark.skipif(not GOLDEN.exists(), reason="needs the repository")
def test_the_server_estimates_exactly_like_the_app(runner):
    vectors = json.loads(GOLDEN.read_text())["vectors"]
    checked = 0
    for vector in vectors:
        inputs = golden_inputs(vector)
        target = date.fromisoformat(vector["targetDate"])
        horizon = vector["horizon"]
        # The golden target is J+horizon of the day the estimate is made.
        today = date.fromordinal(target.toordinal() - horizon)
        estimates, basis = runner.estimate(inputs, today)
        estimate = next(e for e in estimates if e.horizon == horizon)
        assert estimate.date == target
        np.testing.assert_allclose(estimate.probabilities, vector["probabilities"], atol=2e-6)
        assert estimate.rank == vector["level"]
        assert estimate.score == pytest.approx(vector["displayScore"], abs=2e-6)
        assert basis is not None
        checked += 1
    assert checked == len(vectors) >= 20


def test_weather_target_follows_the_app_resolver():
    assert weather_target(1, (5.0, 44.0, 6.0, 45.0), [("ENTREE", 1.0, 2.0)]) == (44.5, 5.5, "WATERSHED_CENTER")
    points = [("PARKING_AVAL", 1.0, 1.0), ("SORTIE", 2.0, 2.0), ("PARKING_AMONT", 3.0, 3.0)]
    assert weather_target(1, None, points) == (3.0, 3.0, "UPSTREAM_PARKING")
    assert weather_target(1, None, []) is None
