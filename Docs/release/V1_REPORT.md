# BaresipKit v1.0.0 report

What changed between the reviewed commit `76037dc` and the release candidate, what was deferred
to 1.x, the known limitations and the residual risks. The findings are those of
[V1_REVIEW.md](V1_REVIEW.md); the release status is in
[RELEASE_CHECKLIST.md](RELEASE_CHECKLIST.md). Every behaviour change is listed in
`CHANGELOG.md`.

## Summary

- All 16 Blockers are fixed, or decided by the owner and documented.
- Of the 46 Should-fix items, 32 are fixed, 10 are partly fixed and 4 are deferred; the open parts
  are listed below with their reasons.
- A second code review of the whole branch found 10 candidates: 5 were confirmed and fixed, and
  5 were declined with the reasons below.
- API baselines are committed for Swift and Kotlin, and CI fails on an unannounced change.
- Not verified: anything on a real Android device, CallKit, PushKit and LiveCommunicationKit
  beyond the owner's own device test, iOS 16, servers other than Asterisk and coturn, and
  performance.

## Owner decisions

| # | Decision |
|---|---|
| D1 | Withdraw the published `1.0.0` release and tag, right before the release and only with the owner's confirmation; release this tree as `1.0.0`. |
| D2 | The C ABI is internal, shipped in lockstep with the SDKs, not covered by SemVer. |
| D3 | Enums and sealed types may gain cases in minor releases. |
| D4 | Kotlin configuration types use builders; output types' constructors are not covered. |
| D5 | Release from a fresh clone. |
| D6 | FCM against Google's servers, WSS and real devices ship as known limitations. |

## Blockers

| ID | Status | Where |
|---|---|---|
| B-01 Published `1.0.0` conflicts | Decided (D1). The release workflow uses `v`-prefixed tags and refuses to run without green CI. Withdrawing the old release is an owner step in the checklist. | `9c5a173` |
| B-02 Local refs with a company name | Decided (D5). The refs exist only in this clone and were never pushed; the release is made from a fresh clone. | checklist |
| B-03 Other vendors named | Fixed. | `8923c3c` |
| B-04 Pin bypass on the system-trust path | Fixed, with a regression test. | `99a972a` |
| B-05 C output structs cannot grow | Decided (D2), documented. | `2bd6fc4` |
| B-06 No API baselines | Fixed: Swift digests and Kotlin ABI dumps committed and checked in CI. | `7853019`, `33b1477`, `c91148e` |
| B-07 No rule for growing enums | Fixed: `Docs/STABILITY.md`. | `2bd6fc4`, `45f71ae` |
| B-08 Kotlin data classes freeze constructors | Fixed: builders. | `755e776`, `f0a2948` |
| B-09 Kotlin API exposes `implementation` types | Fixed: `api` dependencies. | `9af3cd6` |
| B-10 Accept-all TLS in release builds | Fixed: removed from every build. | `725f4d7` |
| B-11 Camera sent after an audio-only answer | Fixed. | `8df2c00` |
| B-12 Telecom event loop can die | Fixed. | `8df2c00` |
| B-13 Camera stays on after the preview | Fixed. | `9caf5a8` |
| B-14 Versions and licensing of artifacts | Fixed: `VERSION`, license texts in the AAR, notices, `LICENSES/`. | `4311f08`, `c3e9a0c`, `cd895b6` |
| B-15 Distribution flow and evidence | Fixed: dry-run default, tested binary, SBOM, `SHA256SUMS`, provenance, Android staging from the tag. | `9c5a173`, `91de55f` |
| B-16 Plausible real phone numbers | Fixed. | `31196c7`, `3c65d90` |

## Should fix

Fixed:

| ID | What |
|---|---|
| S-02 | Plumbing made internal on both platforms. |
| S-03 | System delegate conformances moved to private forwarders. |
| S-08 | Both platforms document `BaresipCallController` as implemented by the SDK only. |
| S-09 | The Kotlin libraries can be used from Kotlin 2.1 projects. |
| S-10 | The Android guide says the libraries are for Kotlin callers. |
| S-11 | `instanceUUID` is required on both platforms. |
| S-12 | PushKit asynchronous handler has a deadline. |
| S-14 | System actions have a 60 s backstop and domain errors on reset. |
| S-17 | `BaresipAppLifecycle` releases its observers. |
| S-18 | Android engine handle guarded; cancelled calls and accounts cleaned up; `runCatching` narrowed. |
| S-19 | Telecom subscribes at once, no ghost call on a failed hang-up, the INVITE's caller is used. |
| S-20 | Ordered audio updates; SCO handled on API < 31; `release()` stops the scope. |
| S-21 | A lost camera is reported; rotation from `DisplayManager`. |
| S-22 | Camera buffers, MediaCodec sizes and strides checked; JNI readers stop on exceptions. |
| S-23 | Separate log lock, guards on the engine thread, atomics, TSan in CI; re-entrant event delivery documented. |
| S-25 | Encryption defaults documented; warning for SDES over unencrypted signaling. |
| S-26 | Private unified logging by default; the SDKs default to INFO. |
| S-27 | Kotlin `toString()` redacts passwords and tokens. |
| S-28 | The password-wiping claim corrected. |
| S-29 | Only TLS 1.2 and 1.3 accepted as the minimum. |
| S-30 | Manifest permissions and camera features declared; the dead R8 rule removed. |
| S-31 | Privacy manifest checked in CI against the APIs the XCFramework uses. |
| S-32 | Security reporting fallback; conduct reports separate. |
| S-33 | OSV check of all pinned sources and Android dependencies; OpenSSL built without QUIC. |
| S-34 | Reproducible builds: prefix maps, `SOURCE_DATE_EPOCH`, `ZERO_AR_DATE`, normalized zip. |
| S-38 | Interop claims narrowed to what is tested. |
| S-39 | Reason parser and whole SIP messages fuzzed, larger inputs, seed corpus. |
| S-40 | The module sets per platform documented. |
| S-41 | Explanatory comments removed from Swift and C sources. |
| S-42 | Stale statements corrected. |
| S-44 | Issue and pull request templates cover Android and the core. |
| S-45 | The release is made from a fresh clone of the new URL (checklist). |

Partly fixed:

| ID | Done | Open, and why |
|---|---|---|
| S-04 | `hangUp` with the call first on Android; hold and ringtone names aligned across the adapters. | `BaresipCallController` argument order differs between the platforms; changing it is another source break for little gain. |
| S-06 | The Kotlin `events` flow completes when the engine closes. | Swift `events` stays a computed property that adds a subscriber per access; documented. A method with a buffering policy is a 1.x addition. |
| S-15 | Audio session handler hook on the CallKit and LiveCommunicationKit adapters, so the controller sees the system's activation. | A stale interruption "began" with the reason `appWasSuspended` still pauses the engine; ignoring it needs the reason from the notification, which the controller does not read yet. Needs a device to verify. |
| S-16 | The camera is no longer mirrored at capture; iOS 16 view recovery and frame coalescing. | `rotationDegrees` is always 0 and documented so; landscape video needs the core to carry rotation. |
| S-24 | Error mapping, session timer floor, loss and MOS, codec capabilities; `suspend` and diagnostics documented as no-ops. The `sip_transports` finding was wrong; a test pins the transports. | DTMF events have no direction field; adding one is a C ABI and SDK change for 1.x. |
| S-35 | Gradle distribution pinned by checksum and the wrapper validated. | Xcode and the brew and apt tools are not pinned; `macos-latest` moves. |
| S-36 | Android emulator tests on API 28 and 34. | iOS 16 is not tested; the newest simulator only. |
| S-37 | Failed registration and SDES-SRTP against Asterisk. | Video, a network change during a call, and the Swift and Kotlin layers against Asterisk are not in CI. |
| S-43 | `STABILITY.md`, DocC articles on TLS and NAT, Android guides on TLS, NAT and Android 14, KDoc on every public declaration, install snippets. | Dokka `reportUndocumented` could not be added: the Dokka plugins do not resolve offline here. |
| S-46 | Host library size recorded; release artifact sizes in the checklist. | Start-up, registration, push-to-call and call setup times, CPU and battery need a device. |

Deferred to 1.x:

| ID | Why |
|---|---|
| S-01 | Moving `endReason` out of `BaresipError` changes every call-event consumer; documented instead. |
| S-05 | Stringly-typed module and device names stay as advanced settings; typed wrappers can be added without breaking. |
| S-07 | `BaresipPushTokens` keeps its parameter format; an RFC 8599 encoding can be added as an option. |
| S-13 | The PushKit placeholder's own `CXProvider` needs a device to change safely. |

## Second code review

Confirmed and fixed:

- **Log handler swap could block for a long time.** The wait counted lines already delivered to
  the new handler. It now waits only for the old handler; a regression test timed out on the old
  code and passes in 0.4 s (`b21f08b`).
- **Android audio activation raced focus updates.** A queued resume could turn audio back on
  after `deactivate()`. All engine updates now go through one ordered queue (`1be37d8`).
- **JNI video method IDs were published unsynchronized.** They are now looked up in
  `JNI_OnLoad` (`383ce38`).
- **Swift `deinit` on the engine thread leaked a running engine.** The destroy is moved to
  another thread (`9efa563`).
- **The Android system CA directory was re-read for every handshake.** The store is now cached
  (`ca6b73c`).

Declined:

- **The camera lock is held while a frame is encoded.** The lock is what keeps a source alive
  during delivery; the engine thread waits on it only while a source starts or stops, not per
  frame.
- **`err |=` on errno values in the MediaCodec encoder.** `mbuf_write_mem` can only fail with
  `ENOMEM` there, and this is the libre idiom.
- **A remaining `runCatching` in the Telecom adapter.** Cancellation still takes effect at the
  next suspension point, and the adapter treats engine failures from system callbacks as
  non-fatal by design.
- **Per-frame AVCC conversion in the MediaCodec decoder** and **per-pixel camera rotation.**
  Performance only; both are in the 1.x backlog (L-03, L-05), with libyuv as the likely fix for
  the rotation.

## Fixes found during the work

- **Android TLS did not trust public CAs.** OpenSSL looked under `/etc/ssl`, which Android does
  not have, so a server with a public-CA certificate needed its CA configured. The core now
  reads the system CA directory (`5ae7ad3`). Tested on Linux with directories in the Android
  layout; not yet against a public server on a device.

## Behaviour changes

All are in `CHANGELOG.md`. The ones most likely to affect an app:

- Server verification cannot be turned off.
- `instanceUUID` must be supplied and persisted.
- TLS minimum below 1.2 and session timer minimums below 90 s are rejected.
- Unified logging is private by default on iOS.
- The front camera is no longer mirrored at capture.
- Android merges `INTERNET`, `ACCESS_NETWORK_STATE` and `MODIFY_AUDIO_SETTINGS` into the app's
  manifest.
- Android trusts the system CAs for SIP over TLS.

## Deferred to 1.x

Besides the deferred Should-fix items above, the 1.x backlog of `V1_REVIEW.md` (L-01 to L-08)
stands. The main items:

- Push-to-INVITE matching and call bookkeeping are implemented twice, in Swift and Kotlin (L-01).
- 27 patches to baresip and libre are not upstream yet (L-02).
- C and SDK leftovers: SecTrust on the engine thread, per-frame copies, unvalidated
  `extra_config`, hard-coded English in the SDK UI (L-03 to L-05).
- CI hardening: CodeQL, clang-tidy, Dependabot, Android Lint (L-06).
- OpenSSL 3.5.10 when released.
- A minified build that exercises the consumer R8 rules.

## Known limitations

As in the release notes and [Docs/Limitations.md](../Limitations.md):

- Nothing has run on a real Android device.
- FCM has not been tried against Google's servers, and WSS is untested.
- CI tests iOS on the newest simulator only.
- Interop has been tested against Asterisk 23 and coturn only.
- On Android, user-installed CAs and the network security configuration do not apply to SIP
  over TLS.
- macOS support is partial: no echo cancellation, and no system call integration.
- Performance has not been measured.

## Residual risks

- **Real devices.** Push handling, CallKit and Telecom timing, audio routing and Bluetooth,
  camera rotation and the Android CA fix are verified only on simulators and emulators, plus the
  owner's own iOS device test. This is the largest risk, and the reason for the device steps in
  the checklist before `1.0.0`.
- **Patched upstream.** The SDK carries 27 patches to baresip and libre; each upstream update
  needs them re-applied and re-tested.
- **OpenSSL.** Two published OpenSSL vulnerabilities are in code that is built but not used by
  TLS or SRTP. The risk is low but not zero until 3.5.10.
- **Concurrency.** The C core runs under TSan in CI, but the Swift and Kotlin layers have no
  race detector; the second review found three ordering and lifetime bugs in them.
- **API freeze.** The baselines freeze what exists. The partly fixed API items (S-04, S-06) are
  now part of 1.x compatibility.
- **Untested Swift fix.** The destroy-off-the-engine-thread fix has no test, because the timing
  cannot be forced from the public API.

## How this was verified

- **Local, Linux:** the full core suite under ASan/UBSan, including the new trust and log tests;
  fuzzing; the Android libraries compiled with their ABI checked; the JNI and TLS sources
  compiled with the NDK for both ABIs.
- **CI:** everything else, including all Swift builds and tests, DocC, the Swift API check,
  ktlint, the Android emulators, Asterisk integration, TSan and the macOS core. The result for
  the release-candidate commit is in the checklist.
- **Not available here:** a Swift toolchain, Docker, real devices. Nothing in this report claims
  a result from them that CI or the owner did not produce.
