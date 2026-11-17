# BaresipKit v1.0.0 release checklist

Status of the release candidate `1.0.0-rc.1`. Every item is checked, marked **not verified**, or
marked **not applicable**, with the evidence or the reason. Nothing here has been tagged,
published or announced.

Legend:
- `[x]` done, with the evidence named.
- `[ ]` open: not verified or not done yet, with the reason and who can close it.
- `n/a` not applicable, with the reason.

## 1. Owner decisions

- [x] D1: withdraw the published `1.0.0` GitHub release and its tag, and release this tree as
  `1.0.0`. Decided; to be done right before the release, after explicit confirmation (step 6).
- [x] D2: the C ABI is internal and shipped in lockstep with both SDKs; not covered by SemVer
  (`Docs/STABILITY.md`, `Docs/C-ABI.md`).
- [x] D3: enums and sealed types may gain cases in minor releases; clients use `default` /
  `else` (`Docs/STABILITY.md`).
- [x] D4: Kotlin configuration types are built with builders; output types' constructors are not
  covered by SemVer.
- [x] D5: release from a fresh clone (step 6).
- [x] D6: FCM against Google's servers, WSS and real-device behaviour ship as known limitations.

## 2. Code and review

- [x] All 16 Blockers of `V1_REVIEW.md` are fixed or decided; see `V1_REPORT.md`.
- [x] Approved Should-fix items are fixed or explicitly deferred with a reason; see
  `V1_REPORT.md`.
- [x] Second code review of the release-candidate branch against `main` done. Of its 10 candidate
  findings, 5 were confirmed and fixed (log handler swap, audio activation order, JNI method
  cache, Swift destroy on the engine thread, CA store reloads); 5 were declined with reasons in
  `V1_REPORT.md`.
- [x] Every behaviour change is listed in `CHANGELOG.md`.
- [x] Branch merged to `main` through a pull request: mariokovacevic/baresipkit#1, squash
  merged on 2026-11-15 as `305e010`, the second commit of `main`.

## 3. CI on `main`

The run numbers below are for the release-candidate branch. They have to be repeated on `main`
after the merge.

- [x] CI green on `main` for iOS, Android and core: run 213 on `305e010` passed all ten jobs.
- [x] CI green on the branch: run 206 on `d72d29d`, all ten jobs (section 9).
- [x] Lint and format (SwiftFormat, SwiftLint, clang-format, ktlint) run in CI with warnings as
  errors.
- [x] Swift builds for the simulator, a device and macOS with `SWIFT_TREAT_WARNINGS_AS_ERRORS`;
  DocC with warnings as errors.
- [x] C core built with `-Wall -Wextra -Wshadow -Werror`; tests under ASan/UBSan and TSan on
  Linux, and on macOS.

## 4. API baselines

- [x] Swift: `API/Swift/*.json` committed; CI runs `Scripts/swift-api.sh check` on every push.
- [x] Kotlin: `Android/<library>/api/<library>.api` committed; CI runs `checkKotlinAbi`.
- [x] Explicit API mode on all six Kotlin libraries.
- [x] `Docs/STABILITY.md` says what is covered and how compatibility is checked.

## 5. Tests

- [x] C unit, registration, call, video, transfer, security and trust tests, local and in CI.
- [x] Fuzzing: libFuzzer over the facade parsers (Contact parameters, log redaction including
  whole SIP messages from the seed corpus, ICE event parsing, the H.264 depacketizer, the Reason
  header), 300 000 runs in CI with a seed corpus and inputs up to 4 KiB.
- [x] Integration in Docker against Asterisk 23.5.0 and coturn 4.6.3 (`Tests/Integration`):
  registration over TLS with a private CA, a wrong password then a successful registration, an
  audio call with RTP statistics, early media, DTMF over RFC 4733 and SIP INFO, hold and resume,
  a second account and call with swapping, SDES-SRTP over TLS, ICE with a TURN relay, relay-only
  calls with TURN over UDP, TCP and TLS, and TURN failures that must end within 20 seconds.
- [x] Against the host peer (`Tests/Core/run_tests.sh`): calls with PCMU and G.722, video with VP8, and
  H.264 through VideoToolbox in the macOS job (MediaCodec H.264 runs in the Android emulator
  tests), ICE restart, attended and blind transfer, SIP MESSAGE, session timers, the NAT pinhole, the TLS
  trust and pinning paths.
- [x] Swift XCTest suite on an iOS simulator and on macOS.
- [x] iOS sample app UI test on a simulator: TLS registration with a private CA, a wrong pin,
  a call to a host peer.
- [x] Android JVM tests against the host core; instrumented tests and the Android sample's UI and screenshot
  tests on emulators with API 28 and API 34.
- [ ] Interop beyond Asterisk and coturn. **Not done**: no FreeSWITCH, Kamailio or media relay
  in the test setup; the README and `Docs/CompatibilityMatrix.md` claim only what was tested.
- [ ] Video calls, a network change during a call, and the Swift and Kotlin layers against
  Asterisk. **Not done** in CI (S-37); covered by the host peer tests only.
- [ ] Lower bounds: iOS 16 on a simulator or device. **Not verified**: CI uses the newest
  simulator only. Android API 28 is covered.

Servers and devices used so far:

| Kind | What | Where |
|---|---|---|
| SIP server | Asterisk 23 (Docker, `Tests/Integration`) | CI |
| TURN server | coturn (Docker) | CI |
| SIP peer | baresip-based host peer and TLS registrar from `Tests/` | CI and local |
| iOS | newest iOS simulator on the CI runner; macOS on the CI runner | CI |
| iOS device | the owner's own device and app, push and call queue checked by the owner | owner, not recorded here |
| Android | emulators, API 28 and API 34 (x86_64) | CI |
| Android device | none | — |

## 6. Release mechanics

- [x] `VERSION` is the single source of the version for the core, the Swift package and Gradle.
- [x] `VERSION` set to `1.0.0-rc.1` and a dated `## [1.0.0-rc.1]` section in `CHANGELOG.md`, with
  its link definition.
- [ ] For the final release: `VERSION` set to `1.0.0`, the changelog section renamed and dated
  on the release day. Owner, at release time.
- [x] Both release workflows default to a dry run and refuse to run unless CI passed on the
  commit, `VERSION` matches and the changelog section exists.
- [x] The release workflow publishes the XCFramework that CI built and tested, with an SPDX SBOM,
  `SHA256SUMS` and build provenance attestations, and pushes only the tag.
- [x] Android: the libraries are staged on Maven Central from the tag, with the license texts in
  the AAR, an SBOM and `SHA256SUMS`.
- [ ] Dry run of both release workflows on the release-candidate commit. **Not done**: needs
  `workflow_dispatch`, which is the owner's call.
- [ ] Repository settings: the local clone's remote still points to the old name `baresip-swift`
  (GitHub redirects). Release from a fresh clone of `https://github.com/mariokovacevic/baresipkit`
  (D5). Owner.
- [ ] Labels `ios`, `android` and `core` exist on GitHub, as the issue templates use them.
  **Not verified** from here. Owner.
- [ ] Private vulnerability reporting is enabled on the repository, as `SECURITY.md` relies on it.
  **Not verified** from here. Owner.
- [x] Withdraw the old `1.0.0` GitHub release and tag (D1): done by the owner on 2026-11-15; the
  repository has no releases and no tags.
- [ ] Maven Central namespace `io.github.mariokovacevic` verified, and the signing key and
  credentials stored as repository secrets. **Not verified** from here. Owner.

## 7. Binary sizes

See section 9 for the figures from the release-candidate CI run.

- [x] Host core static library (full module set, Linux, debug with sanitizers): 48 705 808 bytes.
  Not representative of what ships.
- [x] XCFramework artifact size recorded (section 9).
- [ ] AAR sizes. **Not recorded**: CI does not upload the AARs; the release dry run does.
- [ ] Engine cold start, registration time, push-to-incoming-call time, call setup time, CPU and
  battery. **Not measured**: needs a device (S-46).

## 8. Security, licenses and docs

- [x] Security fixes of the review applied: pins checked against the verified chain, no
  accept-all TLS in any build, TLS 1.2 minimum, private logging by default, SDES over
  unencrypted signaling warned about, Android system CAs.
- [x] Dependency vulnerability check on 2026-11-14 through OSV.dev: libre, baresip, Opus and
  libvpx at their pinned commits, and the Kotlin and AndroidX dependencies, have no known
  vulnerabilities. OpenSSL 3.5.9 has four (CVE-2026-88822, CVE-2026-88823, CVE-2026-95691,
  CVE-2026-95692): QUIC is not built, which removes two, and the other two are in AES-GCM-SIV,
  which neither TLS nor SRTP uses. A clean OSV result does not prove there are none.
- [ ] Repeat the vulnerability check on the release day, and move to OpenSSL 3.5.10 once it is
  released. Owner.
- [x] License review: `LICENSES/` checked against the pinned sources in CI;
  `THIRD_PARTY_NOTICES.md` and `DEPENDENCIES.md` updated; license texts in the `baresipkit` AAR;
  the POM lists every license.
- [x] SBOM: `Scripts/sbom.py` produces SPDX 2.3 for both releases; validated locally with
  `pyspdxtools`.
- [x] Privacy manifest checked in CI against the required-reason APIs the XCFramework uses.
- [x] Docs: DocC builds with warnings as errors; README, the platform guides, `STABILITY.md`,
  `Releasing.md`, `Building.md`, `Limitations.md` and `Platforms.md` updated.
- [ ] Docs reviewed by a person other than the author. **Not done**. Owner.
- [x] Neutral wording: no third-party product, company or customer is named in the tree.
- [ ] A security contact email in `SECURITY.md`, as a fallback to GitHub's private reporting.
  Open question to the owner; a no-address fallback is used for now.

## 9. Release-candidate CI run

CI run 206 on `d72d29d`, the release-candidate commit plus two fixes (ktlint formatting and a
stale DocC link) that run 205 on `07a65bd` had found. All ten jobs passed:

| Job | Result |
|---|---|
| Lint and format check | passed |
| Linux host build, ASan/UBSan, core tests, license check, 300 000 fuzz runs | passed |
| Linux host build, ThreadSanitizer, core tests | passed |
| macOS host build and core tests (with H.264 through VideoToolbox) | passed |
| Integration against Asterisk and coturn (Docker) | passed |
| macOS XCFramework, privacy manifest check, Swift package for simulator, device and macOS with warnings as errors, Swift API check, DocC with warnings as errors, XCTest on a simulator | passed |
| Sample app build, UI test and screenshots | passed |
| Android core, libraries, ABI check, JVM tests, ktlint | passed |
| Android instrumented tests on an emulator, API 28 | passed |
| Android instrumented tests on an emulator, API 34 | passed |

Sizes from that run:

- `BaresipKitCore.xcframework` CI artifact (zip of the default module set, all slices):
  13 956 495 bytes.
- AAR sizes: **not recorded**; the CI run does not upload the AARs. The release dry run
  (`release-android.yml`) uploads them and records `SHA256SUMS`.

## 10. Real devices and live tests

- [ ] iOS sample app on a real device: registration over TLS, incoming call by PushKit,
  CallKit and LiveCommunicationKit, video both ways, a network change during a call. Owner.
- [ ] Several calls from pushes in a row, and two calls at once, on a real device, including a
  server that sends a different Call-ID in the push than in the INVITE. Owner.
- [ ] The Android sample app on a real device: registration over TLS to a server with a public-CA
  certificate (the Android system CA fix), Telecom incoming and outgoing calls, FCM wake-up,
  Bluetooth headset, video both ways. Owner.
- [ ] PushKit placeholder call with the app's own CallKit adapter when no call follows (S-13).
  Owner.
- [ ] Release notes reviewed: `Docs/release/RELEASE_NOTES_v1.0.0.md`. Owner.

## 11. After approval only

Each step needs the owner's explicit go. Steps 1 and 3 are done.

1. ~~Merge to `main` and see CI green there.~~ Done (#1, `305e010`, CI run 213).
2. Fresh clone; dry runs of both release workflows.
3. ~~Withdraw the old `1.0.0` release and tag.~~ Done.
4. Tag `v1.0.0-rc.1`, or go straight to `v1.0.0` after the device tests.
5. Publish the XCFramework release and stage, then release, the Maven Central deployment.
6. Announce.
