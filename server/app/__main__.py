"""Run both listeners in one process: `uv run python -m app`."""

import asyncio
import logging

import uvicorn

from .config import settings
from .main import Core, create_dashboard_app, create_phone_app


async def serve() -> None:
    core = Core.create(settings)
    assert core.tls is not None
    dashboard = uvicorn.Server(
        uvicorn.Config(
            create_dashboard_app(core),
            host="127.0.0.1",
            port=settings.dashboard_port,
            log_level="info",
        )
    )
    phones = uvicorn.Server(
        uvicorn.Config(
            create_phone_app(core),
            host="0.0.0.0",
            port=settings.phone_port,
            ssl_certfile=str(core.tls.chain_path),
            ssl_keyfile=str(core.tls.key_path),
            ws_ping_interval=10,  # a phone that drops off Wi-Fi shows offline within ~20 s
            ws_ping_timeout=10,
            log_level="info",
        )
    )
    # Ctrl+C: let the first server install signal handlers, and stop the other with it.
    phones.install_signal_handlers = lambda: None  # type: ignore[method-assign]
    dash_task = asyncio.create_task(dashboard.serve())
    phone_task = asyncio.create_task(phones.serve())
    await asyncio.wait({dash_task, phone_task}, return_when=asyncio.FIRST_COMPLETED)
    dashboard.should_exit = phones.should_exit = True
    await asyncio.gather(dash_task, phone_task, return_exceptions=True)


def main() -> None:
    logging.basicConfig(
        level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s"
    )
    print(
        f"Dashboard: http://localhost:{settings.dashboard_port}   Phones: wss://{settings.public_host}:{settings.phone_port}/ws"
    )
    asyncio.run(serve())


if __name__ == "__main__":
    main()
