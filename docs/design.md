# Design

## Goal
Check balance, data and expiry for every SIM on demand from a desktop dashboard. No scheduled
polling. Everything stays on the home network.

## Topology
```
 browser ──http://localhost:8000──▶ desktop server (FastAPI + SQLite) ◀──wss://<desktop-ip>:8443/ws── phone app ×N
 (same machine)                      serves dashboard, owns carrier codes                 (≤2 SIMs each)
                                     and parsers, stores readings
```
- **Phones connect out; the desktop never calls a phone.** Each phone holds one WebSocket to the
  desktop. Open socket = online, closed = offline (no `/register`/`/unregister`, no phone HTTP server,
  no phone IPs). A successful connect also proves the phone is on the home network, so the app
  never reads the SSID (that needs location permission on Android 10+).
- The phone opens the socket when Wi-Fi becomes available (`ConnectivityManager.NetworkCallback`,
  Wi-Fi transport only) and reconnects with backoff.

## Security
- One shared token (`Authorization: Bearer`) on the WebSocket handshake.
- The phone connection uses TLS. On first run the server creates a private CA and a server certificate for
  the desktop's LAN address (`SIM_MONITOR_HOST`). The app trusts only that CA, pinned by fingerprint, not the system store.
  Pairing: the dashboard shows a QR code with `{host, port, token, ca_sha256}`, scanned once per phone.
- Dashboard: plain HTTP on `localhost` only. Dashboard routes reject non-loopback clients.
- Two listeners in one process (`python -m app`): one port can't be both TLS and plain.

## SIM identity
Android 10+ hides the ICCID from non-system apps, and Sri Lankan SIMs often don't expose the
number. A SIM is identified by `sim_id = <phone_id>:<subscriptionId>` (Android keeps the
subscription ID when the same SIM goes back into the same phone). The label and number are set
by the user on the dashboard.

## Who knows what
- **Phone = executor.** It runs instructions such as `{"method":"ussd","code":"#456#"}` or
  `{"method":"sms","to":"160","body":"DB","expect_from":"160","timeout_s":60}` and returns the raw reply.
- **Desktop = carrier knowledge.** It holds the codes per carrier and the parsers. Fixing a parser or
  changing a code never requires reinstalling the app.
- **SMS privacy:** the phone forwards SMS text only from allowlisted carrier senders (the desktop sends the
  list per carrier; the probe has it hardcoded in `CarrierSenders`). Other senders (banks, OTPs,
  people) are reported as sender-only, never their text. Carrier SMS that don't match a pending
  check are forwarded and logged as carrier texts.

## Protocol sketch (WebSocket JSON messages; finalized in `docs/api.md`, milestone 3)
- phone → desktop: `hello {phone_id, name, app_version, sims:[{sub_id, slot, carrier_name, carrier_id, number?}]}`,
  `sims_changed {...}`, `result {request_id, sim_id, ok, raw, error?}`, `sms {sim_id, sender, body, ts}`.
- desktop → phone: `run {request_id, sim_id, instruction}`, `ping`.

## Android notes
- minSdk 26 (Android 8.0, needed for `sendUssdRequest`; covers Android Go 8.1+) → targetSdk 37.
- Foreground service type `specialUse` (Android 14+); `POST_NOTIFICATIONS` (13+); restricted-settings
  guidance for SMS perms on file-manager installs (13+); `READ_PHONE_NUMBERS` for numbers (11+).
- The battery-optimization exemption keeps the socket alive in Doze. Some brands (Xiaomi, Samsung, Oppo)
  need extra steps; the app links to them.
- `sendUssdRequest` handles single-shot codes only; menus (e.g. Dialog `#678#`) can't be driven.
- **Android 17 withholds SMS that look like one-time codes** from apps other than the default SMS app
  (compat change 437043173, "skipped by policy" in `dumpsys activity broadcasts`). That's good for
  privacy, but if a carrier balance reply is ever classified as an OTP, the app won't see it. Watch for
  this on Android 17 phones (testing so far was on Android 16).
- Server heartbeat: a phone that loses Wi-Fi can't send a close, so the server notices from missed
  pongs (10 s ping / 10 s timeout, about 20 s).
- Tested on the emulator: pairing via link, TLS pin, check round trip with SMS collection and parsing,
  carrier vs bank SMS filtering, Wi-Fi loss/return, server restart, bad token → "Pairing rejected",
  restart after app update and after reboot, check delivered while in forced Doze.

## Milestones
1. Scaffold: repo, isolated toolchain, empty server/web/android projects. ✅
2. Probe app: list SIMs, run USSD/SMS per SIM, log raw replies (screen + JSONL, shareable). ✅ (emulator)
   → Validate every carrier code on real phones.
3. Server core: SQLite schema, WebSocket hub, token auth, TLS/CA, readings, SMS log, pytest with a fake phone. ✅
4. Phone service: foreground service, WebSocket client, instruction runner, SMS matching, settings + QR pairing. ✅ (emulator)
5. Check flow end to end: `/api/check/{sim_id}`, SSE `/api/events`, timeouts. ✅ (real phone, real SIMs)
6. Dashboard (React): table and row states, highlights for low balance and expiry within 3 days, history drawer. ✅
7. Parsers per carrier from captured replies + "parser broke" alert. Hutch + Dialog done; Mobitel skipped
   for now (no SIM to capture replies from).
8. Running it: release server as a Docker container (restarts at boot), Makefile, signed + shrunk release
   APK, phone install notes (`docs/install.md`). ✅ v1.0.0

## Out of scope (v1)
Scheduled polling, remote access, Telegram alerts, phone-to-phone sync, Play Store.

## Open questions
- Dialog data: Dialog's FAQ says SMS `MYUSE` to `678` (prepaid) / `MBB` to `678` (postpaid).
  Does the reply include expiry? Verify on a real SIM.
- Does `sendUssdRequest` return reply text on each phone and carrier? **Yes on an Android 16
  phone for Hutch and Dialog.** Still to check: Mobitel and an Android Go phone.

## Findings so far (formats in `docs/replies/samples.jsonl`)
- Hutch `*344#`: USSD reply with main balance and expiry, then a `HUTCH` SMS within about 3 s listing every
  balance (main, SMS, data, points) with expiries. One check covers everything.
- Dialog `#456#`: main balance and SIM validity only. `#678#` → `USSD_RETURN_FAILURE` (menu).
- Dialog SMS `MYUSE`→678 fails with `RIL_SMS_SEND_FAIL_RETRY` when main balance is near zero (SMS needs credit).
- Dialog sender `5555` sends data-plan activation ("Valid for 30 days") and free-data balance texts
  ("you have 1024.00 MB free Data"), without an expiry date.
