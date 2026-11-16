# BaresipKit 1.0.0

Draft of the GitHub release notes. Not published.

BaresipKit is a SIP voice and video SDK for iOS and Android, built on
[baresip](https://github.com/baresip/baresip) and [libre](https://github.com/baresip/re). Both
SDKs run the same C core with the same patches, so SIP, media and NAT traversal behave the same
on both; only the layers that talk to the operating system differ.

1.0.0 is the first stable release. From now on the public Swift and Kotlin API follows Semantic
Versioning as described in [Docs/STABILITY.md](../STABILITY.md), and CI checks it against the
committed API baselines.

## Highlights

- **Registration** over UDP, TCP and TLS, with private CAs, mutual TLS and SPKI pinning. Server
  certificates are always verified; there is no setting that turns this off.
- **Audio** with Opus, G.722 and G.711. **Video** with H.264 through the platform codec
  (VideoToolbox on iOS, MediaCodec on Android), and VP8 on Android.
- **System call integration**: CallKit or LiveCommunicationKit on iOS, Telecom on Android. The
  adapters report a pushed call at once, match it to the INVITE that follows, and wait for the
  engine's result before completing a system action.
- **Push**: VoIP pushes through PushKit on iOS and Firebase Cloud Messaging on Android, with the
  push token kept current in the registration's `Contact` parameters.
- **UI state**: `BaresipPhone` and `BaresipPhoneCall` publish registrations, calls, mute, hold,
  video and DTMF to SwiftUI or Compose, with video and camera preview components.
- **Door stations and intercoms**: early media preview before answering, video in audio calls,
  picture updates over SIP INFO, DTMF door codes with a confirmation reply.
- **NAT traversal**: STUN, ICE with TURN over UDP, TCP and TLS, ICE restart, RTCP multiplexing
  and a NAT pinhole for media relays.
- **Calls**: hold and resume, attended and blind transfer, SIP MESSAGE, SIP session timers
  (RFC 4028), the RFC 3326 Reason of calls answered or declined elsewhere.

## Supported platforms and requirements

| | iOS and macOS | Android |
|---|---|---|
| OS | iOS 16 or later; macOS 13 or later on Apple silicon (partial, see Known limitations) | Android 9 (API 28) or later, `arm64-v8a` and `x86_64` |
| Language | Swift 6 | Kotlin 2.1 or later, with coroutines; Kotlin callers only |
| Tools | Xcode 16 or later, Swift Package Manager | Gradle, Android Gradle Plugin 8 |
| UI | SwiftUI (`BaresipKitSwiftUI`) | Jetpack Compose (`baresipkit-compose`) |

## Installation

Swift Package Manager:

```swift
.package(url: "https://github.com/mariokovacevic/baresipkit", from: "1.0.0")
```

Gradle:

```kotlin
implementation("io.github.mariokovacevic:baresipkit:1.0.0")
```

The other Android libraries (`baresipkit-audio`, `-video`, `-telecom`, `-push`, `-compose`)
use the same group and version. The platform guides,
[Docs/iOS.md](../iOS.md) and [Docs/Android.md](../Android.md), have a quick start, the setup
recipes and an app checklist.

## Verifying the release

The release has `SHA256SUMS`, an SPDX SBOM for each platform, and build provenance attestations.
The XCFramework is the one CI built and tested at the tagged commit. To verify an attestation:

```bash
gh attestation verify BaresipKitCore.xcframework.zip --repo mariokovacevic/baresipkit
```

## Known limitations

- **Not tested on real Android devices.** The libraries and the Android sample app run in CI, the JVM
  tests against the host core and the UI tests on emulators with API 28 and 34.
- **FCM** has not been tried against Google's servers; the push path is tested with injected
  messages.
- **WSS** transport is not tested.
- **iOS 16** is the minimum, but CI tests on the newest simulator only.
- **Interop** is tested against Asterisk 23 and coturn only.
- **Android TLS** uses the system CAs from the system's CA directory. CAs the user installed, and
  the app's network security configuration, do not apply.
- **OpenSSL 3.5.9** is built without QUIC, which removes two of its four published
  vulnerabilities; the other two are in AES-GCM-SIV, which neither TLS nor SRTP uses. 1.0.x
  moves to OpenSSL 3.5.10 once it is released.
- **macOS** has no echo cancellation and no system call integration; no real call has been made
  on a Mac.
- **Performance** (start-up, registration, call setup, CPU and battery) has not been measured on
  a device.

[Docs/Limitations.md](../Limitations.md) has the full list, including ICE restart with Asterisk,
TURN over TCP and TLS reconnection and the recording formats.

## Upgrading from 0.x and earlier builds

1.0.0 is not source-compatible with earlier builds. The main changes:

- Server verification can no longer be turned off; a test server with a self-signed certificate
  goes into `caCertificatesPEM` / `caCertificatesPem`.
- The configuration requires a persisted `instanceUUID`; store one per install.
- Kotlin configuration types are built with builders, and plumbing types are no longer public.
- `hangup` is `hangUp` on Android, with the call argument first, as on iOS.
- The CallKit, LiveCommunicationKit and PushKit adapters no longer expose the system delegate
  methods.
- Media events the core never emitted are gone.
- The minimum TLS version accepts only TLS 1.2 and 1.3, and session timer minimums below 90
  seconds are rejected.

`CHANGELOG.md` lists every change and every behaviour change.

A `1.0.0` tag published earlier from an unfinished tree has been withdrawn; it could not be
resolved by Swift Package Manager.

## Acknowledgements

BaresipKit exists because of [baresip](https://github.com/baresip/baresip) and
[libre](https://github.com/baresip/re), and the work of their maintainers and contributors over
many years. It also builds on OpenSSL, Opus, libvpx and the G.722 reference code; their licenses
and notices are in [THIRD_PARTY_NOTICES.md](../../THIRD_PARTY_NOTICES.md).
