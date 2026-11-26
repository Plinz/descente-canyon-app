"""HTTP API (behind Caddy, which serves the static files and terminates TLS).

    GET    /v1/health
    GET    /v1/canyons/{canyonId}/tracks          anonymous read, ETag
    POST   /v1/canyons/{canyonId}/tracks?role=R   body: the GPX file, header X-Install-Key
    PUT    /v1/tracks/{trackId}/vote              body: {"value": 1 | 0 | -1}, header X-Install-Key
    DELETE /v1/tracks/{trackId}                   the author only, header X-Install-Key
    POST   /v1/canyon-proposals                   body: {"kind", "canyonId", "content", "licence"}, header X-Install-Key
    GET    /v1/canyon-proposals/mine              the proposals of this installation, header X-Install-Key
    DELETE /v1/canyon-proposals/{proposalId}      withdraws a pending proposal, header X-Install-Key

Errors are `{"error": CODE}` with the HTTP status of the code (see tracks.service and
canyons.service); a sheet refused by its checks also names the `field`.
"""
from __future__ import annotations

import hashlib
import json
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse, Response
from starlette.concurrency import run_in_threadpool

from .canyons.publication import repo_id_floor
from .canyons.service import CanyonService, ProposalError
from .config import Settings
from .tracks.canyons import CanyonReference
from .tracks.service import TrackError, TrackService

INSTALL_KEY_HEADER = "X-Install-Key"
MAX_PROPOSAL_BYTES = 256 * 1024


def client_ip(request: Request, trusted_proxy: bool) -> str:
    if trusted_proxy:
        forwarded = request.headers.get("x-forwarded-for")
        if forwarded:
            return forwarded.split(",")[0].strip()
    return request.client.host if request.client else "unknown"


def _error(code: str, status: int) -> JSONResponse:
    return JSONResponse({"error": code}, status_code=status, headers={"Cache-Control": "no-store"})


def _json(payload: dict, status: int = 200, extra_headers: dict[str, str] | None = None) -> Response:
    body = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    headers = {"Cache-Control": "no-store"}
    if extra_headers:
        headers.update(extra_headers)
    return Response(body, status_code=status, media_type="application/json", headers=headers)


async def _read_limited(request: Request, limit: int) -> bytes | None:
    declared = request.headers.get("content-length")
    if declared is not None and declared.isdigit() and int(declared) > limit:
        return None
    chunks: list[bytes] = []
    size = 0
    async for chunk in request.stream():
        size += len(chunk)
        if size > limit:
            return None
        chunks.append(chunk)
    return b"".join(chunks)


def create_app(settings: Settings, service: TrackService | None = None, canyon_service: CanyonService | None = None) -> FastAPI:
    holder: dict[str, object] = {}

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        reference = CanyonReference(settings.canyon_reference_path)
        holder["service"] = service or TrackService(settings.database_path, settings.pepper, reference)
        holder["canyons"] = canyon_service or CanyonService(
            settings.database_path,
            settings.pepper,
            id_floor=lambda: repo_id_floor(settings.repo_dir),
            locate=reference.place_near,
        )
        try:
            yield
        finally:
            if service is None:
                holder["service"].close()
            if canyon_service is None:
                holder["canyons"].close()

    app = FastAPI(title="Descente-Canyon app server", docs_url=None, redoc_url=None, openapi_url=None, lifespan=lifespan)

    def tracks() -> TrackService:
        return holder["service"]

    def canyons() -> CanyonService:
        return holder["canyons"]

    @app.exception_handler(TrackError)
    async def track_error(_: Request, exc: TrackError) -> JSONResponse:
        return _error(exc.code, exc.status)

    @app.exception_handler(ProposalError)
    async def proposal_error(_: Request, exc: ProposalError) -> JSONResponse:
        body = {"error": exc.code}
        if exc.field:
            body["field"] = exc.field
        return JSONResponse(body, status_code=exc.status, headers={"Cache-Control": "no-store"})

    @app.exception_handler(RequestValidationError)
    async def bad_request(_: Request, __: RequestValidationError) -> JSONResponse:
        return _error("BAD_REQUEST", 400)

    @app.get("/v1/health")
    async def health() -> Response:
        return _json({"status": "ok"})

    @app.get("/v1/canyons/{canyon_id}/tracks")
    async def list_tracks(canyon_id: int, request: Request) -> Response:
        payload = await run_in_threadpool(tracks().list_for_canyon, canyon_id)
        body = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        etag = '"' + hashlib.sha256(body).hexdigest()[:32] + '"'
        headers = {"ETag": etag, "Cache-Control": "no-cache"}
        if request.headers.get("if-none-match") == etag:
            return Response(status_code=304, headers=headers)
        return Response(body, media_type="application/json", headers=headers)

    @app.post("/v1/canyons/{canyon_id}/tracks")
    async def upload_track(canyon_id: int, request: Request, role: str = "") -> Response:
        data = await _read_limited(request, settings.max_upload_bytes)
        if data is None:
            return _error("TOO_LARGE", 413)
        track = await run_in_threadpool(
            tracks().upload,
            canyon_id,
            role,
            data,
            request.headers.get(INSTALL_KEY_HEADER),
            client_ip(request, settings.trusted_proxy),
        )
        return _json(track, status=201)

    @app.put("/v1/tracks/{track_id}/vote")
    async def vote(track_id: str, request: Request) -> Response:
        data = await _read_limited(request, 1024)
        try:
            value = json.loads(data or b"{}").get("value")
        except (ValueError, AttributeError):
            value = None
        if not isinstance(value, int) or isinstance(value, bool):
            return _error("BAD_VOTE", 400)
        track = await run_in_threadpool(
            tracks().vote,
            track_id,
            value,
            request.headers.get(INSTALL_KEY_HEADER),
            client_ip(request, settings.trusted_proxy),
        )
        return _json(track)

    @app.delete("/v1/tracks/{track_id}")
    async def delete_track(track_id: str, request: Request) -> Response:
        await run_in_threadpool(tracks().delete, track_id, request.headers.get(INSTALL_KEY_HEADER))
        return Response(status_code=204, headers={"Cache-Control": "no-store"})

    @app.post("/v1/canyon-proposals")
    async def propose(request: Request) -> Response:
        data = await _read_limited(request, MAX_PROPOSAL_BYTES)
        if data is None:
            return _error("TOO_LARGE", 413)
        try:
            body = json.loads(data or b"{}")
        except ValueError:
            return _error("BAD_REQUEST", 400)
        proposal = await run_in_threadpool(
            canyons().submit,
            body,
            request.headers.get(INSTALL_KEY_HEADER),
            client_ip(request, settings.trusted_proxy),
        )
        return _json(proposal, status=201)

    @app.get("/v1/canyon-proposals/mine")
    async def my_proposals(request: Request) -> Response:
        return _json(await run_in_threadpool(canyons().mine, request.headers.get(INSTALL_KEY_HEADER)))

    @app.delete("/v1/canyon-proposals/{proposal_id}")
    async def withdraw_proposal(proposal_id: str, request: Request) -> Response:
        await run_in_threadpool(canyons().withdraw, proposal_id, request.headers.get(INSTALL_KEY_HEADER))
        return Response(status_code=204, headers={"Cache-Control": "no-store"})

    return app
