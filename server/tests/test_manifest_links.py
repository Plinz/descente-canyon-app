from __future__ import annotations

import pytest

from dcserver import manifest


def state_with(message: dict) -> dict:
    state = manifest.default_state()
    state["messages"] = [message]
    return state


def test_a_message_may_carry_an_https_link():
    base = {"id": "m1", "level": "info", "text": {"fr": "Nouveau canyon validé"}}
    manifest.validate_state(state_with({**base, "link": "https://dc-app.fr/c/1000012"}))
    with pytest.raises(ValueError):
        manifest.validate_state(state_with({**base, "link": "http://example.org"}))
    with pytest.raises(ValueError):
        manifest.validate_state(state_with({**base, "link": "javascript:alert(1)"}))
