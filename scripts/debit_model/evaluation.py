"""Decision rule and metrics for the six-level ordinal debit prediction."""
from __future__ import annotations

import numpy as np

LEVEL_COUNT = 6
HIGH_RANK = 3
UNDER_HIGH_PENALTY = 2.5  # under-estimating GROS+ costs 2.5x (chosen on leave-one-fold-out OOF: best high-flow F1)


def cumulative(probabilities: np.ndarray) -> np.ndarray:
    """C[:, k-1] = P(rank >= k) for k = 1..5."""
    tail = np.cumsum(probabilities[:, ::-1], axis=1)[:, ::-1]
    return tail[:, 1:]


def decide(probabilities: np.ndarray, thresholds: np.ndarray) -> np.ndarray:
    """Ordinal decision: highest k such that P(rank >= k) >= tau_k (monotone by construction)."""
    c = cumulative(probabilities)
    # a level is reachable only if all lower cumulative thresholds pass
    passed = np.ones(len(probabilities), dtype=bool)
    monotone = np.zeros(len(probabilities), dtype=np.int64)
    for k in range(1, LEVEL_COUNT):
        passed &= c[:, k - 1] >= thresholds[k - 1]
        monotone = np.where(passed, k, monotone)
    return monotone


def display_score(probabilities: np.ndarray, thresholds: np.ndarray) -> np.ndarray:
    """Continuous score in [level - 0.5, level + 0.5) used by the app gauge (cutpoints k + 0.5)."""
    c = np.concatenate([np.ones((len(probabilities), 1)), cumulative(probabilities), np.zeros((len(probabilities), 1))], axis=1)
    tau = np.concatenate([[0.0], thresholds, [1.0]])
    level = decide(probabilities, thresholds)
    rows = np.arange(len(probabilities))
    up = np.clip(c[rows, level + 1] / np.maximum(tau[level + 1], 1e-9), 0.0, 1.0)
    up = np.where(level == LEVEL_COUNT - 1, 0.0, up)
    down = np.clip((1.0 - c[rows, level]) / np.maximum(1.0 - tau[level], 1e-9), 0.0, 1.0)
    down = np.where(level == 0, 0.0, down)
    return level + np.clip(0.45 * (up - down), -0.49, 0.49)


def cost(y: np.ndarray, pred: np.ndarray) -> np.ndarray:
    diff = pred - y
    weight = np.where((diff < 0) & (y >= HIGH_RANK), UNDER_HIGH_PENALTY, 1.0)
    return np.abs(diff) * weight


def tune_thresholds(y: np.ndarray, probabilities: np.ndarray, sample_weight: np.ndarray | None = None) -> np.ndarray:
    """Coordinate descent on tau_1..tau_5 minimising the asymmetric ordinal cost."""
    grid = np.round(np.arange(0.15, 0.86, 0.01), 2)
    thresholds = np.full(LEVEL_COUNT - 1, 0.5)
    weights = np.ones(len(y)) if sample_weight is None else sample_weight

    def score(t):
        return float(np.average(cost(y, decide(probabilities, t)), weights=weights))

    best = score(thresholds)
    for _ in range(4):
        improved = False
        for k in range(LEVEL_COUNT - 1):
            for value in grid:
                candidate = thresholds.copy()
                candidate[k] = value
                current = score(candidate)
                if current < best - 1e-9:
                    best, thresholds, improved = current, candidate, True
        if not improved:
            break
    return thresholds


def risk_thresholds(y: np.ndarray, probabilities: np.ndarray) -> dict[str, float]:
    """Thresholds on P(rank >= GROS) for the three app policies."""
    p_high = cumulative(probabilities)[:, HIGH_RANK - 1]
    truth = y >= HIGH_RANK
    best_f1, balanced = -1.0, 0.5
    candidates = np.round(np.arange(0.05, 0.95, 0.01), 2)
    stats = []
    for t in candidates:
        predicted = p_high >= t
        tp = float((predicted & truth).sum())
        precision = tp / max(predicted.sum(), 1)
        recall = tp / max(truth.sum(), 1)
        f1 = 2 * precision * recall / max(precision + recall, 1e-9)
        stats.append((t, precision, recall))
        if f1 > best_f1:
            best_f1, balanced = f1, float(t)
    safety = max([t for t, _, r in stats if r >= 0.90] or [0.05])
    prudent = min([t for t, p, _ in stats if p >= 0.80] or [0.9])
    prudent = max(prudent, balanced)
    return {"balanced": balanced, "prudent": float(prudent), "safety_first": float(min(safety, balanced))}


def ranked_probability_score(y: np.ndarray, probabilities: np.ndarray) -> float:
    c_pred = np.cumsum(probabilities, axis=1)[:, :-1]
    c_true = (np.arange(LEVEL_COUNT - 1)[None, :] >= y[:, None]).astype(float)
    return float(np.mean(np.sum((c_pred - c_true) ** 2, axis=1) / (LEVEL_COUNT - 1)))


def auc(scores: np.ndarray, truth: np.ndarray) -> float | None:
    positives = truth.sum()
    negatives = len(truth) - positives
    if positives == 0 or negatives == 0:
        return None
    order = np.argsort(scores)
    ranks = np.empty(len(scores))
    ranks[order] = np.arange(1, len(scores) + 1)
    return float((ranks[truth].sum() - positives * (positives + 1) / 2) / (positives * negatives))


def level_metrics(y: np.ndarray, pred: np.ndarray) -> dict[str, float]:
    diff = pred - y
    truth_high = y >= HIGH_RANK
    pred_high = pred >= HIGH_RANK
    tp = float((truth_high & pred_high).sum())
    precision = tp / max(pred_high.sum(), 1)
    recall = tp / max(truth_high.sum(), 1)
    f1_per_class = []
    for k in range(LEVEL_COUNT):
        tpk = float(((y == k) & (pred == k)).sum())
        pk = tpk / max((pred == k).sum(), 1)
        rk = tpk / max((y == k).sum(), 1)
        if (y == k).sum() > 0:
            f1_per_class.append(2 * pk * rk / max(pk + rk, 1e-9))
    return {
        "n": int(len(y)),
        "accuracy": float((diff == 0).mean()),
        "within1": float((np.abs(diff) <= 1).mean()),
        "mae": float(np.abs(diff).mean()),
        "macroF1": float(np.mean(f1_per_class)) if f1_per_class else 0.0,
        "severeError": float((np.abs(diff) >= 2).mean()),
        "severeUnderHigh": float(((y >= HIGH_RANK) & (diff <= -2)).sum() / max(truth_high.sum(), 1)),
        "highPrecision": precision,
        "highRecall": recall,
        "highF1": 2 * precision * recall / max(precision + recall, 1e-9),
        "cost": float(cost(y, pred).mean()),
    }


def probabilistic_metrics(y: np.ndarray, probabilities: np.ndarray) -> dict[str, float | None]:
    p_high = cumulative(probabilities)[:, HIGH_RANK - 1]
    truth = y >= HIGH_RANK
    return {
        "rps": ranked_probability_score(y, probabilities),
        "highBrier": float(np.mean((p_high - truth) ** 2)),
        "highAuc": auc(p_high, truth),
        "logLoss": float(-np.mean(np.log(np.clip(probabilities[np.arange(len(y)), y], 1e-12, 1.0)))),
    }


def onehot_probabilities(levels: np.ndarray) -> np.ndarray:
    probabilities = np.full((len(levels), LEVEL_COUNT), 0.0)
    probabilities[np.arange(len(levels)), np.clip(levels, 0, LEVEL_COUNT - 1)] = 1.0
    return probabilities


def calibration_table(y: np.ndarray, probabilities: np.ndarray, bins: int = 10) -> list[dict[str, float]]:
    p_high = cumulative(probabilities)[:, HIGH_RANK - 1]
    truth = (y >= HIGH_RANK).astype(float)
    edges = np.linspace(0, 1, bins + 1)
    table = []
    for lo, hi in zip(edges[:-1], edges[1:]):
        mask = (p_high >= lo) & (p_high < hi if hi < 1 else p_high <= hi)
        if mask.sum() >= 20:
            table.append({"bin": f"{lo:.1f}-{hi:.1f}", "n": int(mask.sum()), "meanPredicted": float(p_high[mask].mean()), "observed": float(truth[mask].mean())})
    return table
