# SIM Monitor

On-demand balance / data / expiry checks for Sri Lankan SIMs (Dialog, SLT-Mobitel, Hutch).
Android phones run the checks; a desktop server stores readings and serves a dashboard.
LAN only, no third-party cloud. `CLAUDE.md` is a symlink to this file; edit `AGENTS.md` only.

If `AGENTS.local.md` exists (gitignored), read it too: it holds notes about the local machine.

Design and decisions: `docs/design.md` (read it before changing protocol or architecture).
Wire protocol and REST API: `docs/api.md` (keep in sync with `server/app/hub.py` / `main.py`).
User-facing overview: `README.md`. Install, operations and releasing: `docs/install.md`.

## Layout
- `android/` Kotlin + Compose app (single `app` module, package `app.simmonitor`), minSdk 26
  (Android 8 / Go) → targetSdk 37. Packages: `service/` (foreground `MonitorService`,
  `InstructionRunner`, `SmsBus`, `BootReceiver`), `net/` (`DesktopConnection` OkHttp WebSocket bound
  to the Wi-Fi network, `PinnedTrust`, `Pairing`), `settings/Prefs`, `ui/` (Status, Settings,
  Diagnostics tabs), `probe/` (USSD/SMS runners, SMS receiver, diagnostics log). Pairing: QR (ZXing,
  no Google services), pasted link, or `simmonitor://pair` deep link.
- `server/` Python 3.13 + FastAPI + SQLite, managed by uv. Code in `server/app/`, tests in `server/tests/`.
- `web/` React + TypeScript + Vite dashboard; `web/dist` (after `npm run build`) is served by the server.
  TanStack Query for data, `useSimEvents` patches the cache from SSE; one component + CSS module each.
- `docs/` design, API/protocol, install/release, carrier reply samples (parser fixtures).

## Isolated toolchain (always `source ./env.sh` from the repo root first)
Everything the project pulls in lives in `.devenv/` (gitignored), not in `~` or the system.
- uv binary, uv-managed CPython 3.13, uv cache and tools: `.devenv/{bin,python,uv-cache,uv-tools}`.
  Don't use the system Python and don't `pip install` anything globally.
- Gradle user home: `.devenv/gradle-home`. Android Studio ignores `env.sh`: set its Gradle user home
  to `.devenv/gradle-home` if opening the project there.
- npm cache: `.devenv/npm-cache`.
- Not project deps (install yourself): Android SDK (compileSdk 37), JDK 21 (`android/.sdkmanrc`),
  Node 22+, Docker.

## Settings
Local settings live in `.env` (gitignored; template `.env.example`). `SIM_MONITOR_HOST` (the desktop's
fixed LAN IP) is required to serve phones. There is deliberately no default, and tests and docs use RFC 5737
addresses (`192.0.2.x`).

## Commands
`make help` lists everything. Main ones: `make test` (all suites + linters), `make dev` (server from source),
`make deploy` (release server in Docker), `make apk` (signed release APK → `dist/`), `make backup`.

Details per part:
- Server (dev): `make dev` starts both listeners: dashboard `http://localhost:8000` (loopback only) and
  phones `wss://$SIM_MONITOR_HOST:8443/ws` (TLS). The first run creates `server/data/` (SQLite DB,
  token, private CA + cert; gitignored). `uv run python tools/fake_phone.py [--flaky]` connects a
  fake phone that answers with sample replies. Tests/lint: `uv run pytest`, `uv run ruff check . && uv run ruff format .`.
- Web: `cd web && npm run dev` (proxies `/api` to :8000), `npm run build`, `npm test`, `npm run lint`.
  `npm run gen:api` regenerates `src/api/schema.d.ts` from the running server's OpenAPI; run it after
  any API change. It uses npx with pinned TypeScript 5 because openapi-typescript doesn't support TS 7.
- Android: `cd android && ./gradlew testDebugUnitTest lintDebug assembleDebug`; install with
  `adb install -r` (avoids the Android 13+ "restricted settings" block on SMS permissions that
  file-manager installs hit). The Compose BOM is held at 2026.09: newer ones need compileSdk 37.1.
- Emulator: its SIM is a fake US one (carrier "unknown", no check defined). USSD fails, SMS sending works,
  and `adb emu sms send <sender> "<text>"` fakes an incoming SMS. App log:
  `adb exec-out run-as app.simmonitor cat files/probe-log.jsonl`. Pair without a camera:
  `adb shell "am start -a android.intent.action.VIEW -d '<uri from /api/pairing>'"`. For flow tests,
  set `carrier='hutch'` on the emulator's SIM in the DB (it resets on the next hello), and delete
  test phones from the DB afterwards.

## Release
- Version: repo-root `VERSION` only (server image tag, `SIM_MONITOR_VERSION`, APK versionName/versionCode).
- Release server: Docker image `sim-monitor:<version>`, container `sim-monitor`, host network,
  `--env-file .env`, volume `sim-monitor-data` (token, CA, DB; the dev server uses `server/data`).
  Don't run `make dev` while the container is up (same ports).
- APK signing key: `~/.config/sim-monitor/release.{jks,properties}` by default (override with
  `SIM_MONITOR_SIGNING`), outside the repo; never commit it. R8 is on for release; smoke-test
  `make apk` output on an emulator (QR scanner, pairing, a check).
- GitHub: tag `v<version>`, attach the APK to the release (see `docs/install.md`).

## Conventions
- The phone does what the desktop tells it; carrier knowledge (codes, parsers) lives on the server.
  Always store raw reply text; a parse failure keeps the raw text and raises a "parser broke" alert.
- Parsers are written from real reply formats. Real captures go in gitignored `docs/replies/local/`;
  committed fixtures (`docs/replies/samples.jsonl`) keep the exact wording with invented numbers.
- Public repo hygiene: never commit personal data: real balances, phone numbers, IPs/SSIDs/MACs,
  device models, names, non-carrier SMS senders or texts, tokens, keys, server data, or local paths.
  Machine-specific notes go in `AGENTS.local.md`. Before committing, grep the diff for them.
- Every carrier code is unverified until it has run on a real device; the emulator has no real SIM.
- Guard newer Android APIs with `Build.VERSION.SDK_INT` checks; keep the app light for Go devices.
