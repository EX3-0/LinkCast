# LinkCast


> [!CAUTION]
> **AUTONOMOUS AGENT TEST REPOSITORY**
>
> This repository, including its application code, userscript, build configuration,
> documentation, and initial publication, was created fully autonomously by an AI
> coding agent. It exists **exclusively for testing and experimentation**. It is not
> production software, has not received a professional security review, and must not
> be relied upon for safety-critical, privacy-sensitive, or unattended use.

LinkCast sends an HTTP(S) link from an Android phone's Sharesheet to the currently visible browser tab on an Android TV. Everything stays on the local network.

## Components

- **phone** — minimal Android phone app with `TV IP`, **Find TV**, link field, and **Send**. It is also a text/plain Android share target named **Cast to TV browser**.
- **tvhost** — Android TV foreground service. It advertises `_linkcast._tcp.local` with Android NSD/mDNS and listens on TCP port `8765`.
- **userscript/linkcast-tv.user.js** — Tampermonkey receiver. It long-polls the TV host through `127.0.0.1` and navigates the visible tab.

The phone discovers the TV automatically. A random per-install capability token is included in the mDNS TXT record and required by `/send`. The userscript endpoint is restricted to loopback.


## Fire TV / Fire Stick behavior

The TV host uses an Android foreground service with a persistent notification,
`START_STICKY`, and a boot receiver. That makes background hosting practical on a
Fire TV Stick, but it does **not** guarantee permanent execution:

- Fire OS may stop the service under memory pressure or vendor power management.
- Force-stopping the app prevents Android from restarting it until it is launched again.
- Boot receivers and foreground-service starts can be restricted on newer Fire OS versions.
- The host app must be launched once after installation, and may need to be excluded from
  power optimization where the device exposes that option.
- If the receiver stops responding, reopen **LinkCast TV Host** and choose
  **Start / Restart Host**.

This behavior is suitable for personal testing, not guaranteed unattended operation.

## GitHub Actions build

The repository includes `.github/workflows/build-android.yml`. Every push to `main`
that changes build inputs runs a hosted Android build. It can also be started manually
from **Actions → Build Android APKs → Run workflow**.

A successful run produces the `linkcast-debug-build` artifact containing:

- `LinkCast-phone-debug.apk`
- `LinkCast-tvhost-debug.apk`
- `linkcast-tv.user.js`
- `SHA256SUMS.txt`

These are debug/test artifacts, retained for 14 days, and are not signed for production
distribution.

## Build

Requirements:

- JDK 17 or newer
- Android SDK Platform 35
- Android SDK Build Tools 35.x
- Internet access for the first Gradle/Android Gradle Plugin download

Set `ANDROID_HOME` or create `local.properties`:

```properties
sdk.dir=C\:\\Users\\you\\AppData\\Local\\Android\\Sdk
```

Then build:

```shell
gradlew.bat :phone:assembleDebug :tvhost:assembleDebug
```

Outputs:

```text
phone/build/outputs/apk/debug/phone-debug.apk
tvhost/build/outputs/apk/debug/tvhost-debug.apk
```

## Install and use

1. Sideload `tvhost-debug.apk` on the Android TV.
2. Launch **LinkCast TV Host**, grant Nearby Devices, and leave its foreground service running.
3. Install Tampermonkey in the TV browser and import `userscript/linkcast-tv.user.js`.
4. Open any ordinary HTTP or HTTPS page in that browser so the userscript is active.
5. Sideload `phone-debug.apk` on the Android phone and grant Nearby Devices.
6. Either:
   - open LinkCast, press **Find TV**, paste a URL, and press **Send**; or
   - use Android **Share → Cast to TV browser** from another app. LinkCast discovers the TV and sends the shared link automatically.

Both devices must be on the same LAN/Wi-Fi, and the network must permit multicast/mDNS and client-to-client traffic.

## API

- `POST /send` — LAN endpoint; plain UTF-8 URL body and `X-LinkCast-Token` header; returns `202`.
- `GET /next` — loopback-only 25-second long poll used by the userscript.
- `GET /status` — basic health response.

Only `http:` and `https:` URLs are accepted by the phone, TV host, and userscript.

## Practical limitations

- Tampermonkey must support `GM_xmlhttpRequest` to loopback.
- Browser-internal pages, PDFs, new-tab pages, and pages where Tampermonkey does not inject cannot receive a command. Keep a normal web page open.
- Android may stop the TV service after force-stop or aggressive vendor power management. Exempt the app from battery optimization if needed.
- If several LinkCast TVs are present, this version uses the first one discovered.
- The discovery token prevents accidental requests, not a determined attacker already on the same LAN: mDNS TXT records are visible to LAN clients.
