# Building

BaresipKit builds in two steps: the C core, then the SDK of a platform on top of it.

| Step | Guide | Tests |
|---|---|---|
| The C core, for every platform | [Core/Building.md](Core/Building.md) | the core tests, fuzzing and the integration suite |
| The Swift package for iOS and macOS | [Apple/Building.md](Apple/Building.md) | the Swift tests on a simulator and macOS, the sample app's UI test |
| The Android libraries | [Android/Building.md](Android/Building.md) | the JVM tests against the host core, the instrumented tests on an emulator |

Both SDKs check their public API against a committed baseline; the guides above say how to
update it. Cutting a release is in [Releasing.md](Releasing.md).

## Repository layout

| Path | Content |
|---|---|
| `Core/` | the C facade (`include/`, `src/`), the pinned build (`Build/`), the patches to baresip and libre (`Patches/`) and the core tests (`Tests/`) |
| `Apple/` | the Swift package's sources (`Sources/`), tests (`Tests/`), API baseline (`API/`), scripts and SwiftFormat and SwiftLint settings; `Package.swift` stays at the root, where SwiftPM looks for it |
| `Android/` | the Gradle project with the Kotlin libraries, their API baselines and the JNI bridge |
| `Examples/` | the sample app for each platform, `iOS/` and `Android/` |
| `Docs/` | shared documents, and `Core/`, `Apple/` and `Android/` with the same set of guides per platform |
| `Scripts/`, `Tools/` | formatting, linting, licenses, the SBOM, the local PBX and test helpers used by both platforms |

## CI

`.github/workflows/ci.yml` runs on every push and pull request. Its jobs run in parallel:

| Job | Runs |
|---|---|
| `lint` | clang-format, SwiftFormat and SwiftLint |
| `linux-core` | the host build under ASan/UBSan, the core test suite and the fuzzers |
| `linux-core-tsan` | the host build and the core test suite under ThreadSanitizer |
| `integration` | the integration suite against Asterisk and coturn in Docker |
| `macos-core` | the host build and the core test suite on macOS |
| `macos` | the XCFramework, the Swift package for iOS, the simulator and macOS, its tests on macOS and a simulator, and the documentation |
| `sample-app` | the iOS sample app for the simulator and a device, its UI test and its screenshots |
| `android` | the core for both Android ABIs, the Kotlin libraries, ktlint, the JVM tests against the host core, and the Android sample app |
| `android-emulator` | the instrumented tests and the Android sample's UI tests and screenshots on an Android emulator, against a TLS registrar, a SIP peer and coturn on the runner |

The pinned sources and built prefixes are cached under a key made from `Core/Build/versions.env`, the module sets, the build script, `Core/Build/cmake` and `Core/Patches`, so the C core is rebuilt only when one of them changes. `sample-app` builds every prefix the macOS jobs use and saves that cache; `macos` and `macos-core` only restore it.
