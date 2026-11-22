# Install and operate

## Desktop server (Docker)
The release server runs as the `sim-monitor` container with host networking, so the dashboard is
at `http://localhost:8000` (loopback only) and phones connect to `wss://<desktop-ip>:8443/ws`.
It restarts with Docker at boot (`--restart unless-stopped`). Its state is in the
`sim-monitor-data` Docker volume, and its settings come from `.env` (`SIM_MONITOR_HOST` is required).

| Task | Command |
|---|---|
| Build and (re)start | `make deploy` |
| Status / logs | `make status`, `make logs` |
| Stop (stays stopped after reboot) | `make stop` |
| Back up token, CA key and DB | `make backup` → `backups/*.tgz` (gitignored; copy it somewhere safe) |
| First install on a new machine | `make seed-data` (copies `server/data`) or restore a backup into the volume |

The volume holds the **private CA key and token**. Losing them means re-pairing every phone. Back it
up after pairing new phones.

Restore a backup: `docker run --rm -v sim-monitor-data:/data -v "$PWD/backups":/in alpine:3 sh -c 'tar xzf /in/<file>.tgz -C /data && chown -R 10001:10001 /data'`, then `make deploy`.

## Phones
APK: `dist/sim-monitor-<version>.apk` (`make apk`), also attached to each GitHub release.
Works on Android 8.0+ (Android Go included).

1. **Install**
   - With adb (no extra steps): `adb install -r sim-monitor-<version>.apk`.
   - From a file: allow "Install unknown apps" for the file manager or browser, then on Android 13+ go to
     App info → ⋮ → **Allow restricted settings**, or the SMS permissions can't be granted.
   - Play Protect may warn about an unknown app: **Install anyway**.
2. **Status tab:** grant phone & SMS permissions, notifications, and **Unrestricted battery**. Without the
   battery exemption, Android cuts the app's network in deep sleep and the phone shows offline.
3. **Brand-specific background settings** (the Status tab shows the right one). OnePlus/Oppo: App info →
   Battery → **Allow background activity**, and lock the app in Recents.
4. **Pair:** dashboard → **Pair phone**; app → Settings → **Scan QR code** → confirm.
5. The phone's SIMs appear on the dashboard. Give them labels in the row's detail panel.

### Signing key
Release APKs are signed with `~/.config/sim-monitor/release.jks` (password in `release.properties`
beside it; override with `SIM_MONITOR_SIGNING=/path/to/release.properties`). **Back that folder up.**
Updates only install over an APK signed with the same key. A different key means uninstall + re-pair.
Debug builds (`./gradlew assembleDebug`) use the local debug key and can't update a release install
(or the reverse) without uninstalling.

## Releasing
1. Bump `VERSION` (semver; the APK versionCode is derived from it, e.g. 1.2.3 → 10203).
2. `make test`
3. `make deploy` (server) and `make apk` (phone).
4. Commit, `git tag v<version>`, push with tags, and
   `gh release create v<version> dist/sim-monitor-<version>.apk --title v<version> --notes …`.
5. Update phones: `adb install -r` or send them the APK. Pairing and data survive updates.
