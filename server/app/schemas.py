"""API models. These drive the OpenAPI schema the dashboard's TypeScript types are generated from."""

from typing import Literal

from pydantic import BaseModel

SimStatus = Literal["idle", "checking", "ok", "parse_failed", "error"]


class Bucket(BaseModel):
    name: str
    text: str
    expiry: str | None = None
    mb: float | None = None
    days_left: int | None = None


class Reading(BaseModel):
    id: int
    ts: str
    balance: float | None
    expiry: str | None
    data_mb: float | None
    data_expiry: str | None
    buckets: list[Bucket]
    raw: str
    parsed_ok: bool
    error: str | None


class Sim(BaseModel):
    sim_id: str
    phone_id: str
    phone_name: str
    phone_online: bool
    slot: int | None
    carrier: str
    carrier_label: str
    carrier_name: str | None
    number: str | None
    label: str | None
    status: SimStatus
    checkable: bool
    last: Reading | None
    """Most recent attempt (may be a failure)."""
    last_ok: Reading | None
    """Most recent successful reading, shown when the latest attempt failed or the phone is offline."""


class SmsEntry(BaseModel):
    id: int
    ts: str
    sender: str
    body: str


class History(BaseModel):
    readings: list[Reading]
    sms: list[SmsEntry]


class SimUpdate(BaseModel):
    label: str | None = None
    number: str | None = None


class Settings(BaseModel):
    low_balance: float
    expiry_warn_days: int


class Pairing(BaseModel):
    host: str
    port: int
    token: str
    ca_sha256: str
    uri: str
