# Transformer (Android)

Native Android counterpart to the `Transformer.dc.html` web prototype in the
repo root — same product (LAN-only, end-to-end-encrypted file/message
transfer between paired devices), rebuilt as a real app instead of a browser
page. Going native removes the two hard limits the web version had to live
with:

- **Real automatic reconnection.** Android's `NsdManager` (mDNS) lets every
  paired device advertise and discover the other on the LAN — no QR re-scan
  needed once paired, even if DHCP hands out a new IP.
- **Real background operation.** A foreground `Service` keeps the TCP server
  and NSD advertising/discovery running while the app isn't in the
  foreground, so a queued message or file can still arrive and get delivered
  without the user keeping the app open.

## Architecture

Two Gradle modules:

- **`core`** — pure Kotlin/JVM, zero Android dependency. AES-256-GCM
  crypto, the NDJSON-over-TCP-socket protocol (`msg` / `file-meta` /
  `file-chunk` / `ack`, the same four frame types the web version speaks
  over WebRTC data channels), the pairing payload codec, and the
  socket server/client. Because it's plain JVM, it's fully unit-tested with
  **real `java.net.Socket`s on localhost** — see
  `core/src/test/kotlin/.../PeerConnectionTest.kt`, which pairs two
  in-process "devices," sends an encrypted message, sends a 500 KB file and
  checks the reassembled bytes are exact, cancels a transfer mid-flight, and
  reconnects reusing the same key. Also covers HKDF against RFC 5869's own
  test vectors (`HkdfTest`), and — by forging raw wire frames directly,
  bypassing `PeerConnection`'s own client code — that a replayed frame
  closes the connection instead of being accepted twice, and that a frame
  captured on one connection is rejected outright on a different one. Run
  it with:

  ```
  ./gradlew :core:test
  ```

- **`app`** — the actual Android app: Jetpack Compose UI (Material 3,
  themed from the same design tokens as the web version's `styles.css`),
  `NsdCoordinator` (advertise/discover), `TransferService` (foreground
  service hosting the always-on `PeerServer` + NSD + the outbox queue),
  `PeerStore` (DataStore-backed persisted pairings, encrypted at rest —
  see Encryption below), QR generation (ZXing,
  vendored, no CDN) and QR scanning (CameraX + ML Kit barcode scanning).

### Why pairing is simpler than the web version

The web prototype had to do a two-QR WebRTC offer/answer dance because
browsers can't open a raw listening socket. A native app can: every device
runs one `PeerServer` all the time. So a pairing QR just carries
`{id, name, host, port, key}` — the shown device's own address — and the
scanning device dials straight in. One QR, one scan, no second round trip.
Reconnecting an already-paired device (after mDNS finds it again, or when
you tap "Neu verbinden") reuses the same flow but omits `key`, since both
sides already have it.

### Encryption

Every message and file chunk is AES-256-GCM encrypted, on top of (not
instead of) whatever the TCP connection itself provides — plain TCP has no
transport encryption, so this app-layer AES-GCM is what actually protects
the content on the wire.

The key generated once at pairing time and carried only inside that first
QR code is never used to encrypt frames directly, though. Each connection
derives two fresh AES-256 subkeys from it via HKDF (RFC 5869, HMAC-SHA256;
`core/.../Hkdf.kt`, checked against the RFC's own test vectors in
`HkdfTest`) — one for client→server, one for server→client — salted with a
random nonce the connecting client generates fresh for that connection
(carried in the plaintext hello preamble, alongside the pairing id).
Consequences:

- A client and a server on the same connection never share a key.
- No two connections — even an instant reconnect of the very same pairing —
  ever derive the same keys, so a ciphertext frame captured on one
  connection cannot be replayed into a different one; it fails to decrypt
  outright.
- Every frame also binds its type and a per-direction sequence number into
  the GCM tag as additional authenticated data, so replaying or reordering
  a frame *within* one connection fails the tag check the same way a
  tampered ciphertext would.

Both properties are exercised in `PeerConnectionTest` by forging raw wire
frames byte-for-byte (bypassing `PeerConnection`'s own client code
entirely) and confirming the server rejects them — the point being that the
server has to defend itself against arbitrary bytes, not just against
whatever our own client happens to send.

Two further hardenings on top of the crypto itself: `PeerServer` drops a
connection that never sends its hello line within 10s (a "slow-loris"
wouldn't otherwise be told apart from someone on a slow network) and caps
concurrent connections at 64 rather than spawning a thread per socket
without bound; and a `FileMetaBody` claiming a negative size, more than
20 GiB, or more than 500,000 chunks is rejected before any buffer for it is
allocated, so a malicious or buggy peer can't trigger an OOM just by
lying in its own metadata frame.

On the Android side, every stored pairing key sits in `PeerStore` encrypted
with an AES-256 key that lives only in the Android Keystore
(`data/SecureStorage.kt`) — hardware-backed where the device supports it —
rather than as plaintext in DataStore's preferences file, and the app
declares `allowBackup="false"` so none of it can leave the device via
`adb backup` or cloud backup in the first place.

One thing this deliberately does *not* change: the hello preamble
(`{id, name, nonce}`) is still sent before any key is even looked up, so
`name` — the paired device's display name, nothing more sensitive than
that — is visible in the clear to anyone already able to sniff traffic on
the LAN. Fixing that would mean moving the name into the first encrypted
frame and making its delivery asynchronous, which ripples into how
`TransferService` learns a newly-accepted peer's name; left as a known,
narrowly-scoped gap rather than folded into this pass.

### LAN-only, by construction

There's no server anywhere in this design — not even for signaling. A
`PeerServer` just calls `ServerSocket(port)` and a `PeerClient` just calls
`Socket().connect(InetSocketAddress(host, port))` to whatever local IP was
in the QR code or resolved via NSD. If the two devices aren't on the same
network, there's no address to connect to and no fallback that would route
through the internet.

## What's verified here vs. what needs Android Studio

This was built in a sandboxed environment with **no Android SDK** (Google's
`dl.google.com`, which serves both SDK platform components and — for this
proxy's policy — every Android Gradle Plugin / AndroidX / Compose / CameraX
/ ML Kit artifact resolution, was blocked). Concretely:

- `core` — builds and its full test suite passes here, for real, on the
  actual JVM socket/crypto code that also runs inside the app.
- `app` — **could not be synced, compiled, or run here at all.** Every file
  under `app/` was written carefully against the stable, documented APIs
  (Jetpack Compose, `NsdManager`, CameraX, ML Kit barcode scanning,
  DataStore, foreground `Service`, Android Keystore) and cross-checked by hand (every
  `viewModel.xxx()` call against the ViewModel's actual methods, every
  `state.xxx` / `d.xxx` / `m.xxx` field access against its data class,
  every `TransferService` call the ViewModel makes against what the service
  actually exposes) — but none of it has been compiled, let alone run on a
  device or emulator.

**First thing to do in Android Studio: open `android/`, let it sync, and
build.** Straightforward version/API mismatches (AGP, Compose BOM, CameraX,
ML Kit versions drifting since this was written) are the most likely
failure class — bump `gradle/libs.versions.toml` as needed. Please report
back whatever the first build turns up so it can get fixed quickly.

Fonts (Caprasimo, Figtree — same as the web version) are bundled as actual
`.ttf` files under `app/src/main/res/font/`, fetched directly from Google
Fonts rather than wired through the Play-Services downloadable-fonts
provider — that provider needs a set of certificate hashes hard-coded into
`font_certs.xml`, and fabricating those from memory looked exactly like the
kind of "plausible but unverified" cryptographic data that's worth avoiding
rather than risking a silent runtime failure.

## Building

```
cd android
./gradlew assembleDebug
```

(needs a real Android SDK + `local.properties` pointing at it, which Android
Studio sets up automatically on first open).

## Trying it out

Install the APK on two devices on the same Wi-Fi. On one, tap **QR-Code
zeigen**; on the other, **QR-Code scannen**. Once paired, closing and
reopening either app should reconnect automatically within a few seconds
(NSD discovery) — no re-scan needed unless a device was unpaired via
Settings → Entkoppeln.
