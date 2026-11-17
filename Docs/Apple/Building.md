# Building the Swift package

How to build and test the Swift package from source. The C core comes first:
[Core/Building.md](../Core/Building.md).

## Requirements

macOS with Xcode 16 or newer (verified with Xcode 27.2), CMake 3.18+ and Python 3. For formatting
and linting also `swiftformat`, `swiftlint` and `xcodegen` (`brew install`).

## The core

```bash
Core/Build/build-xcframework.sh
```

Builds `Core/Build/out/BaresipKitCore.xcframework` with separate iOS device arm64, iOS simulator
arm64 and macOS arm64 slices, `BaresipKitCore.xcframework.zip` and `Core/Build/out/checksum.txt`
for the SPM binary target. The variables are in [Core/Building.md](../Core/Building.md#the-build-script).

## Using the package

`Package.swift` stays at the repository root, where SwiftPM looks for it, and takes its sources
from `Apple/Sources` and its tests from `Apple/Tests`. It resolves the `BaresipKitCore` binary
target in two ways:

- **Local build**: on the default branch, where `coreReleaseURL` is empty, or anywhere with the
  environment variable `BARESIPKIT_LOCAL_CORE=1`, the target uses
  `Core/Build/out/BaresipKitCore.xcframework`, so run the build script first.
- **Release**: at a release tag, `coreReleaseURL` and `coreReleaseChecksum` name the published
  asset, and SwiftPM downloads and verifies it. A local `Core/Build/out` is ignored there unless
  `BARESIPKIT_LOCAL_CORE=1` is set.

Apps add a checkout as a local package (`.package(path: "../baresipkit")`), or a released version
by URL. See [Releasing.md](Releasing.md#how-swiftpm-resolves-the-core).

## Tests

```bash
xcodebuild test -scheme BaresipKit-Package -destination 'platform=iOS Simulator,name=iPhone 16'
```

The sample app's UI test registers and calls a test peer in CI. What needs a physical iPhone is
listed in [DeviceTesting.md](DeviceTesting.md).

## Public API baseline

CI fails when the public API changes without its baseline. The baselines are the
`swift-api-digester` dumps in `Apple/API`, one per product module, made from an iOS Simulator
build so the iOS-only adapters are included. On macOS, after building the XCFramework:

```bash
Apple/Scripts/swift-api.sh check
Apple/Scripts/swift-api.sh dump
```

`check` reports breaking changes and every declaration added or removed; `dump` rewrites the
baselines. CI uploads the dumps it made as the `swift-api` artifact, so a baseline can also be
taken from there. Commit the baselines together with the API change, and describe the change in
`CHANGELOG.md`.

## API documentation

The API reference and the articles are in the DocC catalog
(`Apple/Sources/BaresipKit/BaresipKit.docc`); build it in Xcode with Product > Build
Documentation.
