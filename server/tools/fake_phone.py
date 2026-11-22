"""A fake phone for development: connects like the app and answers checks with captured replies.

    uv run python tools/fake_phone.py               # wss://127.0.0.1:8443, CA + token from server/data
    uv run python tools/fake_phone.py --flaky       # every 3rd check returns an unparseable reply

Replies come from real phone captures (docs/replies/), so the dashboard shows realistic data.
"""

import argparse
import asyncio
import json
import random
import ssl
from datetime import UTC, datetime
from pathlib import Path

import websockets

SERVER_DIR = Path(__file__).resolve().parent.parent

SIMS = [
    {"sub_id": 1, "slot": 0, "carrier_name": "Hutch", "mcc": "413", "mnc": "08", "number": None},
    {
        "sub_id": 2,
        "slot": 1,
        "carrier_name": "Dialog",
        "mcc": "413",
        "mnc": "02",
        "number": "000-000-0001",
    },
]

REPLIES = {
    "*344#": (
        "Your main balance is Rs {bal} Ex: 13/12/2026. You'll receive all balances via SMS shortly.",
        [
            (
                "HUTCH",
                "Bal 1 Rs {bal} Ex 13/12/2026, Anynet - SMS 22293 Ex 08/12/2026, 2G Data - {mb} MB "
                "Ex {soon}, Cheer Points - 8218.60 Ex 31/01/2027, Hotspot Nonstop Anytime Data valid for 16 Day(s), "
                "Status : Active",
            )
        ],
    ),
    "#456#": (
        "Your available balance is Rs.{bal}.Valid until 22/11/2027.Check your Voice,Data,Loan balances & "
        "other services, easily via MyDialog App",
        [("5555", "Dear customer, you have     {mb} MB free Data,    Thank you.")],
    ),
}


def now() -> str:
    return datetime.now(UTC).isoformat(timespec="seconds")


async def run(args: argparse.Namespace) -> None:
    data = SERVER_DIR / "data"
    token = args.token or (data / "token").read_text().strip()
    ctx = ssl.create_default_context(cafile=str(data / "tls" / "ca.crt"))
    count = 0
    async with websockets.connect(
        args.url, ssl=ctx, additional_headers={"Authorization": f"Bearer {token}"}
    ) as ws:
        await ws.send(
            json.dumps(
                {
                    "type": "hello",
                    "phone_id": args.phone_id,
                    "name": args.name,
                    "app_version": "fake-1",
                    "sims": SIMS,
                }
            )
        )
        print("connected; waiting for checks (Ctrl+C to stop)")
        async for raw in ws:
            msg = json.loads(raw)
            if msg["type"] != "run":
                print("server:", msg)
                continue
            count += 1
            ins = msg["instruction"]
            print(f"run {msg['request_id']} sub {msg['sub_id']}: {ins}")
            await asyncio.sleep(args.delay)
            fmt = {
                "bal": f"{random.uniform(0, 1500):.2f}",
                "mb": f"{random.uniform(0, 4096):.2f}",
                "soon": datetime.now().strftime("%d/%m/%Y"),
            }
            ussd, sms = REPLIES.get(ins.get("code", ""), ("Unknown request", []))
            if args.flaky and count % 3 == 0:
                ussd, sms = "Sorry, the service is temporarily unavailable.", []
            await ws.send(
                json.dumps(
                    {
                        "type": "result",
                        "request_id": msg["request_id"],
                        "ok": True,
                        "ussd_reply": ussd.format(**fmt),
                        "sms": [
                            {"sender": s, "body": b.format(**fmt), "ts": now()} for s, b in sms
                        ],
                    }
                )
            )


def main() -> None:
    p = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    p.add_argument("--url", default="wss://127.0.0.1:8443/ws")
    p.add_argument("--token")
    p.add_argument("--phone-id", default="fake-phone-1")
    p.add_argument("--name", default="Fake phone")
    p.add_argument("--delay", type=float, default=2.0, help="seconds before answering a check")
    p.add_argument("--flaky", action="store_true")
    try:
        asyncio.run(run(p.parse_args()))
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
