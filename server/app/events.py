"""Fan-out of server events to dashboard SSE subscribers."""

import asyncio
import json
from collections.abc import AsyncIterator
from typing import Any


class Events:
    def __init__(self) -> None:
        self._subs: set[asyncio.Queue[str]] = set()

    def publish(self, kind: str, data: Any) -> None:
        msg = f"event: {kind}\ndata: {json.dumps(data)}\n\n"
        for q in list(self._subs):
            if q.qsize() < 1000:  # a stuck client must not grow memory forever
                q.put_nowait(msg)

    async def stream(self, heartbeat_s: float = 15) -> AsyncIterator[str]:
        q: asyncio.Queue[str] = asyncio.Queue()
        self._subs.add(q)
        try:
            yield "event: hello\ndata: {}\n\n"
            while True:
                try:
                    yield await asyncio.wait_for(q.get(), heartbeat_s)
                except TimeoutError:
                    yield ": keepalive\n\n"
        finally:
            self._subs.discard(q)
