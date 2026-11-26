"""Review tool of the canyons proposed in the app (`dcserver admin`).

A small web page and its JSON API, for the operator only. It listens on the VPS's loopback
(published by Docker on 127.0.0.1 only) and is reached through an SSH tunnel:

    ssh -N -L 8090:127.0.0.1:8090 dc@<domain>      then open http://localhost:8090

Every API call carries the token of DC_ADMIN_TOKEN (typed once in the page, kept by the browser tab
only); the Host header is checked so that a web page open in the same browser cannot reach the tool
through a DNS name of its own. The tool never signs nor publishes anything: the app-canyons job puts
the approved sheets into the catalog within ten minutes.

    GET  /api/status                                    pending proposals, publication
    GET  /api/proposals?status=PENDING|DECIDED
    GET  /api/proposals/{id}                            sheet, changes, nearby canyons, author
    POST /api/proposals/{id}/approve                    {"content"?: corrected sheet, "note"?}
    POST /api/proposals/{id}/reject                     {"reason", "note"?}
    POST /api/proposals/{id}/block | /unblock           the author of the proposal
    GET  /api/canyons                                   the canyons added in the app
    POST /api/canyons                                   {"content", "note"?}: added by the operator
    GET  /api/canyons/{id}                              current sheet, revisions, nearby canyons
    PUT  /api/canyons/{id}                              {"content", "note"?}: a new revision
    GET  /api/canyons/{id}/revisions/{revision}
    POST /api/canyons/{id}/restore                      {"revision", "note"?}
    POST /api/canyons/{id}/hidden                       {"hidden": true | false}
    GET  /api/nearby?lat=&lon=&radius=                  canyons of the catalog around a point
"""
from __future__ import annotations

import asyncio
import hmac
import json
from contextlib import asynccontextmanager
from importlib import resources
from typing import Any

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse, PlainTextResponse, Response
from starlette.concurrency import run_in_threadpool

from ..canyons import content as sheets
from ..canyons import publication
from ..canyons.service import REJECTION_REASONS, CanyonService, ProposalError
from ..config import Settings
from ..tracks.canyons import CanyonReference

TOKEN_HEADER = "X-Admin-Token"
MAX_BODY_BYTES = 512 * 1024
NEARBY_RADIUS_M = 5_000
PAGE_HEADERS = {
    "Cache-Control": "no-store",
    "X-Frame-Options": "DENY",
    "Referrer-Policy": "no-referrer",
    "X-Content-Type-Options": "nosniff",
    "Content-Security-Policy": (
        "default-src 'none'; script-src 'self' 'unsafe-inline' https://unpkg.com; style-src 'self' 'unsafe-inline' https://unpkg.com; "
        "img-src 'self' data: https://tile.openstreetmap.org https://*.tile.opentopomap.org https://data.geopf.fr https://unpkg.com; "
        "connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'"
    ),
}


def host_name(raw: str | None) -> str:
    """The host of a Host header, without its port ("[::1]:8090" -> "::1")."""
    value = (raw or "").strip().lower()
    if value.startswith("["):
        return value[1 : value.find("]")] if "]" in value else value
    return value.rsplit(":", 1)[0] if value.count(":") == 1 else value


def nearby(reference: CanyonReference, points: list[dict] | list[tuple[float, float]], radius_m: float = NEARBY_RADIUS_M, exclude: int | None = None) -> list[dict]:
    """The canyons of the catalog with a point within [radius_m] of one of [points], nearest first."""
    targets = [(p["latitude"], p["longitude"]) if isinstance(p, dict) else (p[0], p[1]) for p in points]
    if not targets:
        return []
    found = []
    for canyon_id, entry in reference.entries().items():
        if canyon_id == exclude:
            continue
        best = None
        for lat, lon in entry.get("points", []):
            for t_lat, t_lon in targets:
                if abs(lat - t_lat) > 0.1 or abs(lon - t_lon) > 0.15:
                    continue
                distance = sheets.distance_m(lat, lon, t_lat, t_lon)
                if best is None or distance < best:
                    best = distance
        if best is not None and best <= radius_m:
            found.append(
                {
                    "id": canyon_id,
                    "name": entry.get("name", ""),
                    "distanceM": round(best),
                    "forbidden": bool(entry.get("forbidden")),
                    "app": bool(entry.get("app")),
                    "points": entry.get("points", []),
                }
            )
    found.sort(key=lambda item: item["distanceM"])
    return found[:40]


def _latest_report(settings: Settings, job: str) -> dict | None:
    reports = sorted(settings.reports_dir.glob(f"{job}-*.json"), reverse=True) if settings.reports_dir.exists() else []
    for path in reports[:1]:
        try:
            report = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return None
        return {key: report.get(key) for key in ("startedAt", "finishedAt", "published", "unchanged", "aborted", "detail", "appCanyons", "dataVersion")}
    return None


def create_admin_app(settings: Settings, service: CanyonService | None = None, reference: CanyonReference | None = None) -> FastAPI:
    holder: dict[str, Any] = {}

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        holder["reference"] = reference or CanyonReference(settings.canyon_reference_path)
        holder["service"] = service or CanyonService(
            settings.database_path,
            settings.pepper,
            id_floor=lambda: publication.repo_id_floor(settings.repo_dir),
            locate=holder["reference"].place_near,
        )
        try:
            yield
        finally:
            if service is None:
                holder["service"].close()

    app = FastAPI(title="Descente-Canyon review tool", docs_url=None, redoc_url=None, openapi_url=None, lifespan=lifespan)
    allowed_hosts = set(settings.admin_hosts)

    def canyons() -> CanyonService:
        return holder["service"]

    def catalog() -> CanyonReference:
        return holder["reference"]

    @app.middleware("http")
    async def guard(request: Request, call_next):
        if host_name(request.headers.get("host")) not in allowed_hosts:
            return PlainTextResponse("Unknown host", status_code=421)
        if request.url.path.startswith("/api/"):
            if not settings.admin_token:
                return JSONResponse({"error": "ADMIN_TOKEN_NOT_SET"}, status_code=503)
            given = request.headers.get(TOKEN_HEADER) or ""
            if not hmac.compare_digest(given.encode("utf-8"), settings.admin_token.encode("utf-8")):
                await asyncio.sleep(0.5)
                return JSONResponse({"error": "BAD_TOKEN"}, status_code=401)
        response = await call_next(request)
        for name, value in PAGE_HEADERS.items():
            response.headers.setdefault(name, value)
        return response

    @app.exception_handler(ProposalError)
    async def proposal_error(_: Request, exc: ProposalError) -> JSONResponse:
        body = {"error": exc.code}
        if exc.field:
            body["field"] = exc.field
        return JSONResponse(body, status_code=exc.status)

    async def body_of(request: Request) -> dict:
        data = await request.body()
        if len(data) > MAX_BODY_BYTES:
            raise ProposalError("TOO_LARGE", 413)
        try:
            body = json.loads(data or b"{}")
        except ValueError as exc:
            raise ProposalError("BAD_REQUEST", 400) from exc
        if not isinstance(body, dict):
            raise ProposalError("BAD_REQUEST", 400)
        return body

    def ok(payload: Any, status: int = 200) -> Response:
        return Response(json.dumps(payload, ensure_ascii=False), status_code=status, media_type="application/json")

    @app.get("/")
    async def page() -> Response:
        html = resources.files("dcserver.admin").joinpath("static/index.html").read_text(encoding="utf-8")
        return Response(html, media_type="text/html; charset=utf-8")

    @app.get("/api/status")
    async def status() -> Response:
        def compute() -> dict:
            export = canyons().export()
            try:
                repo_document = publication.load_repo_document(settings.repo_dir)
            except (OSError, ValueError):
                repo_document = None
            merged = publication.merged_document(export, repo_document)
            state_path = settings.data_dir / "catalog" / "state.json"
            try:
                state = json.loads(state_path.read_text(encoding="utf-8"))
            except (OSError, ValueError):
                state = {}
            return {
                "pendingProposals": canyons().pending_count(),
                "publishedCanyons": len(export["canyons"]),
                "hiddenCanyons": len(export["hidden"]),
                "repoCanyons": len((repo_document or {}).get("canyons", [])),
                # Nothing added yet and no catalog built since this tool exists: nothing waits either.
                "publicationUpToDate": state.get("appCanyonsDigest") == publication.digest(merged)
                or (not merged["canyons"] and "appCanyonsDigest" not in state),
                "lastCatalogJob": _latest_report(settings, "catalog"),
                "rejectionReasons": list(REJECTION_REASONS[:-1]),
                "pointTypes": list(sheets.POINT_TYPES),
            }

        return ok(await run_in_threadpool(compute))

    @app.get("/api/proposals")
    async def proposals(status: str = "PENDING") -> Response:
        selected = None if status == "DECIDED" else status
        return ok(await run_in_threadpool(canyons().proposals, selected))

    @app.get("/api/proposals/{proposal_id}")
    async def proposal(proposal_id: str) -> Response:
        def compute() -> dict:
            result = canyons().proposal(proposal_id)
            result["nearby"] = nearby(catalog(), result["content"].get("points", []), exclude=result.get("canyonId"))
            return result

        return ok(await run_in_threadpool(compute))

    @app.post("/api/proposals/{proposal_id}/approve")
    async def approve(proposal_id: str, request: Request) -> Response:
        body = await body_of(request)
        return ok(await run_in_threadpool(canyons().approve, proposal_id, body.get("content"), body.get("note")))

    @app.post("/api/proposals/{proposal_id}/reject")
    async def reject(proposal_id: str, request: Request) -> Response:
        body = await body_of(request)
        return ok(await run_in_threadpool(canyons().reject, proposal_id, body.get("reason"), body.get("note")))

    @app.post("/api/proposals/{proposal_id}/block")
    async def block(proposal_id: str) -> Response:
        return ok({"rejected": await run_in_threadpool(canyons().block_author, proposal_id)})

    @app.post("/api/proposals/{proposal_id}/unblock")
    async def unblock(proposal_id: str) -> Response:
        return ok({"unblocked": await run_in_threadpool(canyons().unblock_author, proposal_id)})

    @app.get("/api/canyons")
    async def list_canyons() -> Response:
        return ok(await run_in_threadpool(canyons().canyons))

    @app.post("/api/canyons")
    async def create(request: Request) -> Response:
        body = await body_of(request)
        return ok(await run_in_threadpool(canyons().create, body.get("content"), body.get("note")), status=201)

    @app.get("/api/canyons/{canyon_id}")
    async def canyon(canyon_id: int) -> Response:
        def compute() -> dict:
            result = canyons().canyon(canyon_id)
            result["nearby"] = nearby(catalog(), result["content"].get("points", []), exclude=canyon_id)
            return result

        return ok(await run_in_threadpool(compute))

    @app.put("/api/canyons/{canyon_id}")
    async def edit(canyon_id: int, request: Request) -> Response:
        body = await body_of(request)
        return ok(await run_in_threadpool(canyons().edit, canyon_id, body.get("content"), body.get("note")))

    @app.get("/api/canyons/{canyon_id}/revisions/{revision}")
    async def revision(canyon_id: int, revision: int) -> Response:
        return ok(await run_in_threadpool(canyons().revision, canyon_id, revision))

    @app.post("/api/canyons/{canyon_id}/restore")
    async def restore(canyon_id: int, request: Request) -> Response:
        body = await body_of(request)
        number = body.get("revision")
        if isinstance(number, bool) or not isinstance(number, int):
            raise ProposalError("BAD_REQUEST", 400)
        return ok(await run_in_threadpool(canyons().restore, canyon_id, number, body.get("note")))

    @app.post("/api/canyons/{canyon_id}/hidden")
    async def hidden(canyon_id: int, request: Request) -> Response:
        body = await body_of(request)
        if not isinstance(body.get("hidden"), bool):
            raise ProposalError("BAD_REQUEST", 400)
        return ok(await run_in_threadpool(canyons().set_hidden, canyon_id, body["hidden"]))

    @app.get("/api/nearby")
    async def around(lat: float, lon: float, radius: float = NEARBY_RADIUS_M) -> Response:
        radius = max(100.0, min(radius, 20_000.0))
        return ok(await run_in_threadpool(nearby, catalog(), [(lat, lon)], radius))

    return app
