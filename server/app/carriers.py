"""Carrier knowledge: detection, the check to run, SMS senders to trust, and reply parsers.

Phones only execute instructions and return raw text, so every fix to codes or parsing
happens here. Parsers are written from real reply formats in docs/replies/ (see tests/test_parsers.py).
"""

import re
from dataclasses import dataclass, field
from datetime import date
from typing import Any

DIALOG, MOBITEL, HUTCH, UNKNOWN = "dialog", "mobitel", "hutch", "unknown"
LABELS = {DIALOG: "Dialog", MOBITEL: "SLT-Mobitel", HUTCH: "Hutch", UNKNOWN: "Unknown"}


def detect(mcc: str | None, mnc: str | None, name: str = "") -> str:
    """Sri Lankan networks by MCC 413 / MNC, with name matching as a fallback."""
    if mcc == "413" and mnc:
        match mnc.lstrip("0"):
            case "1":
                return MOBITEL
            case "2" | "5":  # 05 = Airtel, merged into Dialog
                return DIALOG
            case "3" | "8":  # 03 = Etisalat, merged into Hutch
                return HUTCH
    n = name.lower()
    if "dialog" in n:
        return DIALOG
    if "mobitel" in n or "slt" in n:
        return MOBITEL
    if "hutch" in n or "etisalat" in n:
        return HUTCH
    return UNKNOWN


# Senders whose SMS text phones may forward. Anything else is reported sender-only.
SENDERS: dict[str, list[str]] = {
    DIALOG: ["Dialog", "678", "5555", "456"],
    MOBITEL: ["Mobitel", "SLT-Mobitel", "SLT", "160", "7678", "100"],
    HUTCH: ["HUTCH", "344", "444"],
}


def normalize_sender(s: str) -> str:
    return re.sub(r"[^0-9a-z]", "", s.lower())


def all_senders() -> list[str]:
    return sorted({normalize_sender(s) for v in SENDERS.values() for s in v})


def is_carrier_sender(carrier: str, sender: str) -> bool:
    return normalize_sender(sender) in {normalize_sender(s) for s in SENDERS.get(carrier, [])}


# Instructions -------------------------------------------------------------------------------
# method=ussd: run `code`, return the reply text.
# method=sms:  send `body` to `to`.
# Either way, the phone then collects SMS from `expect_sms_from` for up to `sms_wait_s` seconds
# (stopping early after `sms_max` messages) and returns them with the result.
CHECKS: dict[str, dict[str, Any]] = {
    # Verified on a real phone: USSD reply has the main balance and validity; a 5555 SMS with
    # free data followed within about 3 s once a data plan was active.
    DIALOG: {
        "method": "ussd",
        "code": "#456#",
        "expect_sms_from": ["5555"],
        "sms_wait_s": 20,
        "sms_max": 1,
    },
    # Unverified: no Mobitel reply captured yet.
    MOBITEL: {
        "method": "ussd",
        "code": "*100#",
        "expect_sms_from": ["Mobitel", "160"],
        "sms_wait_s": 20,
        "sms_max": 1,
    },
    # Verified: USSD gives main balance + expiry, then a HUTCH SMS lists every balance (about 3 s later).
    HUTCH: {
        "method": "ussd",
        "code": "*344#",
        "expect_sms_from": ["HUTCH"],
        "sms_wait_s": 30,
        "sms_max": 1,
    },
}


def check_for(carrier: str) -> dict[str, Any] | None:
    c = CHECKS.get(carrier)
    return dict(c) if c else None


# Parsing ------------------------------------------------------------------------------------
@dataclass
class Parsed:
    balance: float | None = None
    expiry: str | None = None
    data_mb: float | None = None
    data_expiry: str | None = None
    buckets: list[dict[str, Any]] = field(default_factory=list)

    @property
    def ok(self) -> bool:
        return self.balance is not None or self.data_mb is not None

    def as_dict(self) -> dict[str, Any]:
        return {
            "balance": self.balance,
            "expiry": self.expiry,
            "data_mb": self.data_mb,
            "data_expiry": self.data_expiry,
            "buckets": self.buckets,
        }


DATE = r"(\d{1,2})/(\d{1,2})/(\d{4})"


def _date(m: re.Match | None, start: int = 1) -> str | None:
    if not m:
        return None
    d, mo, y = (int(m.group(start + i)) for i in range(3))
    try:
        return date(y, mo, d).isoformat()
    except ValueError:
        return None


def _mb(amount: str, unit: str) -> float:
    v = float(amount)
    return v * 1024 if unit.upper() == "GB" else v


def _add_data(p: Parsed, mb: float, expiry: str | None) -> None:
    p.data_mb = round((p.data_mb or 0) + mb, 2)
    if expiry and (p.data_expiry is None or expiry < p.data_expiry):
        p.data_expiry = expiry


def parse_hutch(ussd: str | None, sms: list[str]) -> Parsed:
    p = Parsed()
    if ussd and (m := re.search(r"main balance is Rs\.?\s*([\d,.]+)\s*Ex:?\s*" + DATE, ussd, re.I)):
        p.balance = float(m.group(1).replace(",", ""))
        p.expiry = _date(m, 2)
    for body in sms:
        # "Bal 1 Rs 1234.56 Ex 15/01/2027, Anynet - SMS 5000 Ex 10/01/2027, 2G Data - 0.50 MB Ex ..."
        for item in (i.strip() for i in body.split(",")):
            if not item:
                continue
            exp = _date(re.search(r"Ex:?\s*" + DATE, item, re.I))
            name = re.split(r"\s+-\s+|\s+Ex\b", item, maxsplit=1)[0].strip()
            bucket: dict[str, Any] = {"name": name, "text": item, "expiry": exp}
            if m := re.match(r"Bal\s*\d*\s*Rs\.?\s*([\d,.]+)", item, re.I):
                p.balance = (
                    p.balance if p.balance is not None else float(m.group(1).replace(",", ""))
                )
                p.expiry = p.expiry or exp
            elif m := re.search(r"-\s*([\d.]+)\s*(MB|GB)\b", item, re.I):
                if "data" in name.lower():
                    _add_data(p, _mb(m.group(1), m.group(2)), exp)
                    bucket["mb"] = _mb(m.group(1), m.group(2))
            elif m := re.search(r"valid for (\d+) Day", item, re.I):
                bucket["days_left"] = int(m.group(1))
            if not item.lower().startswith("status"):
                p.buckets.append(bucket)
    return p


def parse_dialog(ussd: str | None, sms: list[str]) -> Parsed:
    p = Parsed()
    # "Your available balance is Rs.12.34.Valid until 20/02/2027.Check your ..."
    if ussd and (m := re.search(r"balance is Rs\.?\s*([\d,]+(?:\.\d+)?)", ussd, re.I)):
        p.balance = float(m.group(1).replace(",", ""))
        p.expiry = _date(re.search(r"Valid until\s*" + DATE, ussd, re.I))
    for body in sms:
        # "Dear customer, you have     1024.00 MB free Data,    Thank you."
        for m in re.finditer(r"([\d.]+)\s*(MB|GB)\s+([A-Za-z ]*?Data)", body, re.I):
            mb = _mb(m.group(1), m.group(2))
            _add_data(p, mb, None)
            p.buckets.append(
                {"name": m.group(3).strip(), "text": m.group(0), "mb": mb, "expiry": None}
            )
    return p


def parse_generic(ussd: str | None, sms: list[str]) -> Parsed:
    """Best effort for carriers without a tuned parser: first Rs amount, MB/GB amounts, first date."""
    p = Parsed()
    text = " ".join([ussd or "", *sms])
    if m := re.search(r"Rs\.?\s*([\d,]+(?:\.\d+)?)", text, re.I):
        p.balance = float(m.group(1).replace(",", ""))
    p.expiry = _date(re.search(DATE, text))
    for m in re.finditer(r"([\d.]+)\s*(MB|GB)\b", text, re.I):
        _add_data(p, _mb(m.group(1), m.group(2)), None)
    return p


PARSERS = {HUTCH: parse_hutch, DIALOG: parse_dialog}


def parse(carrier: str, ussd: str | None, sms: list[str]) -> Parsed:
    return PARSERS.get(carrier, parse_generic)(ussd, sms)
