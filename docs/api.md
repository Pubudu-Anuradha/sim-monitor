# API

## Listeners
| Listener | Address | Who | Transport |
|---|---|---|---|
| Dashboard | `http://127.0.0.1:8000` | browser on this desktop | plain HTTP, loopback only (bind + middleware) |
| Phones | `wss://<desktop-ip>:8443/ws` | phone app | TLS with the private CA (`server/data/tls/`) |

## Pairing
The dashboard's "Pair phone" shows a QR code for
`simmonitor://pair?host=…&port=…&token=…&ca_sha256=…`.
`ca_sha256` is the SHA-256 of the CA certificate (DER, lowercase hex). The phone trusts only a
chain ending in that CA. The token goes in `Authorization: Bearer <token>` on the WebSocket handshake.

## Phone WebSocket (`/ws`)
JSON text frames. The server pings every 10 s and drops a phone that misses 10 s of pongs
(a phone that loses Wi-Fi shows offline within about 20 s).

**phone → server**
```jsonc
// first frame, within 10 s, or the socket is closed (4400)
{"type": "hello", "phone_id": "<stable uuid>", "name": "Phone 1", "app_version": "1.0",
 "sims": [{"sub_id": 1, "slot": 0, "carrier_name": "Hutch", "mcc": "413", "mnc": "08", "number": null}]}

// answer to a run (exactly one per request_id)
{"type": "result", "request_id": "…", "ok": true,
 "ussd_reply": "Your main balance is …",            // null for SMS instructions or on failure
 "sms": [{"sender": "HUTCH", "body": "…", "ts": "2026-11-22T08:20:06Z"}],
 "error": null}                                     // e.g. "USSD_RETURN_FAILURE (-1)", "timeout"

// carrier SMS outside a check (allowlisted senders only)
{"type": "sms", "sub_id": 1, "sender": "HUTCH", "body": "…", "ts": "…"}

// SIMs changed (inserted, removed, swapped)
{"type": "sims", "sims": [ …same shape as hello… ]}
```

**server → phone**
```jsonc
// right after hello: normalized (lowercase alphanumerics) senders whose text may be forwarded
{"type": "config", "allowed_senders": ["160", "344", "5555", "678", "dialog", "hutch", …]}

{"type": "run", "request_id": "…", "sub_id": 1, "instruction": {
  "method": "ussd", "code": "*344#",                 // or "sms" with "to" and "body"
  "expect_sms_from": ["HUTCH"],                      // collect SMS from these senders afterwards…
  "sms_wait_s": 30,                                  // …for up to this long…
  "sms_max": 1                                       // …or until this many arrived
}}
```
Phones run one instruction at a time (the server also serializes per phone). The server
times out a run after `45 + sms_wait_s + 20` s and records "phone did not answer in time".

A bad token is refused before the WebSocket is accepted, which reaches the phone as an HTTP 403
handshake response (tests see close code `4401`). Close codes after accept: `4400` no/invalid hello, `4409` replaced by a newer connection
from the same phone.

## Dashboard REST (`/api`)
OpenAPI at `http://localhost:8000/docs`; the TypeScript types come from it (`npm run gen:api`).
- `GET /api/sims`: every present SIM with `status` (`idle|checking|ok|parse_failed|error`),
  `phone_online`, `last` (latest attempt) and `last_ok` (latest successful reading).
- `PATCH /api/sims/{sim_id}` `{label, number}`.
- `GET /api/sims/{sim_id}/history`: last 50 readings (with raw text) and carrier texts.
- `POST /api/check/{sim_id}` → 202 `{result: started|busy}`; 409 offline; 422 no check for carrier.
- `POST /api/check-all` → 202 `{started: [...]}`.
- `GET /api/settings`, `GET /api/pairing`, `GET /api/pairing.svg`.
- `GET /api/events`: SSE. `event: sim` carries a full `Sim` on any change. `event: sms` carries
  `{sim_id, sender}`. `event: hello` is sent on connect.
