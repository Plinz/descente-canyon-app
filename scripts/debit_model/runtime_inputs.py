"""Inputs of one canyon read from the runtime lookups asset (runtime_feature_lookups.json).

Python twins of EmbeddedDebitRuntimeLookupStore: shared by the asset export and the app server,
which computes the estimates without CatBoost.
"""
from __future__ import annotations

UNKNOWN = "__UNKNOWN__"


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


def decode_history(values: dict[str, float]) -> list[tuple[int, int]]:
    """Reports shipped in the lookups (``h<i>`` = epoch day x 10 + rank), as DebitFeatureBuilder.decodeLookupHistory."""
    return sorted(
        (int(round(v)) // 10, int(round(v)) % 10)
        for k, v in values.items()
        if k.startswith("h") and len(k) > 1 and k[1:].isdigit()
    )
