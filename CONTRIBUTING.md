# Contributing to BaresipKit

Thank you for helping. This guide covers setting up, building, testing and sending changes.

## Setup

Each part of the repository has its own tools; install those of the parts you change. The C core
and the patches are shared, so a change to them has to build and pass on both platforms.

| Part | You need |
|---|---|
| C core (`Core/`) | CMake 3.18 or later, a C compiler and Python 3; macOS with Xcode for the Apple slices |
| Apple (`Apple/`, `Package.swift`) | macOS with Xcode 16 or later, `swiftformat`, `swiftlint` and `xcodegen` |
| Android (`Android/`) | JDK 21, the Android SDK with platform 36 and NDK `30.0.16248370` |

For formatting and linting also install the tools CI uses:

```bash
brew install cmake swiftformat swiftlint xcodegen
python3 -m pip install --user clang-format==18.1.3
```

## The C core

The C core (libre, baresip, OpenSSL, Opus and the BaresipKit facade in `Core/`) is built from the
pinned sources listed in `Core/Build/versions.env`, with the patches in `Core/Patches/`. The tests
run against a build for the machine you are on, with AddressSanitizer and
UndefinedBehaviorSanitizer:

```bash
PLATFORM=host MODULE_SET=full Core/Build/build-core.sh
PREFIX=Core/Build/out/host/prefix Core/Tests/run_tests.sh
```

Integration tests against Asterisk and coturn (Linux or macOS with Docker):

```bash
Core/Tests/Integration/run_integration.sh
```

[Docs/Core/Building.md](Docs/Core/Building.md) has the module sets, the sanitizers and fuzzing.

## Apple

Build the core for Apple platforms into `Core/Build/out/BaresipKitCore.xcframework`; on the
default branch `Package.swift` uses it, so the package then builds in Xcode like any other. In a
checkout of a release tag, set `BARESIPKIT_LOCAL_CORE=1` to use it instead of the published
binary.

```bash
Core/Build/build-core.sh
```

```bash
xcodebuild test -scheme BaresipKit-Package -destination 'platform=iOS Simulator,name=iPhone 16'
```

The sample app:

```bash
cd Examples/iOS && xcodegen generate && open BaresipKitSample.xcodeproj
```

[Docs/Apple/Building.md](Docs/Apple/Building.md) has the details and the public API baseline;
tests that need a physical iPhone are listed in
[Docs/Apple/DeviceTesting.md](Docs/Apple/DeviceTesting.md).

## Android

Build the core for both Android ABIs, and for the host, whose core the JVM tests load:

```bash
PLATFORM=android MODULE_SET=full ANDROID_NDK_HOME="$ANDROID_HOME/ndk/30.0.16248370" Core/Build/build-core.sh
PLATFORM=host MODULE_SET=full Core/Build/build-core.sh
```

```bash
cd Android && ./gradlew assembleDebug testDebugUnitTest ktlintCheck
```

Kotlin follows ktlint's `ktlint_official` style with the settings in `.editorconfig`;
`./gradlew ktlintFormat` fixes most of what `ktlintCheck` reports. The instrumented tests and
the Android sample's UI tests run on an emulator in CI.
[Docs/Android/Building.md](Docs/Android/Building.md) has the details and the public API
baseline.

## Format and lint

```bash
Scripts/format.sh
Scripts/lint.sh
```

CI runs `Scripts/lint.sh` and fails on any difference. C code follows the style in
`.clang-format` (tabs, Linux brace style, 80 columns); Swift follows `Apple/.swiftformat` and
`Apple/.swiftlint.yml`.

## Code rules

- Readable code without explaining comments. The only comments are DocC comments (`///`) on
  public Swift API, KDoc on public Kotlin API, and short `// SAFETY:` notes where unsafe
  pointers or C types cross a boundary.
- C types and unsafe pointers stay at the boundary: in `Apple/Sources/BaresipKit/Interop` and the
  bridge target on Apple, in the JNI bridge (`Android/baresipkit/src/main/cpp`) and
  `internal/Native.kt` on Android.
- No force unwraps, `try!` or `fatalError` in Swift, and no `!!` in Kotlin, outside tests.
- Every public symbol has a DocC comment in Swift and a KDoc comment in Kotlin.
- Changes to baresip or libre go into `Core/Patches/` as a small diff with a row in
  `Core/Patches/README.md`, never into downloaded sources.
- Never log credentials, authorization headers or full push tokens.
- The defaults suit any SIP peer. Behaviour that only some deployments, devices or test setups
  need is an option that is off by default, never a change to the default. Such an option is a
  typed setting on both platforms with a DocC and a KDoc comment that say when to turn it on, a
  capability in `bsk_capabilities` when the core implements it, and a core test for each of its
  values. The sample apps turn it on, as one of their settings, and
  [Examples/README.md](Examples/README.md) lists it under "Options the samples turn on".

## Commits

Use [Conventional Commits](https://www.conventionalcommits.org/): `feat:`, `fix:`, `refactor:`,
`style:`, `docs:`, `test:`, `ci:`, `build:` or `chore:`, one concern per commit. Explain why in
the body when it is not obvious.

## License of contributions

Contributions are accepted under the project's license. By opening a pull request you agree that
your contribution is licensed under the BSD 3-Clause license in `LICENSE`, and you confirm that
you may submit it: it is your own work, or its author or your employer allows it.

## Pull request checklist

- [ ] `Scripts/lint.sh` passes.
- [ ] The C tests pass, and the tests of each platform the change touches: the Swift tests for
  `Apple/`, the Android build, its JVM tests and ktlint for `Android/`, and both for changes to
  the core or the patches.
- [ ] New public API has DocC or KDoc comments, and `CHANGELOG.md` lists renames or removals.
- [ ] `CHANGELOG.md` has an entry under "Unreleased".
- [ ] No credentials, real hostnames, IP addresses or phone numbers in code, tests or logs; use
  `example.com` and the documentation address ranges (192.0.2.0/24, 198.51.100.0/24,
  203.0.113.0/24, 2001:db8::/32), and fictional numbers such as `+1 555 0100` to `+1 555 0199`.
- [ ] No names of companies, commercial products, other SDKs or apps; describe the behaviour
  instead.
- [ ] Behaviour that only some deployments need is an option that is off by default, tested with
  both values, turned on in the sample apps and listed in `Examples/README.md`.
