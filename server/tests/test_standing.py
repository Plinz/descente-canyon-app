from __future__ import annotations

from dcserver.tracks import standing
from dcserver.tracks.standing import VoteCounts


def test_one_network_weighs_at_most_two_votes_of_each_sign():
    votes = [("net-a", 1)] * 5 + [("net-b", 1), ("net-a", -1)]
    assert standing.effective_counts(votes) == VoteCounts(up=3, down=1)


def test_standing_rules():
    assert standing.standing(VoteCounts(0, 0)) == standing.NEW
    assert standing.standing(VoteCounts(2, 0)) == standing.NEW
    assert standing.standing(VoteCounts(3, 0)) == standing.ENDORSED
    assert standing.standing(VoteCounts(5, 1)) == standing.ENDORSED
    assert standing.standing(VoteCounts(5, 2)) == standing.DISPUTED
    assert standing.standing(VoteCounts(0, 1)) == standing.DISPUTED
    assert standing.standing(VoteCounts(1, 3)) == standing.DISCREDITED
    assert standing.standing(VoteCounts(2, 3)) == standing.DISPUTED
    assert standing.standing(VoteCounts(0, 3)) == standing.DISCREDITED


def test_score_prefers_many_agreeing_votes():
    assert standing.score(VoteCounts(0, 0)) == 0.0
    assert standing.score(VoteCounts(10, 0)) > standing.score(VoteCounts(2, 0)) > standing.score(VoteCounts(2, 2))
