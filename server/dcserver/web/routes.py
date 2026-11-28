"""Routes of the shared links: the pages a browser shows, and the Digital Asset Links file that lets
Android open these links straight in the app."""
from __future__ import annotations

import json
from collections.abc import Callable
from pathlib import Path

from fastapi import FastAPI, Request
from fastapi.responses import HTMLResponse, Response
from starlette.concurrency import run_in_threadpool
from fastapi.staticfiles import StaticFiles

from ..config import Settings
from ..shares.service import ShareError, ShareService
from ..tracks.canyons import CanyonReference
from . import html, links
from .i18n import negotiate

STATIC_DIR = Path(__file__).parent / "static"
PAGE_MAX_AGE_S = 3600


def asset_links(settings: Settings) -> list[dict]:
    """Which apps may open the links of this domain: the app (release key, Play app signing key) and,
    when its debug key is given, the debug build."""

    def statement(package: str, fingerprints: tuple[str, ...]) -> dict:
        return {
            "relation": ["delegate_permission/common.handle_all_urls"],
            "target": {"namespace": "android_app", "package_name": package, "sha256_cert_fingerprints": list(fingerprints)},
        }

    statements = [statement(settings.android_package, settings.android_cert_sha256)]
    if settings.android_debug_cert_sha256:
        statements.append(statement(settings.android_package + ".debug", settings.android_debug_cert_sha256))
    return statements


def context_of(request: Request, settings: Settings) -> html.PageContext:
    return html.PageContext(
        language=negotiate(request.headers.get("accept-language")),
        public_url=settings.public_url,
        package=settings.android_package,
        is_android="android" in request.headers.get("user-agent", "").lower(),
    )


def html_response(body: str, status: int = 200, max_age: int = PAGE_MAX_AGE_S) -> HTMLResponse:
    headers = dict(html.SECURITY_HEADERS)
    headers["Cache-Control"] = f"public, max-age={max_age}" if status == 200 and max_age > 0 else "no-store"
    headers["Vary"] = "Accept-Language, User-Agent"
    return HTMLResponse(body, status_code=status, headers=headers)


def link_of(request: Request, settings: Settings) -> str:
    """The shared link as the app reads it: this domain's address, whatever host the request came by."""
    query = request.url.query
    return settings.public_url + request.url.path + (f"?{query}" if query else "")


def parse_point(request: Request) -> tuple[float, float] | None:
    try:
        latitude = float(request.query_params["lat"])
        longitude = float(request.query_params["lon"])
    except (KeyError, ValueError):
        return None
    if not (-90 <= latitude <= 90 and -180 <= longitude <= 180):
        return None
    return latitude, longitude


def register_pages(
    app: FastAPI,
    settings: Settings,
    reference: Callable[[], CanyonReference],
    shares: Callable[[], ShareService] | None = None,
) -> None:
    app.mount("/static", StaticFiles(directory=STATIC_DIR), name="static")

    @app.get("/.well-known/assetlinks.json")
    async def well_known_asset_links() -> Response:
        body = json.dumps(asset_links(settings), separators=(",", ":")).encode("utf-8")
        return Response(body, media_type="application/json", headers={"Cache-Control": "public, max-age=3600"})

    @app.get("/")
    async def landing(request: Request) -> HTMLResponse:
        return html_response(html.landing_page(context_of(request, settings)))

    async def canyon(request: Request, ref: str, section: str | None) -> HTMLResponse:
        ctx = context_of(request, settings)
        canyon_id = links.parse_canyon_ref(ref)
        if canyon_id is None or (section is not None and section not in links.CANYON_SECTIONS):
            return html_response(html.not_found_page(ctx), status=404)
        entry = reference().entry(canyon_id)
        if entry is None:
            return html_response(html.not_found_page(ctx, "unknown_canyon"), status=404)
        point = parse_point(request) if section == "carte" else None
        return html_response(html.canyon_page(ctx, canyon_id, entry, link_of(request, settings), point))

    @app.get("/c/{ref}")
    async def canyon_page(request: Request, ref: str) -> HTMLResponse:
        return await canyon(request, ref, None)

    @app.get("/c/{ref}/{section}")
    async def canyon_section_page(request: Request, ref: str, section: str) -> HTMLResponse:
        return await canyon(request, ref, section)

    @app.get("/o/{ref}")
    async def outing_in_link_page(request: Request, ref: str) -> HTMLResponse:
        ctx = context_of(request, settings)
        canyon_id = links.parse_canyon_ref(ref)
        entry = reference().entry(canyon_id) if canyon_id is not None else None
        if entry is None:
            return html_response(html.not_found_page(ctx, "unknown_canyon"), status=404)
        return html_response(html.outing_in_link_page(ctx, canyon_id, entry, link_of(request, settings)))

    if shares is None:
        return

    @app.get("/s/{code}")
    async def share_page(request: Request, code: str) -> HTMLResponse:
        ctx = context_of(request, settings)
        try:
            share = await run_in_threadpool(shares().get, code)
        except ShareError:
            return html_response(html.not_found_page(ctx), status=404)
        entries = reference().entries()
        # The organizer may change it: browsers and link previews ask again after a few minutes.
        return html_response(html.share_page(ctx, share, entries, link_of(request, settings)), max_age=300)
