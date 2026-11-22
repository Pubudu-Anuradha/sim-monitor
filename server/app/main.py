"""Two ASGI apps sharing one core:

- dashboard app: plain HTTP on 127.0.0.1 (REST API, SSE, built dashboard).
- phone app: WebSocket at /ws, served over TLS on the LAN.
"""

import io
import os
from dataclasses import dataclass
from urllib.parse import urlencode

import segno
from fastapi import FastAPI, HTTPException, Request, WebSocket
from fastapi.responses import FileResponse, JSONResponse, Response, StreamingResponse
from fastapi.staticfiles import StaticFiles

from . import schemas
from .config import Settings
from .db import Database
from .events import Events
from .hub import Hub
from .tls import Tls


@dataclass
class Core:
    settings: Settings
    db: Database
    events: Events
    hub: Hub
    tls: Tls | None

    @classmethod
    def create(cls, settings: Settings, with_tls: bool = True) -> "Core":
        db = Database(settings.db_path)
        db.mark_all_offline()  # nothing is connected at startup
        events = Events()
        if with_tls and not settings.public_host:
            raise SystemExit(
                "SIM_MONITOR_HOST is not set: give this desktop a fixed LAN IP and put it in .env "
                "(see .env.example)."
            )
        tls = Tls(settings.data_dir, settings.public_host) if with_tls else None
        if tls:
            tls.ensure()
        return cls(settings, db, events, Hub(db, events, settings.token()), tls)


LOOPBACK = {"127.0.0.1", "::1", "localhost", "testclient"}


def create_dashboard_app(core: Core) -> FastAPI:
    app = FastAPI(title="SIM Monitor", version=os.environ.get("SIM_MONITOR_VERSION", "dev"))
    hub, db = core.hub, core.db

    # Endpoints are async on purpose: they share the hub (tasks, SSE queues) with the phone socket,
    # which is not thread-safe, so they must run on the event loop rather than in the threadpool.

    @app.middleware("http")
    async def loopback_only(request: Request, call_next):  # type: ignore[no-untyped-def]
        # The dashboard listener binds to 127.0.0.1; this is a second guard in case it's ever exposed.
        if request.client is None or request.client.host not in LOOPBACK:
            return JSONResponse({"detail": "dashboard is local only"}, status_code=403)
        return await call_next(request)

    def sim_or_404(sim_id: str) -> None:
        if db.sim(sim_id) is None:
            raise HTTPException(404, "unknown SIM")

    @app.get("/api/health")
    async def health() -> dict[str, str]:
        return {"status": "ok"}

    @app.get("/api/sims")
    async def list_sims() -> list[schemas.Sim]:
        return [schemas.Sim(**s) for s in hub.sims()]

    @app.patch("/api/sims/{sim_id}")
    async def update_sim(sim_id: str, body: schemas.SimUpdate) -> schemas.Sim:
        sim_or_404(sim_id)
        db.update_sim(
            sim_id, (body.label or "").strip() or None, (body.number or "").strip() or None
        )
        hub.publish_sim(sim_id)
        row = db.sim(sim_id)
        return schemas.Sim(**hub.sim_view(row))

    @app.get("/api/sims/{sim_id}/history")
    async def history(sim_id: str) -> schemas.History:
        sim_or_404(sim_id)
        return schemas.History(
            readings=[schemas.Reading(**hub.reading_view(r)) for r in db.readings(sim_id)],  # type: ignore[arg-type]
            sms=[schemas.SmsEntry(**dict(r)) for r in db.sms(sim_id)],
        )

    @app.post("/api/check/{sim_id}", status_code=202)
    async def check(sim_id: str) -> dict[str, str]:
        match hub.start_check(sim_id):
            case "unknown":
                raise HTTPException(404, "unknown SIM")
            case "offline":
                raise HTTPException(409, "phone offline")
            case "unsupported":
                raise HTTPException(422, "no check defined for this carrier")
            case result:
                return {"result": result}

    @app.post("/api/check-all", status_code=202)
    async def check_all() -> dict[str, list[str]]:
        started = [s["sim_id"] for s in hub.sims() if hub.start_check(s["sim_id"]) == "started"]
        return {"started": started}

    @app.get("/api/settings")
    async def get_settings() -> schemas.Settings:
        return schemas.Settings(
            low_balance=core.settings.low_balance, expiry_warn_days=core.settings.expiry_warn_days
        )

    def pairing() -> schemas.Pairing:
        if core.tls is None:
            raise HTTPException(503, "TLS disabled")
        s = core.settings
        p = {
            "host": s.public_host,
            "port": s.phone_port,
            "token": s.token(),
            "ca_sha256": core.tls.ca_sha256(),
        }
        return schemas.Pairing(**p, uri="simmonitor://pair?" + urlencode(p))

    @app.get("/api/pairing")
    async def get_pairing() -> schemas.Pairing:
        return pairing()

    @app.get("/api/pairing.svg", response_class=Response)
    async def pairing_qr() -> Response:
        buf = io.BytesIO()
        segno.make(pairing().uri, error="m").save(
            buf, kind="svg", scale=6, border=2, dark="#000", light="#fff"
        )
        return Response(
            buf.getvalue(), media_type="image/svg+xml", headers={"Cache-Control": "no-store"}
        )

    @app.get("/api/events", response_class=StreamingResponse)
    async def events() -> StreamingResponse:
        return StreamingResponse(
            core.events.stream(),
            media_type="text/event-stream",
            headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
        )

    dist = core.settings.web_dist
    if (dist / "index.html").exists():
        app.mount("/assets", StaticFiles(directory=dist / "assets"), name="assets")

        @app.get("/{path:path}", include_in_schema=False)
        async def spa(path: str) -> FileResponse:
            if path.startswith("api/"):
                raise HTTPException(404)
            f = dist / path
            return FileResponse(f if path and f.is_file() else dist / "index.html")

    return app


def create_phone_app(core: Core) -> FastAPI:
    app = FastAPI(openapi_url=None, docs_url=None, redoc_url=None)

    @app.websocket("/ws")
    async def ws(websocket: WebSocket) -> None:
        await core.hub.handle(websocket)

    return app
