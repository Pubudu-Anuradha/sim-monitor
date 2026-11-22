"""End-to-end through the apps: a fake phone over the WebSocket, checks via the REST API."""

import json
import time
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app.config import Settings
from app.main import Core, create_dashboard_app, create_phone_app

TOKEN = "test-token"
HELLO = {
    "type": "hello",
    "phone_id": "p1",
    "name": "Test phone",
    "app_version": "t",
    "sims": [
        {"sub_id": 1, "slot": 0, "carrier_name": "Hutch", "mcc": "413", "mnc": "08"},
        {
            "sub_id": 2,
            "slot": 1,
            "carrier_name": "Dialog",
            "mcc": "413",
            "mnc": "02",
            "number": "000-000-0001",
        },
    ],
}
HUTCH_USSD = (
    "Your main balance is Rs 1234.56 Ex: 15/01/2027. You'll receive all balances via SMS shortly."
)
HUTCH_SMS = "Bal 1 Rs 1234.56 Ex 15/01/2027, 2G Data - 0.50 MB Ex 05/01/2027, Status : Active"


@pytest.fixture
def core(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> Core:
    monkeypatch.setenv("SIM_MONITOR_TOKEN", TOKEN)
    s = Settings(data_dir=tmp_path, web_dist=tmp_path / "no-dist")
    return Core.create(s, with_tls=False)


@pytest.fixture
def client(core: Core) -> TestClient:
    # In production both apps run on one event loop; mount them together so tests do the same.
    app = create_dashboard_app(core)
    app.mount("/phone", create_phone_app(core))
    with TestClient(app) as c:
        yield c


@pytest.fixture
def dash(client: TestClient) -> TestClient:
    return client


@pytest.fixture
def phone(client: TestClient) -> TestClient:
    return client


def wait_for(cond, timeout: float = 5.0):  # type: ignore[no-untyped-def]
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        if v := cond():
            return v
        time.sleep(0.02)
    raise AssertionError("condition not met")


def connect(phone: TestClient, token: str = TOKEN):  # type: ignore[no-untyped-def]
    return phone.websocket_connect("/phone/ws", headers={"Authorization": f"Bearer {token}"})


def test_bad_token_rejected(phone: TestClient) -> None:
    from starlette.websockets import WebSocketDisconnect

    with pytest.raises(WebSocketDisconnect) as e, connect(phone, "wrong") as ws:
        ws.receive_text()
    assert e.value.code == 4401


def test_hello_registers_sims_and_sends_config(phone: TestClient, dash: TestClient) -> None:
    with connect(phone) as ws:
        ws.send_text(json.dumps(HELLO))
        config = json.loads(ws.receive_text())
        assert config["type"] == "config" and "hutch" in config["allowed_senders"]
        sims = dash.get("/api/sims").json()
        assert [(s["sim_id"], s["carrier"], s["phone_online"], s["status"]) for s in sims] == [
            ("p1:1", "hutch", True, "idle"),
            ("p1:2", "dialog", True, "idle"),
        ]
        assert sims[1]["number"] == "000-000-0001"
    sims = wait_for(lambda: [s for s in dash.get("/api/sims").json() if not s["phone_online"]])
    assert len(sims) == 2  # offline once the socket closes, still listed with last-known values


def test_check_flow_stores_parsed_reading(phone: TestClient, dash: TestClient) -> None:
    with connect(phone) as ws:
        ws.send_text(json.dumps(HELLO))
        ws.receive_text()  # config
        assert dash.post("/api/check/p1:1").status_code == 202
        assert dash.post("/api/check/p1:1").json() == {"result": "busy"}
        run = json.loads(ws.receive_text())
        assert run["type"] == "run" and run["sub_id"] == 1
        assert run["instruction"]["code"] == "*344#"
        assert dash.get("/api/sims").json()[0]["status"] == "checking"
        ws.send_text(
            json.dumps(
                {
                    "type": "result",
                    "request_id": run["request_id"],
                    "ok": True,
                    "ussd_reply": HUTCH_USSD,
                    "sms": [
                        {"sender": "HUTCH", "body": HUTCH_SMS, "ts": "2026-11-22T08:20:06+00:00"}
                    ],
                }
            )
        )
        sim = wait_for(
            lambda: next(s for s in dash.get("/api/sims").json() if s["status"] != "checking")
        )
        assert sim["status"] == "ok"
        assert sim["last"]["balance"] == 1234.56
        assert sim["last"]["data_mb"] == 0.5
        assert sim["last"]["data_expiry"] == "2027-01-05"
        assert "[USSD] Your main balance" in sim["last"]["raw"]
        h = dash.get("/api/sims/p1:1/history").json()
        assert len(h["readings"]) == 1 and h["sms"][0]["sender"] == "HUTCH"


def test_parse_failure_keeps_raw_and_last_ok(phone: TestClient, dash: TestClient) -> None:
    with connect(phone) as ws:
        ws.send_text(json.dumps(HELLO))
        ws.receive_text()
        for reply in (HUTCH_USSD, "Sorry, service unavailable."):
            dash.post("/api/check/p1:1")
            run = json.loads(ws.receive_text())
            ws.send_text(
                json.dumps(
                    {
                        "type": "result",
                        "request_id": run["request_id"],
                        "ok": True,
                        "ussd_reply": reply,
                        "sms": [],
                    }
                )
            )
            wait_for(lambda: dash.get("/api/sims").json()[0]["status"] != "checking")
        sim = dash.get("/api/sims").json()[0]
        assert sim["status"] == "parse_failed"
        assert sim["last"]["raw"] == "[USSD] Sorry, service unavailable."
        assert sim["last_ok"]["balance"] == 1234.56


def test_phone_error_and_disconnect(phone: TestClient, dash: TestClient) -> None:
    with connect(phone) as ws:
        ws.send_text(json.dumps(HELLO))
        ws.receive_text()
        dash.post("/api/check/p1:2")
        run = json.loads(ws.receive_text())
        ws.send_text(
            json.dumps(
                {
                    "type": "result",
                    "request_id": run["request_id"],
                    "ok": False,
                    "error": "USSD_RETURN_FAILURE (-1)",
                    "sms": [],
                }
            )
        )
        sim = wait_for(
            lambda: next(
                s
                for s in dash.get("/api/sims").json()
                if s["sim_id"] == "p1:2" and s["status"] != "checking"
            )
        )
        assert sim["status"] == "error" and sim["last"]["error"] == "USSD_RETURN_FAILURE (-1)"
        dash.post("/api/check/p1:2")
        ws.receive_text()  # run, never answered: the phone goes away
    sim = wait_for(
        lambda: next(
            s
            for s in dash.get("/api/sims").json()
            if s["sim_id"] == "p1:2" and s["status"] != "checking"
        )
    )
    assert sim["last"]["error"] == "phone disconnected"
    assert dash.post("/api/check/p1:2").status_code == 409


def test_unsolicited_sms_filtered_by_sender(phone: TestClient, dash: TestClient) -> None:
    with connect(phone) as ws:
        ws.send_text(json.dumps(HELLO))
        ws.receive_text()
        ws.send_text(
            json.dumps(
                {"type": "sms", "sub_id": 1, "sender": "HUTCH", "body": "Your pack expires soon"}
            )
        )
        ws.send_text(
            json.dumps({"type": "sms", "sub_id": 1, "sender": "MyBank", "body": "OTP 123"})
        )
        wait_for(lambda: dash.get("/api/sims/p1:1/history").json()["sms"])
        sms = dash.get("/api/sims/p1:1/history").json()["sms"]
        assert [s["sender"] for s in sms] == ["HUTCH"]


def test_label_update(phone: TestClient, dash: TestClient) -> None:
    with connect(phone) as ws:
        ws.send_text(json.dumps(HELLO))
        ws.receive_text()
        r = dash.patch("/api/sims/p1:1", json={"label": " Work ", "number": "000-000-0002"})
        assert (r.json()["label"], r.json()["number"]) == ("Work", "000-000-0002")
        ws.send_text(json.dumps(HELLO))  # a later hello must not wipe the user's number
    sim = dash.get("/api/sims").json()[0]
    assert sim["label"] == "Work" and sim["number"] == "000-000-0002"


def test_dashboard_rejects_non_loopback(core: Core) -> None:
    c = TestClient(create_dashboard_app(core), client=("192.0.2.50", 5000))
    assert c.get("/api/sims").status_code == 403
