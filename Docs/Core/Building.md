# Building the C core

Every build starts here: both SDKs link the same C core, built from pinned sources with the
same patches. The Apple and Android builds that follow are in
[Apple/Building.md](../Apple/Building.md) and [Android/Building.md](../Android/Building.md).

## Requirements

- macOS with Xcode 16 or newer (verified with Xcode 27.2) for the Apple slices; the Android NDK
  for the Android slices (see [Android/Building.md](../Android/Building.md#requirements)).
- CMake 3.18+ (`brew install cmake`) and Python 3.
- For the host build on Linux: cmake, a C compiler, libssl-dev, zlib, yasm (for libvpx), Python 3.

## The build script

```bash
Core/Build/build-core.sh
```

The script builds the core for one platform at a time; `PLATFORM` selects which:

| `PLATFORM` | Output | Used by |
|---|---|---|
| `ios` (default) | `Core/Build/out/BaresipKitCore.xcframework`, its zip and `Core/Build/out/checksum.txt` | the Swift package ([Apple/Building.md](../Apple/Building.md)) |
| `android` | `Core/Build/out/android-arm64-v8a` and `Core/Build/out/android-x86_64` | the Android libraries ([Android/Building.md](../Android/Building.md)) |
| `host` | `Core/Build/out/host/prefix` for the machine you are on | the core tests, the fuzzers, the integration suite and the Android JVM tests |

| Variable | Values | Default |
|---|---|---|
| `MODULE_SET` | `minimal`, `default`, `full` (adds VP8 via libvpx) | `default` |
| `SLICES` | `ios-arm64`, `ios-arm64-simulator`, `ios-x86_64-simulator`, `macos-arm64` | iOS device, iOS simulator and macOS, all arm64 |
| `PLATFORM` | `ios`, `host`, `android` | `ios` |
| `SANITIZE` | `address,undefined` or `thread` (host only) | unset |

The script clones each pinned source (`Core/Build/versions.env`), verifies the commit, applies
`Core/Patches/*.patch`, and builds OpenSSL, Opus, libvpx (full), libre, baresip and the facade per
slice. For `ios` it merges the archives with `libtool -static` and runs
`xcodebuild -create-xcframework`; the archive contains libre, baresip (static modules), OpenSSL,
Opus, the BaresipKitCore facade and, on Apple platforms, the VideoToolbox and camera modules.
`OUT_DIR` and `DEPS_DIR` may be relative to the current directory; the script makes them absolute. `OUT_DIR` must not contain symlinks (CMake path resolution). A checkout in `Core/Build/.deps`
that is not at the pinned commit is deleted and cloned again, and the libre and baresip checkouts
are reset and cleaned (`git clean -fdx`) before the patches are applied, so do not keep work in
them. OpenSSL is configured with `no-autoload-config` and with fixed directories (`/etc/ssl` for
the configuration, `/usr/lib/ossl-modules` for engines and providers, neither of which is used),
so the libraries never read a configuration file on their own and do not contain the build path.

Changes to baresip or libre are patches in `Core/Patches/`, each with a row and its rationale in
[Core/Patches/README.md](../../Core/Patches/README.md).

## Host build and core tests

```bash
PLATFORM=host MODULE_SET=full SANITIZE=address,undefined Core/Build/build-core.sh
HOSTIP=<interface address> PREFIX=Core/Build/out/host/prefix Core/Tests/run_tests.sh
```

`SANITIZE` builds libre, baresip, Opus and the facade of the host prefix with the given sanitizers (OpenSSL and libvpx are built without); CI does this on Linux, so the patched network code runs under ASan and UBSan in the core tests, the fuzzer and the integration suite. Delete `Core/Build/out/host` when you change `SANITIZE`, because Opus is not rebuilt once it is in the prefix. `BUILD_ONLY=1` makes `run_tests.sh` stop after building the test binaries.

ThreadSanitizer needs its own prefix, and `SANITIZE=thread` for the build and for the tests:

```bash
OUT_DIR=Core/Build/out/tsan PLATFORM=host MODULE_SET=full SANITIZE=thread Core/Build/build-core.sh
SANITIZE=thread BUILD_DIR=Core/Build/out/tsan/core-tests HOSTIP=<interface address> PREFIX=Core/Build/out/tsan/host/prefix Core/Tests/run_tests.sh
```

With `thread`, libre, baresip and the facade use libre's pthread wrappers instead of C11 threads, because ThreadSanitizer does not intercept glibc's `thrd_create` and crashes in the first thread it creates. CI runs this suite in its own job.

`run_tests.sh` compiles the facade and the C tests with ASan/UBSan and runs: unit tests, registration against a Digest-challenging registrar (UDP and TLS with a private CA, pinning and a missing-CA case), registering again and updating the Contact parameters during an unanswered REGISTER against a registrar that answers late and checks that it still holds the binding, a wrong-password case, two-process audio calls (plain and with ICE), a camera preview with `fakevideo`, and a two-process VP8 video call. `HOSTIP` must be a non-loopback address because baresip excludes loopback interfaces.

## Integration tests

```bash
Core/Tests/Integration/run_integration.sh
```

Runs the core against Asterisk and coturn in Docker (Linux or macOS); see
[Core/Tests/Integration](../../Core/Tests/Integration/README.md).

## Fuzzing

```bash
CC=clang RUNS=300000 PREFIX=Core/Build/out/host/prefix Core/Tests/Fuzz/run_fuzz.sh
```

Builds a libFuzzer harness over the parsing code the facade owns (Contact parameter encoding, log redaction, ICE module event parsing, H.264 RTP depacketization and its Annex B to AVCC conversion). Where libFuzzer is not available, `FUZZ_MODE=driver` builds a mutation driver with ASan/UBSan instead.
