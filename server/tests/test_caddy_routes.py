"""Every route of the API is forwarded by Caddy (server/deploy/Caddyfile).

Caddy sends only the paths its matchers name to the API: any other /v1/* path goes to the static
files, which answer 404. A route added to the API without its Caddy path works in the tests and
fails on the server (it happened to the idea box).
"""
from __future__ import annotations

import re
from fnmatch import fnmatchcase
from pathlib import Path

from fastapi.routing import APIRoute
from starlette.routing import Mount

from dcserver.api import create_app

CADDYFILE = Path(__file__).resolve().parents[1] / "deploy" / "Caddyfile"
PROXIED_MATCHERS = ("api", "pages")


def caddy_patterns(name: str) -> list[str]:
    match = re.search(rf"^\s*@{name}\s+path\s+(.+)$", CADDYFILE.read_text(), re.MULTILINE)
    assert match, f"no @{name} path matcher in {CADDYFILE}"
    return match.group(1).split()


def sample_path(route_path: str) -> str:
    """A concrete path for a route template: /v1/feedback/{feedback_id} -> /v1/feedback/x1."""
    return re.sub(r"\{[^}]+\}", "x1", route_path)


def test_every_api_route_is_forwarded_by_caddy(settings) -> None:
    patterns = [pattern for name in PROXIED_MATCHERS for pattern in caddy_patterns(name)]
    app = create_app(settings)
    paths = [sample_path(route.path) for route in app.routes if isinstance(route, APIRoute)]
    paths += [f"{route.path}/x1" for route in app.routes if isinstance(route, Mount)]
    assert any(path.startswith("/v1/feedback") for path in paths), "the route list looks wrong"

    missed = [path for path in paths if not any(fnmatchcase(path, pattern) for pattern in patterns)]

    assert missed == [], f"paths that Caddy would not forward to the API: {missed}"
