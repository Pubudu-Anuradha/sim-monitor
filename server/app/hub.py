"""Phone connections and the check flow.

Protocol (JSON text frames, see docs/api.md):
  phone → hello {phone_id, name, app_version, sims:[{sub_id, slot, carrier_name, mcc, mnc, number?}]}
  desktop → config {allowed_senders:[...]}
  desktop → run {request_id, sub_id, instruction}
  phone → result {request_id, ok, ussd_reply?, sms:[{sender, body, ts}], error?}
  phone → sms {sub_id, sender, body, ts}      (unsolicited carrier SMS)
  phone → sims {sims:[...]}                   (SIM inserted / removed)
"""

import asyncio
import json
import logging
import uuid
from typing import Any

from fastapi import WebSocket, WebSocketDisconnect

from . import carriers
from .db import Database
from .events import Events

log = logging.getLogger("sim_monitor.hub")

HELLO_TIMEOUT_S = 10
RUN_SLACK_S = 20  # on top of the instruction's own waits (USSD + SMS)
USSD_TIMEOUT_S = 45


class PhoneGone(Exception):
    pass


class Hub:
    def __init__(self, db: Database, events: Events, token: str):
        self.db = db
        self.events = events
        self.token = token
        self.phones: dict[str, WebSocket] = {}
        self.pending: dict[str, asyncio.Future[dict[str, Any]]] = {}
        self.phone_locks: dict[str, asyncio.Lock] = {}
        self.checking: set[str] = set()
        self.tasks: set[asyncio.Task[None]] = set()

    # views ----------------------------------------------------------------------------------
    @staticmethod
    def reading_view(row: Any) -> dict[str, Any] | None:
        if row is None:
            return None
        return {
            "id": row["id"],
            "ts": row["ts"],
            "balance": row["balance"],
            "expiry": row["expiry"],
            "data_mb": row["data_mb"],
            "data_expiry": row["data_expiry"],
            "buckets": json.loads(row["buckets"] or "[]"),
            "raw": row["raw"],
            "parsed_ok": bool(row["parsed_ok"]),
            "error": row["error"],
        }

    def sim_view(self, row: Any) -> dict[str, Any]:
        last = self.db.last_reading(row["sim_id"])
        last_ok = (
            last
            if last is not None and last["parsed_ok"]
            else self.db.last_reading(row["sim_id"], ok_only=True)
        )
        if row["sim_id"] in self.checking:
            status = "checking"
        elif last is None:
            status = "idle"
        elif last["parsed_ok"]:
            status = "ok"
        elif last["error"]:
            status = "error"
        else:
            status = "parse_failed"
        return {
            "sim_id": row["sim_id"],
            "phone_id": row["phone_id"],
            "phone_name": row["phone_name"],
            "phone_online": bool(row["phone_online"]),
            "slot": row["slot"],
            "carrier": row["carrier"],
            "carrier_label": carriers.LABELS.get(row["carrier"], row["carrier"]),
            "carrier_name": row["carrier_name"],
            "number": row["number"],
            "label": row["label"],
            "status": status,
            "checkable": carriers.check_for(row["carrier"]) is not None,
            "last": self.reading_view(last),
            "last_ok": self.reading_view(last_ok),
        }

    def sims(self) -> list[dict[str, Any]]:
        return [self.sim_view(r) for r in self.db.sims()]

    def publish_sim(self, sim_id: str) -> None:
        if row := self.db.sim(sim_id):
            self.events.publish("sim", self.sim_view(row))

    def publish_phone(self, phone_id: str) -> None:
        for row in self.db.sims():
            if row["phone_id"] == phone_id:
                self.events.publish("sim", self.sim_view(row))

    # phone connection -----------------------------------------------------------------------
    async def handle(self, ws: WebSocket) -> None:
        if ws.headers.get("authorization") != f"Bearer {self.token}":
            await ws.close(code=4401, reason="bad token")
            return
        await ws.accept()
        try:
            hello = json.loads(await asyncio.wait_for(ws.receive_text(), HELLO_TIMEOUT_S))
        except (TimeoutError, WebSocketDisconnect, json.JSONDecodeError):
            await ws.close(code=4400, reason="expected hello")
            return
        if hello.get("type") != "hello" or not hello.get("phone_id"):
            await ws.close(code=4400, reason="expected hello")
            return

        phone_id = str(hello["phone_id"])
        if old := self.phones.get(phone_id):  # reconnect: the new socket wins
            await old.close(code=4409, reason="replaced")
        self.phones[phone_id] = ws
        self.db.upsert_phone(
            phone_id, str(hello.get("name") or phone_id[:8]), hello.get("app_version")
        )
        self._sync_sims(phone_id, hello.get("sims") or [])
        log.info("phone %s (%s) online", phone_id, hello.get("name"))
        await ws.send_text(
            json.dumps({"type": "config", "allowed_senders": carriers.all_senders()})
        )
        self.publish_phone(phone_id)

        try:
            while True:
                msg = json.loads(await ws.receive_text())
                await self._on_message(phone_id, msg)
        except WebSocketDisconnect:
            pass
        finally:
            if self.phones.get(phone_id) is ws:
                del self.phones[phone_id]
                self.db.set_phone_online(phone_id, False)
                for rid, fut in list(self.pending.items()):
                    if rid.startswith(phone_id + "/") and not fut.done():
                        fut.set_exception(PhoneGone())
                log.info("phone %s offline", phone_id)
                self.publish_phone(phone_id)

    def _sync_sims(self, phone_id: str, sims: list[dict[str, Any]]) -> None:
        self.db.sync_sims(
            phone_id,
            [
                {
                    "sim_id": f"{phone_id}:{s['sub_id']}",
                    "phone_id": phone_id,
                    "sub_id": int(s["sub_id"]),
                    "slot": s.get("slot"),
                    "carrier": carriers.detect(
                        s.get("mcc"), s.get("mnc"), s.get("carrier_name") or ""
                    ),
                    "carrier_name": s.get("carrier_name"),
                    "number": s.get("number") or None,
                }
                for s in sims
            ],
        )

    async def _on_message(self, phone_id: str, msg: dict[str, Any]) -> None:
        match msg.get("type"):
            case "result":
                fut = self.pending.get(f"{phone_id}/{msg.get('request_id')}")
                if fut and not fut.done():
                    fut.set_result(msg)
            case "sms":
                sim_id = f"{phone_id}:{msg.get('sub_id')}"
                sim = self.db.sim(sim_id)
                if sim and carriers.is_carrier_sender(sim["carrier"], str(msg.get("sender", ""))):
                    self.db.add_sms(
                        sim_id, str(msg["sender"]), str(msg.get("body", "")), msg.get("ts")
                    )
                    self.events.publish("sms", {"sim_id": sim_id, "sender": msg["sender"]})
            case "sims":
                self._sync_sims(phone_id, msg.get("sims") or [])
                self.publish_phone(phone_id)
            case other:
                log.warning("phone %s sent unknown message type %r", phone_id, other)

    # checks ---------------------------------------------------------------------------------
    def start_check(self, sim_id: str) -> str:
        """Kick off a check in the background. Returns "started", "busy", "offline", "unsupported" or "unknown"."""
        row = self.db.sim(sim_id)
        if row is None:
            return "unknown"
        if row["phone_id"] not in self.phones:
            return "offline"
        if carriers.check_for(row["carrier"]) is None:
            return "unsupported"
        if sim_id in self.checking:
            return "busy"
        self.checking.add(sim_id)
        self.publish_sim(sim_id)
        task = asyncio.create_task(self._run_check(sim_id))
        self.tasks.add(task)
        task.add_done_callback(self.tasks.discard)
        return "started"

    async def _run_check(self, sim_id: str) -> None:
        row = self.db.sim(sim_id)
        assert row is not None
        phone_id, carrier = row["phone_id"], row["carrier"]
        instruction = carriers.check_for(carrier)
        assert instruction is not None
        try:
            # One instruction at a time per phone: USSD sessions can't overlap.
            async with self.phone_locks.setdefault(phone_id, asyncio.Lock()):
                result = await self._run(phone_id, row["sub_id"], instruction)
            self._store_result(sim_id, carrier, result)
        except PhoneGone:
            self.db.add_reading(
                sim_id, {"raw": "", "parsed_ok": False, "error": "phone disconnected"}
            )
        except TimeoutError:
            self.db.add_reading(
                sim_id, {"raw": "", "parsed_ok": False, "error": "phone did not answer in time"}
            )
        except Exception as e:  # keep the dashboard state consistent whatever happens
            log.exception("check %s failed", sim_id)
            self.db.add_reading(
                sim_id, {"raw": "", "parsed_ok": False, "error": f"server error: {e}"}
            )
        finally:
            self.checking.discard(sim_id)
            self.publish_sim(sim_id)

    async def _run(self, phone_id: str, sub_id: int, instruction: dict[str, Any]) -> dict[str, Any]:
        ws = self.phones.get(phone_id)
        if ws is None:
            raise PhoneGone()
        request_id = uuid.uuid4().hex[:12]
        key = f"{phone_id}/{request_id}"
        fut: asyncio.Future[dict[str, Any]] = asyncio.get_running_loop().create_future()
        self.pending[key] = fut
        try:
            await ws.send_text(
                json.dumps(
                    {
                        "type": "run",
                        "request_id": request_id,
                        "sub_id": sub_id,
                        "instruction": instruction,
                    }
                )
            )
            timeout = USSD_TIMEOUT_S + instruction.get("sms_wait_s", 0) + RUN_SLACK_S
            return await asyncio.wait_for(fut, timeout)
        finally:
            self.pending.pop(key, None)

    def _store_result(self, sim_id: str, carrier: str, result: dict[str, Any]) -> None:
        sms = [s for s in result.get("sms") or [] if isinstance(s, dict)]
        for s in sms:
            self.db.add_sms(sim_id, str(s.get("sender", "")), str(s.get("body", "")), s.get("ts"))
        ussd = result.get("ussd_reply")
        bodies = [str(s.get("body", "")) for s in sms]
        raw_parts = ([f"[USSD] {ussd}"] if ussd else []) + [
            f"[SMS {s.get('sender')}] {s.get('body')}" for s in sms
        ]
        raw = "\n".join(raw_parts)
        if not result.get("ok") and not raw:
            self.db.add_reading(
                sim_id,
                {"raw": "", "parsed_ok": False, "error": result.get("error") or "check failed"},
            )
            return
        parsed = carriers.parse(carrier, ussd, bodies)
        error = None if parsed.ok else (result.get("error") or None)
        if not parsed.ok:
            log.warning("parser broke for %s (%s): %r", sim_id, carrier, raw)
        self.db.add_reading(
            sim_id, {**parsed.as_dict(), "raw": raw, "parsed_ok": parsed.ok, "error": error}
        )
