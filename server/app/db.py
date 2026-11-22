"""SQLite storage. Small and synchronous: every call is a few rows, guarded by one lock."""

import json
import sqlite3
import threading
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

SCHEMA = """
CREATE TABLE IF NOT EXISTS phones (
    id          TEXT PRIMARY KEY,
    name        TEXT NOT NULL,
    app_version TEXT,
    last_seen   TEXT,
    online      INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS sims (
    sim_id       TEXT PRIMARY KEY,           -- "<phone_id>:<sub_id>"
    phone_id     TEXT NOT NULL REFERENCES phones(id),
    sub_id       INTEGER NOT NULL,
    slot         INTEGER,
    carrier      TEXT NOT NULL,              -- dialog | mobitel | hutch | unknown
    carrier_name TEXT,
    number       TEXT,
    label        TEXT,
    present      INTEGER NOT NULL DEFAULT 1  -- 0 when the phone no longer reports it
);
CREATE TABLE IF NOT EXISTS readings (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    sim_id      TEXT NOT NULL REFERENCES sims(sim_id),
    ts          TEXT NOT NULL,
    balance     REAL,
    expiry      TEXT,                        -- ISO date, main balance / SIM validity
    data_mb     REAL,
    data_expiry TEXT,                        -- ISO date, earliest data bucket expiry
    buckets     TEXT,                        -- JSON list of every balance line found
    raw         TEXT NOT NULL,               -- every reply text, verbatim
    parsed_ok   INTEGER NOT NULL,
    error       TEXT
);
CREATE INDEX IF NOT EXISTS readings_sim_ts ON readings(sim_id, ts);
CREATE TABLE IF NOT EXISTS sms_log (
    id     INTEGER PRIMARY KEY AUTOINCREMENT,
    sim_id TEXT NOT NULL,
    ts     TEXT NOT NULL,
    sender TEXT NOT NULL,
    body   TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS sms_sim_ts ON sms_log(sim_id, ts);
"""


def now() -> str:
    return datetime.now(UTC).isoformat(timespec="seconds")


class Database:
    def __init__(self, path: Path | str):
        if isinstance(path, Path):
            path.parent.mkdir(parents=True, exist_ok=True)
        self._conn = sqlite3.connect(str(path), check_same_thread=False)
        self._conn.row_factory = sqlite3.Row
        self._conn.execute("PRAGMA foreign_keys = ON")
        self._conn.execute("PRAGMA journal_mode = WAL")
        self._conn.executescript(SCHEMA)
        self._lock = threading.Lock()

    def _q(self, sql: str, params: tuple | dict = ()) -> list[sqlite3.Row]:
        with self._lock, self._conn:
            return self._conn.execute(sql, params).fetchall()

    # phones ---------------------------------------------------------------------------------
    def upsert_phone(self, phone_id: str, name: str, app_version: str | None) -> None:
        self._q(
            """INSERT INTO phones (id, name, app_version, last_seen, online) VALUES (?, ?, ?, ?, 1)
               ON CONFLICT(id) DO UPDATE SET name=excluded.name, app_version=excluded.app_version,
               last_seen=excluded.last_seen, online=1""",
            (phone_id, name, app_version, now()),
        )

    def set_phone_online(self, phone_id: str, online: bool) -> None:
        self._q(
            "UPDATE phones SET online=?, last_seen=? WHERE id=?", (int(online), now(), phone_id)
        )

    def mark_all_offline(self) -> None:
        self._q("UPDATE phones SET online=0")

    # sims -----------------------------------------------------------------------------------
    def sync_sims(self, phone_id: str, sims: list[dict[str, Any]]) -> None:
        """Insert/update the SIMs a phone reports; mark its other SIMs as not present."""
        with self._lock, self._conn:
            self._conn.execute("UPDATE sims SET present=0 WHERE phone_id=?", (phone_id,))
            for s in sims:
                self._conn.execute(
                    """INSERT INTO sims (sim_id, phone_id, sub_id, slot, carrier, carrier_name, number, present)
                       VALUES (:sim_id, :phone_id, :sub_id, :slot, :carrier, :carrier_name, :number, 1)
                       ON CONFLICT(sim_id) DO UPDATE SET slot=excluded.slot, carrier=excluded.carrier,
                       carrier_name=excluded.carrier_name,
                       number=COALESCE(sims.number, excluded.number), present=1""",
                    s,
                )

    def update_sim(self, sim_id: str, label: str | None, number: str | None) -> None:
        self._q("UPDATE sims SET label=?, number=? WHERE sim_id=?", (label, number, sim_id))

    def sim(self, sim_id: str) -> sqlite3.Row | None:
        rows = self._q(
            """SELECT s.*, p.name AS phone_name, p.online AS phone_online
               FROM sims s JOIN phones p ON p.id = s.phone_id WHERE s.sim_id=?""",
            (sim_id,),
        )
        return rows[0] if rows else None

    def sims(self) -> list[sqlite3.Row]:
        return self._q(
            """SELECT s.*, p.name AS phone_name, p.online AS phone_online
               FROM sims s JOIN phones p ON p.id = s.phone_id
               WHERE s.present = 1 ORDER BY p.name, s.slot"""
        )

    # readings -------------------------------------------------------------------------------
    def add_reading(self, sim_id: str, r: dict[str, Any]) -> sqlite3.Row:
        with self._lock, self._conn:
            cur = self._conn.execute(
                """INSERT INTO readings (sim_id, ts, balance, expiry, data_mb, data_expiry, buckets,
                   raw, parsed_ok, error) VALUES (?,?,?,?,?,?,?,?,?,?)""",
                (
                    sim_id,
                    now(),
                    r.get("balance"),
                    r.get("expiry"),
                    r.get("data_mb"),
                    r.get("data_expiry"),
                    json.dumps(r.get("buckets") or []),
                    r["raw"],
                    int(r["parsed_ok"]),
                    r.get("error"),
                ),
            )
            return self._conn.execute(
                "SELECT * FROM readings WHERE id=?", (cur.lastrowid,)
            ).fetchone()

    def last_reading(self, sim_id: str, ok_only: bool = False) -> sqlite3.Row | None:
        cond = "AND parsed_ok = 1" if ok_only else ""
        rows = self._q(
            f"SELECT * FROM readings WHERE sim_id=? {cond} ORDER BY id DESC LIMIT 1", (sim_id,)
        )
        return rows[0] if rows else None

    def readings(self, sim_id: str, limit: int = 50) -> list[sqlite3.Row]:
        return self._q(
            "SELECT * FROM readings WHERE sim_id=? ORDER BY id DESC LIMIT ?", (sim_id, limit)
        )

    # sms ------------------------------------------------------------------------------------
    def add_sms(self, sim_id: str, sender: str, body: str, ts: str | None = None) -> None:
        self._q(
            "INSERT INTO sms_log (sim_id, ts, sender, body) VALUES (?,?,?,?)",
            (sim_id, ts or now(), sender, body),
        )

    def sms(self, sim_id: str, limit: int = 50) -> list[sqlite3.Row]:
        return self._q(
            "SELECT * FROM sms_log WHERE sim_id=? ORDER BY ts DESC, id DESC LIMIT ?",
            (sim_id, limit),
        )
