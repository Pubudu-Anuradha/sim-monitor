# SIM Monitor

Check the balance, data and expiry of all your Sri Lankan SIMs from one dashboard, on demand.

Your Android phones run the carrier's own balance codes (USSD or SMS) when you press **Check**, and a
small server on your desktop collects and shows the results. Everything stays on your home network:
no cloud service, no accounts, no scheduled polling.

```
 browser ──http://localhost:8000──▶ desktop server ◀──wss (TLS, pinned)── phone app (×N, ≤2 SIMs each)
 (same machine)                     dashboard, carrier                    runs USSD / SMS checks,
                                    codes + parsers, history              forwards carrier texts
```

## Features
- **One dashboard for every SIM** across several phones: balance, data, expiry, when it was last
  checked, and a **Check** / **Check all** button. It updates live as replies arrive.
- **Highlights** for low balance and anything expiring within 3 days.
- **History per SIM:** every reading with the carrier's raw reply text, plus carrier alert texts
  (e.g. "your pack expires tomorrow").
- **Robust to wording changes:** the raw reply is always kept. If a carrier changes its message, the row
  shows *Parse failed* with the last good values, and the parser can be fixed on the server without
  touching the phones.
- **Offline-aware:** a phone that leaves Wi-Fi shows as offline with its last-known values.

## Carriers
| Carrier | Check | Status |
|---|---|---|
| Hutch | USSD `*344#` + the HUTCH SMS that follows (all balances and expiries) | Verified |
| Dialog | USSD `#456#` (main balance, validity) + 5555 SMS (free data) | Verified; no data expiry in the reply |
| SLT-Mobitel | USSD `*100#` | **Unverified** (no replies captured yet) |

Only single-reply USSD codes work: Android's `sendUssdRequest` can't navigate menus such as Dialog's `#678#`.
Some checks send an SMS, which may cost a little and needs credit on the SIM.

## Privacy and security
- **LAN only.** The phones connect out to your desktop; nothing talks to the internet.
- **Encrypted and pinned.** On first run the server creates its own certificate authority. Phones pair
  by scanning a QR code with the server address, a shared token and the CA's fingerprint, and then
  trust only that CA.
- **Dashboard is local.** It's served on `127.0.0.1` only and refuses other clients.
- **SMS stay private.** The phone only forwards texts from known carrier senders. Anything else (banks,
  OTPs, people) is never read beyond its sender name and never leaves the phone. Android 17 also
  withholds OTP-looking texts from apps like this one.

## Requirements
- A desktop on the same network as the phones, with a **fixed LAN IP** (a DHCP reservation on your
  router) and **Docker**.
- Phones on **Android 8.0+** (Android Go works) with the SIMs to monitor, on the same Wi-Fi.

## Quick start
```sh
git clone <this repo> && cd sim-monitor
cp .env.example .env          # set SIM_MONITOR_HOST to the desktop's LAN IP
make deploy                   # builds and starts the server container; restarts at boot
```
Open <http://localhost:8000>.

**Phone app:** download the APK from the latest [release](../../releases) or build it with `make apk`.
Install it (`adb install sim-monitor-<version>.apk` is easiest), then:
1. On the app's **Status** tab, grant phone and SMS permissions, notifications, and **unrestricted
   battery**. Without that last one, Android cuts the app off while the phone sleeps. The tab also shows
   extra steps for brands that kill background apps (OnePlus, Xiaomi, Samsung, …).
2. On the dashboard click **Pair phone**. In the app go to **Settings → Scan QR code**, then confirm.
3. The phone's SIMs appear on the dashboard. Press **Check**.

If you install the APK from a file instead of adb, Android 13+ blocks SMS permissions until you open
App info → ⋮ → **Allow restricted settings**.

More detail (backups, updates, signing keys, releasing): [docs/install.md](docs/install.md).

## Development
Dev tooling is kept inside the repo (`.devenv/`): `source ./env.sh` sets up uv, a project-local
Python 3.13, and Gradle and npm caches. You need the Android SDK (compileSdk 37), JDK 21, Node 22+
and Docker installed.

```sh
source ./env.sh
make test        # server (pytest, ruff), web (vitest, oxlint, build), android (unit tests, lint)
make dev         # run the server from source
cd server && uv run python tools/fake_phone.py   # a fake phone answering with sample replies
cd web && npm run dev                            # dashboard with hot reload (proxies /api)
```

| Part | Stack |
|---|---|
| `android/` | Kotlin, Jetpack Compose, OkHttp WebSocket, ZXing (QR) |
| `server/` | Python 3.13, FastAPI, SQLite, uvicorn |
| `web/` | React, TypeScript, Vite, TanStack Query; API types generated from the server's OpenAPI |

Design notes: [docs/design.md](docs/design.md). Protocol and API: [docs/api.md](docs/api.md).

### Adding or fixing a carrier
Carrier knowledge lives on the server, in `server/app/carriers.py`: the check to run (`CHECKS`), the
SMS senders to trust (`SENDERS`), and a parser. Capture real replies with the app's **Diagnostics**
tab. Copy their format into `docs/replies/samples.jsonl` **with the numbers changed** (real captures go
in the gitignored `docs/replies/local/`), then write the parser and tests against them.

## License
[GPL-3.0](LICENSE). Not affiliated with Dialog, SLT-Mobitel or Hutch.
