"""Parsers against carrier reply formats captured on real phones (docs/replies/samples.jsonl)."""

import json
from pathlib import Path

import pytest

from app import carriers

REPLIES = Path(__file__).resolve().parents[2] / "docs" / "replies"


def captured() -> list[dict]:
    rows = []
    for f in sorted(REPLIES.glob("*.jsonl")):
        rows += [json.loads(line) for line in f.read_text().splitlines() if line.strip()]
    return rows


def test_fixtures_exist() -> None:
    assert any(r["kind"] == "ussd_reply" for r in captured())


def test_detect() -> None:
    assert carriers.detect("413", "08") == carriers.HUTCH
    assert carriers.detect("413", "02") == carriers.DIALOG
    assert carriers.detect("413", "01") == carriers.MOBITEL
    assert carriers.detect("413", "1") == carriers.MOBITEL
    assert carriers.detect(None, None, "Dialog 4G") == carriers.DIALOG
    assert carriers.detect("310", "260", "T-Mobile") == carriers.UNKNOWN


def test_hutch_ussd_and_sms() -> None:
    rows = captured()
    ussd = next(r["reply"] for r in rows if r.get("code") == "*344#" and r["kind"] == "ussd_reply")
    sms = next(r["body"] for r in rows if r["kind"] == "sms_received" and r["sender"] == "HUTCH")
    p = carriers.parse(carriers.HUTCH, ussd, [sms])
    assert p.ok
    assert p.balance == 1234.56
    assert p.expiry == "2027-01-15"
    assert p.data_mb == 0.5
    assert p.data_expiry == "2027-01-05"
    names = [b["name"] for b in p.buckets]
    assert names[:3] == ["Bal 1 Rs 1234.56", "Anynet", "2G Data"]
    assert any(b.get("days_left") == 16 for b in p.buckets)


def test_hutch_ussd_only() -> None:
    p = carriers.parse(carriers.HUTCH, "Your main balance is Rs 1,250.00 Ex: 01/02/2027.", [])
    assert (p.balance, p.expiry, p.data_mb) == (1250.0, "2027-02-01", None)


@pytest.mark.parametrize(
    ("reply", "balance", "expiry"),
    [
        (r["reply"], None, None)
        for r in captured()
        if r.get("code") == "#456#" and r["kind"] == "ussd_reply"
    ],
)
def test_dialog_ussd_captured(reply: str, balance: None, expiry: None) -> None:
    p = carriers.parse(carriers.DIALOG, reply, [])
    assert p.ok and p.balance == 12.34
    assert p.expiry in {"2027-02-20", "2027-03-20"}


def test_dialog_free_data_sms() -> None:
    sms = next(
        r["body"]
        for r in captured()
        if r["kind"] == "sms_received" and "free Data" in r.get("body", "")
    )
    p = carriers.parse(carriers.DIALOG, None, [sms])
    assert p.data_mb == 1024.0 and p.ok


def test_dialog_activation_sms_is_not_a_balance() -> None:
    # "...activated the Rs.199 Anytime Data plan with 2.5GB Anytime Data. Valid for 30 days."
    # Must not be read as main balance; the GB amount is the plan size, counted as data.
    sms = next(
        r["body"]
        for r in captured()
        if r["kind"] == "sms_received" and "activated" in r.get("body", "")
    )
    p = carriers.parse(carriers.DIALOG, None, [sms])
    assert p.balance is None


def test_unparseable_reply() -> None:
    p = carriers.parse(carriers.HUTCH, "Sorry, the service is temporarily unavailable.", [])
    assert not p.ok


def test_senders() -> None:
    assert carriers.is_carrier_sender(carriers.HUTCH, "HUTCH")
    assert carriers.is_carrier_sender(carriers.MOBITEL, "SLT-Mobitel")
    assert not carriers.is_carrier_sender(carriers.DIALOG, "MyBank")
    assert "mybank" not in carriers.all_senders()
