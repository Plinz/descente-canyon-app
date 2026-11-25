"""What the votes say about a track, without any moderator.

Every installation has one vote per track (+1 reliable, -1 wrong or dangerous) and cannot vote on
its own tracks. One network (IPv4 /24, IPv6 /48) weighs at most [MAX_VOTES_PER_NETWORK] votes of
each sign on a track, so a single person reinstalling the app cannot outvote the others.

Standing, from the effective counts:
- ENDORSED: at least 3 up and three times more up than down; the best endorsed track of each role
  is the reference one, drawn first on the map;
- DISCREDITED: at least 3 down and twice more down than up; hidden unless asked for;
- DISPUTED: any down vote otherwise;
- NEW: everything else (no vote yet, or a few up votes).
"""
from __future__ import annotations

import math
from collections import Counter
from collections.abc import Iterable
from dataclasses import dataclass

MAX_VOTES_PER_NETWORK = 2

NEW = "NEW"
ENDORSED = "ENDORSED"
DISPUTED = "DISPUTED"
DISCREDITED = "DISCREDITED"


@dataclass(frozen=True)
class Rules:
    endorse_min_up: int = 3
    endorse_ratio: float = 3.0
    discredit_min_down: int = 3
    discredit_ratio: float = 2.0
    max_votes_per_network: int = MAX_VOTES_PER_NETWORK


@dataclass(frozen=True)
class VoteCounts:
    up: int
    down: int


def effective_counts(votes: Iterable[tuple[str, int]], rules: Rules = Rules()) -> VoteCounts:
    """[votes] are (network hash, value) pairs; each network counts at most N votes of each sign."""
    per_network = Counter((network, value) for network, value in votes)
    up = sum(min(count, rules.max_votes_per_network) for (_, value), count in per_network.items() if value > 0)
    down = sum(min(count, rules.max_votes_per_network) for (_, value), count in per_network.items() if value < 0)
    return VoteCounts(up=up, down=down)


def standing(counts: VoteCounts, rules: Rules = Rules()) -> str:
    if counts.down >= rules.discredit_min_down and counts.down >= rules.discredit_ratio * counts.up:
        return DISCREDITED
    if counts.up >= rules.endorse_min_up and counts.up >= rules.endorse_ratio * counts.down:
        return ENDORSED
    if counts.down > 0:
        return DISPUTED
    return NEW


def wilson_lower_bound(up: int, total: int, z: float = 1.96) -> float:
    if total == 0:
        return 0.0
    phat = up / total
    denominator = 1 + z * z / total
    centre = phat + z * z / (2 * total)
    margin = z * math.sqrt((phat * (1 - phat) + z * z / (4 * total)) / total)
    return (centre - margin) / denominator


def score(counts: VoteCounts) -> float:
    """Ranking score: the lower bound of the share of up votes (few votes rank below many)."""
    return wilson_lower_bound(counts.up, counts.up + counts.down)
