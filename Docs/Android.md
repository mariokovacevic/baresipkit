# Android setup

BaresipKit for Android: Kotlin libraries over a JNI bridge to the C core, with the same features
and types as the [Swift package](iOS.md). Names follow each language's conventions: acronyms
longer than two letters are capitalized like words in Kotlin, so Swift's `BaresipDTMFReply`,
`BaresipTLSConfiguration` and `BaresipICEState` are `BaresipDtmfReply`, `BaresipTlsConfiguration`
and `BaresipIceState` here. What Semantic Versioning covers is in [STABILITY.md](STABILITY.md).
This guide covers installing the libraries, a quick start, the setup recipes, TLS, NAT
traversal, incoming calls on Android 14 and later, what the libraries add to the manifest, the
sample app, building from source, tests and releases. How the two platforms share the core and
differ where they must is in [Platforms.md](Platforms.md).

> [!IMPORTANT]
> **Release candidate.** The API is frozen for 1.0 and follows [STABILITY.md](STABILITY.md). The
> Android SDK has run in CI, on the JVM and on emulators, but not yet on a physical device. Test it with your own
> devices and SIP server before relying on it.

## Modules

| Artifact | What it gives you | Add it when |
|---|---|---|
| `baresipkit` | `BaresipEngine`: accounts, calls, media, messages, events as a `Flow`, logging (Logcat, files), TLS, NAT, network and app lifecycle helpers; `BaresipCallController`, `SipIdentity` | Always |
| `baresipkit-audio` | `BaresipAudioController`: audio focus, communication mode, earpiece, speaker, Bluetooth and wired outputs | You manage audio yourself (no Telecom) |
| `baresipkit-video` | `BaresipVideoView` (OpenGL ES), `BaresipCamera` (Camera2 into the engine's camera source) | You show or send video |
| `baresipkit-telecom` | `BaresipTelecomAdapter`: calls in Telecom through Jetpack core-telecom, push-to-INVITE matching; `BaresipRedialRequest` for calls asked for with a `sip:` or `tel:` intent | You want the system to know your calls (recommended) |
| `baresipkit-push` | `BaresipPushAdapter`: Firebase tokens as `Contact` parameters, pushed calls | You receive calls while the app is not running |
| `baresipkit-compose` | `BaresipPhone`, `BaresipPhoneCall`, `BaresipCallVideo`, `BaresipCameraPreview` | You build the UI in Compose |

## Requirements

- Android 9 (API 28) or later, on `arm64-v8a` or `x86_64`
- Kotlin 2.1 or later, with coroutines; Jetpack Compose for `baresipkit-compose`
- The libraries are made for Kotlin callers. Their API uses value classes and suspend functions,
  which Java sees under generated names and without coroutines, so calling them from Java is not
  supported in 1.0
- To build from source: JDK 21, the Android SDK and NDK, see [Building from source](#building-from-source)

## Installation

Releases will be published to Maven Central under `io.github.mariokovacevic`; `baresipkit`
carries the native core for `arm64-v8a` and `x86_64`. There is no release yet. Until the first
one, build the core and publish the libraries to your local Maven repository:

```bash
git clone https://github.com/mariokovacevic/baresipkit.git
cd baresipkit
PLATFORM=android MODULE_SET=full ANDROID_NDK_HOME="$ANDROID_HOME/ndk/30.0.16248370" Build/build-xcframework.sh
cd Android && ./gradlew publishToMavenLocal
```

Then add the libraries you need, with the version in `Android/gradle.properties`:

```kotlin
repositories {
    mavenLocal()
}
dependencies {
    implementation("io.github.mariokovacevic:baresipkit:0.1.0-SNAPSHOT")
    implementation("io.github.mariokovacevic:baresipkit-telecom:0.1.0-SNAPSHOT")
    implementation("io.github.mariokovacevic:baresipkit-compose:0.1.0-SNAPSHOT")
}
```

## Quick start

```kotlin
val engine = BaresipEngine(BaresipConfiguration(instanceUuid = storedInstanceUuid) { userAgent = "MyApp" })
engine.setLogHandler(BaresipLogcat.handler())
engine.start()
val account = engine.addAccount(BaresipAccountConfiguration(aor = "sip:alice@example.com") { authPassword = password })
engine.register(account)
engine.events.collect { event ->
    when (event) {
        is BaresipEvent.IncomingCall -> engine.answer(event.info.id)
        is BaresipEvent.Call -> println("${event.call}: ${event.state}")
        else -> Unit
    }
}
```

Every operation is a `suspend` function that runs on the engine's own thread; what happens
afterwards arrives through `events`, a `Flow` that completes when the engine is closed. Only one
engine runs per process; close it with `close()`.

`storedInstanceUuid` is the `+sip.instance` identifier of the installation: create it once with
`UUID.randomUUID()`, persist it (for example in `SharedPreferences`) and pass the stored value on
every launch, so the registrar replaces the device's `Contact` binding instead of adding one.

The configuration types are built with a block and changed with `copy`:
`BaresipAccountConfiguration(aor = "sip:alice@example.com") { transport = BaresipTransport.TLS }`,
`account.copy { nat { mode = BaresipNatTraversal.ICE } }`.

## Setup recipes

**Telecom.** Create one `BaresipTelecomAdapter` and use it as the call controller, so answer,
end, hold and mute go through the system. The app shows incoming calls itself, as self-managed
calls require: post a `CallStyle` notification with a full-screen intent from `onIncomingCall`.
[Incoming calls on Android 14 and later](#incoming-calls-on-android-14-and-later) has the rules
for the full-screen intent and the call's foreground service.

```kotlin
val telecom = BaresipTelecomAdapter(context, engine) { call -> showIncomingCall(call) }
val phone = BaresipPhone(engine, telecom, viewModelScope)
viewModelScope.launch { phone.run() }
val call = phone.makeCall("sip:door@example.com", video = true)
```

**Push.** Forward your `FirebaseMessagingService` to `BaresipPushAdapter`; it keeps the
account's `Contact` parameters (`pn-type=firebase`, `pn-tok`, `app-id`) current and reports a
pushed call to Telecom before waking the engine. A server matches the INVITE to the push by the
UUID in an INVITE header (`BaresipTelecomConfiguration.callUuidHeader`) or by the caller. The
door stack in `Tools/DoorStack` sends such pushes, to Google or to a local stand-in. A call the
server replaces with an INVITE carrying `Replaces`, or one the other side transfers with REFER,
keeps its Telecom call: the new call's `BaresipCallInfo.replacesCallId` names the old one, which
ends with `BaresipCallEndReason.REPLACED`, and the adapter moves the Telecom call over.

```kotlin
val push = BaresipPushAdapter(engine, appId = context.packageName) { call ->
    telecom.reportIncomingCallFromPush(call.uuid, call.remoteUri, call.displayName, call.hasVideo)
}
push.bind(account)
```

**Calls asked for outside the app.** Add an intent filter for `VIEW` and `DIAL` with the `sip`
and `sips` schemes (and `tel` if the app should be offered for phone numbers), and receive
`CALL` on an `activity-alias` that requires `android.permission.CALL_PHONE`, so only apps
allowed to place calls, like the Phone and contacts apps, can start one. Read the request with
`BaresipRedialRequest.from(intent)` in `onCreate` (only when `savedInstanceState` is `null`
and the activity was not launched from history, so a recreated activity does not call again)
and in `onNewIntent`. Call `request.destination` at once only when `request.callsNow` is set
and the intent reached the alias; otherwise show the destination and let the user start the
call.

**Audio without Telecom.** `BaresipAudioController(context, engine).activate()` requests audio
focus for voice communication, sets `MODE_IN_COMMUNICATION` and tells the engine;
`selectOutput` picks the earpiece, the speaker or a headset.

**Video.** Call `BaresipCamera.install(context)` once, and ask for the `CAMERA` permission, which
`baresipkit-video` declares, before the first video call. Then show a call's video with
`BaresipCallVideo(engine, call)` in Compose or a `BaresipVideoView` attached with
`engine.setVideoRenderer(call, view.renderer)`. H.264 is coded by MediaCodec and VP8 by libvpx.

**Network and lifecycle.** `BaresipNetworkMonitor(context, engine).start()` tells the engine
when the default network changes, so registrations and calls move to it.
`BaresipAppLifecycle(engine).start()` suspends the engine or takes it offline in the background
when no call runs.

## TLS and certificates

The engine configuration holds the TLS settings shared by every account: extra trusted CAs, a
client certificate for mutual TLS, pinned server keys, the lowest TLS version and the cipher
list. An account uses TLS with `transport = BaresipTransport.TLS`, the default.

```kotlin
val configuration =
    BaresipConfiguration(instanceUuid = storedInstanceUuid) {
        tls {
            caCertificatesPem = BaresipCertificates.caBundle(caFileBytes)
            clientCertificatePem = BaresipCertificates.clientIdentity(clientPemBytes)
            pinnedSpkiSha256 = listOf("base64-encoded-sha256-of-the-server-public-key=")
        }
    }
val account =
    engine.addAccount(
        BaresipAccountConfiguration(aor = "sip:alice@example.com") {
            registrar = "sip:pbx.example.com:5061;transport=tls"
            authPassword = password
        },
    )
```

- **CA.** `BaresipCertificates.caBundle` takes PEM (one or more certificates) or a single DER
  certificate and returns the PEM the configuration takes; it throws
  `BaresipCertificateException` for a file that holds no valid certificate. The server
  certificate is always verified, and there is no setting that turns this off: for a test server
  with a self-signed certificate, add that certificate or its CA here.
- **The system's CAs.** When the certificates in `caCertificatesPem` do not verify the server,
  the core verifies it with OpenSSL against Android's system CAs, read from
  `/apex/com.android.conscrypt/cacerts` (the store Android 14 and later update) or, when that
  does not exist, `/system/etc/security/cacerts`. CAs the user installed or turned off in the
  settings, and the app's network security configuration, do not apply. A server whose
  certificate a public CA issued therefore needs no CA here (verified with CA directories in
  this layout on Linux, not yet against a public server on a device).
- **Pins.** `pinnedSpkiSha256` lists the Base64 SHA-256 hashes of the server keys
  (SubjectPublicKeyInfo) you accept, checked on top of the normal verification; a certificate of
  the chain must match one of them. Compute a pin from the server's certificate with
  `openssl x509 -in server.pem -pubkey -noout | openssl pkey -pubin -outform der | openssl dgst -sha256 -binary | base64`.
  List the next key too before you rotate it.
- **Client certificate.** `BaresipCertificates.clientIdentity` takes the certificate (and its
  chain) followed by its unencrypted private key, in PEM, and checks them. Encrypted keys are not
  supported. `toString` of the configuration never shows the key; keep the file itself in the
  app's private storage.
- **Versions.** `minimumVersion` is `TlsVersion.TLS_1_2` by default; set `TLS_1_3` to refuse
  TLS 1.2.

A failed handshake or verification arrives as a `BaresipEvent.Registration` whose error has the
code `BaresipError.Code.TLS`, and the log names the reason; see
[Troubleshooting.md](Troubleshooting.md).

## NAT traversal: ICE, STUN and TURN

NAT traversal is set per account. With `BaresipNatTraversal.ICE` the engine gathers host, STUN
and TURN candidates and picks the pair that works:

```kotlin
val account =
    BaresipAccountConfiguration(aor = "sip:alice@example.com") {
        authPassword = password
        nat {
            mode = BaresipNatTraversal.ICE
            stunUri = "stun:stun.example.com:3478"
            turnServers =
                listOf(
                    BaresipTurnServer("turns:turn.example.com:5349") {
                        username = "alice"
                        password = turnPassword
                    },
                )
        }
    }
```

- The TURN URI selects the transport: `turn:host:3478` for UDP,
  `turn:host:3478?transport=tcp` for TCP and `turns:host:5349` for TLS. One TURN server is
  supported per account.
- `BaresipNatTraversal.STUN` only learns the public address from the STUN server, without ICE.
  Without ICE, set `pinhole = true` when a media relay forwards the other side's media only
  after it has seen a packet from you.
- `BaresipConfiguration.iceRelayOnly = true` sends all media through TURN, for example to test a
  relay.
- `BaresipEvent.Ice` reports a call's ICE state and the candidate pair in use;
  `callStatistics(call)` has the same.
- `BaresipNetworkMonitor` tells the engine when the network changes, and the engine restarts
  ICE for running calls. `restartIce(call)` restarts it by hand, for example after the TURN
  connection was lost. The known limits of TURN over TCP and TLS are in
  [Limitations.md](Limitations.md).

## Incoming calls on Android 14 and later

Calls through Telecom and pushes need the app to show the incoming call itself and to keep
running while the call lasts. Android 14 (API 34) tightened both:

- **Full-screen intent.** Declare `USE_FULL_SCREEN_INTENT` and post the incoming call as a
  `CallStyle` notification with a full-screen intent from `onIncomingCall`, so the ring screen
  shows over the lock screen. Since Android 14 the permission is granted by default only to apps
  whose main purpose is calling or alarms, and the user can withdraw it in the settings. Check
  `NotificationManager.canUseFullScreenIntent()`; when it is `false`, the notification shows as
  a heads-up notification only, and you can send the user to
  `Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT` for your package. Ask for
  `POST_NOTIFICATIONS` first (Android 13 and later), or nothing shows.
- **Foreground service types.** On Android 14 every foreground service declares
  `android:foregroundServiceType` in the manifest and the matching `FOREGROUND_SERVICE_*`
  permission, and `startForeground` may only use declared types. A call service declares
  `phoneCall` (`FOREGROUND_SERVICE_PHONE_CALL`, which also needs `MANAGE_OWN_CALLS`, merged with
  `baresipkit-telecom`), and `microphone` and `camera` (`FOREGROUND_SERVICE_MICROPHONE`,
  `FOREGROUND_SERVICE_CAMERA`) for the audio and video of the call.
- **Microphone and camera from the background.** The `microphone` and `camera` types need the
  `RECORD_AUDIO` and `CAMERA` permissions granted, and a service started while the app is in the
  background does not get them. While a pushed call rings, start the service with `phoneCall`
  only; when the user answers from the ring screen or the notification, call `startForeground`
  again with `microphone` (and `camera` for video) added.
- **Pushes.** Send calls as high-priority data messages and forward them to
  `BaresipPushAdapter` from `onMessageReceived`, which reports the call to Telecom before it
  wakes the engine. The system lets the app start a foreground service while it handles such a
  message, but lowers the priority of an app whose high-priority messages do not lead to a
  notification.

## App checklist

Before shipping a VoIP app built on BaresipKit for Android:

| Where | What |
|---|---|
| Manifest | `RECORD_AUDIO`; `BLUETOOTH_CONNECT` for Bluetooth headsets unless `baresipkit-telecom` merges it. The libraries declare the rest, see [What the libraries add to the manifest](#what-the-libraries-add-to-the-manifest) |
| Manifest | For Telecom: `MANAGE_OWN_CALLS`, and a foreground service of type `phoneCall` (with `microphone` and `camera` for video calls) with the matching `FOREGROUND_SERVICE_*` permissions |
| Manifest | For incoming calls: `POST_NOTIFICATIONS` and `USE_FULL_SCREEN_INTENT`, so the ring screen shows over the lock screen |
| Runtime | Ask for the microphone, camera and notification permissions before the first call |
| Firebase | A Firebase project, its `google-services.json`, and a `FirebaseMessagingService` that forwards tokens and messages to `BaresipPushAdapter`; send calls as high-priority data messages |
| SIP server | Reads the push parameters from the `Contact` and sends a push for a call to a registered device |
| Calls from other apps | The `sip:` and `tel:` intent filters and the `CALL_PHONE` alias from the recipe above |
| Licenses | Ship the third-party notices, including OpenSSL's NOTICE (see [License](../README.md#license)) |

## What the libraries add to the manifest

Each library declares the permissions it needs itself, so they appear in the app's merged
manifest without being listed again:

| Library | Merged into the app's manifest |
|---|---|
| `baresipkit` | `INTERNET`; `ACCESS_NETWORK_STATE` for `BaresipNetworkMonitor` |
| `baresipkit-audio` | `MODIFY_AUDIO_SETTINGS`, for communication mode, the speaker and Bluetooth SCO |
| `baresipkit-video` | `CAMERA`; the features `android.hardware.camera.any`, `android.hardware.camera` and `android.hardware.camera.autofocus` and OpenGL ES 2.0, all with `required="false"`, so devices without a camera can still install the app |
| `baresipkit-telecom` | From core-telecom: `MANAGE_OWN_CALLS`, `BLUETOOTH_CONNECT`, `MODIFY_AUDIO_SETTINGS`, and its `ConnectionService` and mute receiver |
| `baresipkit-push` | Nothing of its own; it does not depend on a push messaging library. The one the app uses adds what it needs |
| `baresipkit-compose` | Nothing |

`RECORD_AUDIO`, `POST_NOTIFICATIONS`, `USE_FULL_SCREEN_INTENT` and the foreground service
permissions stay with the app, which decides when to ask for them. An app that uses
`baresipkit-video` only to show the other side's video, and never sends the camera, can remove
the camera permission:

```xml
<uses-permission android:name="android.permission.CAMERA" tools:node="remove" />
```

## Sample app

[Android/zvono](../Android/zvono/README.md) is Zvono, the intercom sample, in Compose: doors with their last
picture and Open, activity, keypad, messages, settings and diagnostics, the ring screen with a
preview before answering, and the call screen with captions and chat for SIP messages. It uses
every module above. CI runs its screenshot test on emulators with API 28 and 34 and keeps the
pictures as the `android-sample-screenshots-api28` and `android-sample-screenshots-api34`
artifacts.

## Building from source

Requirements: JDK 21, the Android SDK with platform 36, NDK `30.0.16248370` and CMake 3.22.1,
and `cmake` and `nasm` on the build machine.

Build the C core for both Android ABIs first, from the repository root:

```bash
PLATFORM=android MODULE_SET=full ANDROID_NDK_HOME="$ANDROID_HOME/ndk/30.0.16248370" Build/build-xcframework.sh
```

Then build the libraries:

```bash
cd Android && ./gradlew assembleDebug
```

The API documentation is generated with Dokka:

```bash
cd Android && ./gradlew :dokkaGeneratePublicationHtml
```

## Tests

The JVM unit tests load the JNI bridge built against the core for the Linux host. Build that core
once, then run the tests:

```bash
PLATFORM=host MODULE_SET=full Build/build-xcframework.sh
```

```bash
cd Android && ./gradlew testDebugUnitTest
```

On other hosts the tests that need the core are skipped. The call tests talk to a small scripted
SIP peer over UDP on the machine's own address. The instrumented tests run on an emulator in CI;
one of them sends a call's H.264 video through a peer that echoes it, so MediaCodec encodes and
decodes it.

## Releasing

The `Release Android` workflow publishes the artifacts to Maven Central from the default branch
once CI passed for the commit. It needs the secrets `MAVEN_CENTRAL_USERNAME` and
`MAVEN_CENTRAL_PASSWORD` (a Central Portal token) and `SIGNING_KEY` and `SIGNING_KEY_PASSWORD`
(an ASCII-armored GPG key).

## Documentation

| Where | What |
|---|---|
| Dokka (`./gradlew :dokkaGeneratePublicationHtml`) | The API reference of every library |
| [Platforms.md](Platforms.md) | How the platforms share the core, the decisions behind the Android SDK, and parity with iOS type by type and test by test |
| [Architecture.md](Architecture.md#android) | The layers on Android, from the C core to the adapters |
| [C-ABI.md](C-ABI.md) | The C ABI the JNI bridge binds |
| [Troubleshooting.md](Troubleshooting.md) | Reading the log and the usual registration, media and ICE failures; the examples use the Swift names, which Kotlin shares |
| [Interop.md](Interop.md), [Limitations.md](Limitations.md) | Servers and devices BaresipKit has been tried with, and the known limits |
