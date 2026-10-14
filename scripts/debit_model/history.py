"""History features (canyon reports + massif/region priors) for a dataset.

Everything is strictly causal: a row only sees reports dated before its own day. Massif/region
priors are additionally frozen at ``cutoff_day`` (the app ships them frozen at export time), so a
walk-forward test row sees exactly what the app would have known on the first day of the period.
"""
from __future__ import annotations

import bisect
from collections import defaultdict

import numpy as np
import pandas as pd

import features as F


MASSIF_FRAC_LOW = "massifSeasonFracLowPrior"  # not a model feature; needed to rebuild cold-canyon rows

COLD_MISSING_FEATURES = (
    [f"hZ{name}" for name in F.RESERVOIR_NAMES]
    + ["hZQ1Evening", "hDeltaLogQ3", "hDeltaLogQ10", "hDeltaLogQ30", "hLiquidSinceLast", "hSweChangeSinceLast",
       "hydroLogQ10Mean", "hydroLogQ90Mean"]
)


def cold_canyon(frame: pd.DataFrame) -> pd.DataFrame:
    """Same rows as seen by the app for a canyon without any report nor flow climatology."""
    cold = frame.copy()
    for index, row in enumerate(cold.itertuples(index=False)):
        priors = {
            "massifSeasonMeanRank": row.massifSeasonMeanRank,
            "massifSeasonFracHigh": row.massifSeasonFracHigh,
            "massifSeasonFracLow": getattr(row, MASSIF_FRAC_LOW),
            "massifSeasonWeightLog": row.massifSeasonWeightLog,
            "regionSeasonMeanRank": row.regionSeasonMeanRank,
            "regionSeasonFracHigh": row.regionSeasonFracHigh,
        }
        values = F.history_features([], int(row.day), F.from_epoch_day(int(row.day)).month, priors)
        for name in F.HISTORY_FEATURES:
            cold.iat[index, cold.columns.get_loc(name)] = F.finalize_value(values[name])
    for name in COLD_MISSING_FEATURES:
        cold[name] = F.MISSING
    return cold


def build_lookups(observations: pd.DataFrame, cutoff_day: int | None):
    """Monthly aggregates from observations strictly before ``cutoff_day`` (all if None)."""
    known = observations if cutoff_day is None else observations[observations["day"] < cutoff_day]
    rows = zip(known["massif"], known["region"], known["month"], known["rank"])
    return F.lookup_tables(rows)


def add_history(
    data: pd.DataFrame,
    histories: dict[int, list[tuple[int, int]]],
    observations: pd.DataFrame,
    cutoff_day: int | None,
) -> pd.DataFrame:
    """Return ``data`` with history columns computed causally."""
    days = data["day"].to_numpy()
    effective = days if cutoff_day is None else np.minimum(days, cutoff_day)
    order = np.argsort(effective, kind="stable")

    known = observations.sort_values("day")
    obs_days = known["day"].to_numpy()
    obs_massif = known["massif"].to_numpy()
    obs_region = known["region"].to_numpy()
    obs_month = known["month"].to_numpy()
    obs_rank = known["rank"].to_numpy()
    massifs: dict[str, F.MonthlyAggregate] = defaultdict(F.MonthlyAggregate)
    regions: dict[str, F.MonthlyAggregate] = defaultdict(F.MonthlyAggregate)
    global_ = F.MonthlyAggregate()
    pointer = 0

    columns = {name: np.empty(len(data)) for name in F.HISTORY_FEATURES + [MASSIF_FRAC_LOW]}
    canyon_ids = data["canyonId"].to_numpy()
    massif_keys = data["massif"].to_numpy()
    region_keys = data["region"].to_numpy()
    prior_cache: dict[tuple, dict[str, float]] = {}
    for index in order:
        horizon = int(effective[index])
        if pointer < len(obs_days) and obs_days[pointer] < horizon:
            prior_cache.clear()
            while pointer < len(obs_days) and obs_days[pointer] < horizon:
                massifs[obs_massif[pointer]].add(int(obs_month[pointer]), int(obs_rank[pointer]))
                regions[obs_region[pointer]].add(int(obs_month[pointer]), int(obs_rank[pointer]))
                global_.add(int(obs_month[pointer]), int(obs_rank[pointer]))
                pointer += 1
        day = int(days[index])
        month = F.from_epoch_day(day).month
        key = (massif_keys[index], region_keys[index], month)
        priors = prior_cache.get(key)
        if priors is None:
            priors = F.area_priors(month, massifs[key[0]], regions[key[1]], global_)
            prior_cache[key] = priors
        history = histories.get(int(canyon_ids[index]), [])
        past = history[:bisect.bisect_left(history, (day, -1))]
        values = F.history_features(past, day, month, priors)
        for name in F.HISTORY_FEATURES:
            columns[name][index] = F.finalize_value(values[name])
        columns[MASSIF_FRAC_LOW][index] = priors["massifSeasonFracLow"]
    output = data.copy()
    for name, values in columns.items():
        output[name] = values
    return output


def observations_frame(histories: dict[int, list[tuple[int, int]]], canyons: dict[int, dict]) -> pd.DataFrame:
    records = []
    for canyon_id, history in histories.items():
        canyon = canyons.get(canyon_id, {})
        massif = canyon.get("massif") or "__UNKNOWN__"
        region = canyon.get("region") or "__UNKNOWN__"
        for day, rank in history:
            records.append((canyon_id, day, rank, massif, region, F.from_epoch_day(day).month))
    return pd.DataFrame(records, columns=["canyonId", "day", "rank", "massif", "region", "month"])
