# LinkCast


> [!CAUTION]
> **AUTONOMOUS AGENT TEST REPOSITORY**
>
> This repository, including its application code, userscript, build configuration,
> documentation, and initial publication, was created fully autonomously by an AI
> coding agent. It exists **exclusively for testing and experimentation**. It is not
> production software, has not received a professional security review, and must not
> be relied upon for safety-critical, privacy-sensitive, or unattended use.

LinkCast sends an HTTP(S) link from an Android phone's Sharesheet directly to a full-screen browser built into the Android TV/Fire TV app. Everything stays on the local network; no browser extension or cloud relay is required.

## Components

- **phone** — minimal Android phone app with `TV IP`, **Find TV**, link field, and **Send**. It is also a text/plain Android share target named **Cast to TV browser**.
- **tvhost** — Android TV/Fire TV foreground receiver with its own full-screen WebView browser. It advertises `_linkcast._tcp.local` with Android NSD/mDNS and listens on TCP port `8765`.
- **userscript/linkcast-tv.user.js** — optional legacy fallback for a separate Tampermonkey-capable browser; it is not needed for normal use.

The phone discovers the TV automatically. A random per-install capability token is included in the mDNS TXT record and required by `/send`. Accepted links are saved until displayed, so reopening the TV app can recover a waiting link.


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

1. Sideload `tvhost-debug.apk` on the Android TV or Fire TV Stick.
2. Launch **LinkCast TV Browser** once and grant Nearby Devices. Leave the app open
   when you want received links to appear immediately.
3. Sideload `phone-debug.apk` on the Android phone and grant Nearby Devices.
4. Either:
   - open LinkCast, press **Find TV**, paste a URL, and press **Send**; or
   - use Android **Share → Cast to TV browser** from another app. LinkCast discovers
     the TV and sends the shared link automatically.

The TV app opens the received page itself. Press **Back** to navigate web history;
press Back at the first page to return to the LinkCast receiver screen. Fullscreen
HTML5 video is supported by the system WebView.

Both devices must be on the same LAN/Wi-Fi, and the network must permit multicast/mDNS and client-to-client traffic.

## API

- `POST /send` — LAN endpoint; plain UTF-8 URL body and `X-LinkCast-Token` header; returns `202`.
- `GET /next` — loopback-only 25-second long poll retained for the optional userscript.
- `GET /status` — basic health response.

Only `http:` and `https:` URLs are accepted by the phone, TV host, and userscript.

## Practical limitations

- Rendering and codec support come from the Fire OS/Android System WebView. Old
  Fire OS versions may require a WebView update.
- DRM-heavy services, proprietary codecs, downloads, popups, camera/microphone access,
  and sites requiring an official app may not work in WebView.
- Some Fire OS versions restrict launching an activity from the background. If a link
  is waiting but does not appear, open the LinkCast notification/app; the URL remains queued.
- Android may stop the TV service after force-stop or aggressive vendor power management.
  Reopen the TV app if needed.
- If several LinkCast TVs are present, this version uses the first one discovered.
- The discovery token prevents accidental requests, not a determined attacker already
  on the same LAN: mDNS TXT records are visible to LAN clients.
