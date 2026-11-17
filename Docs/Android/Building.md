# Building the Android libraries

How to build and test the Android libraries from source. The C core comes first:
[Core/Building.md](../Core/Building.md).

## Requirements

JDK 21, the Android SDK with platform 36, NDK `30.0.16248370` and CMake 3.22.1, and `cmake` and
`nasm` on the build machine.

## The core

Build the C core for both Android ABIs, from the repository root:

```bash
PLATFORM=android MODULE_SET=full ANDROID_NDK_HOME="$ANDROID_HOME/ndk/30.0.16248370" Core/Build/build-xcframework.sh
```

The variables are in [Core/Building.md](../Core/Building.md#the-build-script).

## Using the libraries

The Gradle project is `Android/`. Build the libraries:

```bash
cd Android && ./gradlew assembleDebug
```

## Tests

The JVM unit tests load the JNI bridge built against the core for the Linux host. Build that core
once, then run the tests:

```bash
PLATFORM=host MODULE_SET=full Core/Build/build-xcframework.sh
```

```bash
cd Android && ./gradlew testDebugUnitTest
```

On other hosts the tests that need the core are skipped. The call tests talk to a small scripted
SIP peer over UDP on the machine's own address. The instrumented tests run on an emulator in CI;
one of them sends a call's H.264 video through a peer that echoes it, so MediaCodec encodes and
decodes it.

Kotlin follows ktlint's `ktlint_official` style with the settings in `.editorconfig`:

```bash
cd Android && ./gradlew ktlintCheck
```

`./gradlew ktlintFormat` fixes most of what `ktlintCheck` reports.

## Public API baseline

CI fails when the public Kotlin API of a library changes without its baseline. The baselines are
the ABI dumps in `Android/<library>/api/<library>.api`, written by the Kotlin Gradle plugin's ABI
validation. They cover binary compatibility, so a changed signature, a removed overload or a new
constructor parameter all show up. In `Android/`:

```bash
./gradlew checkKotlinAbi
./gradlew updateKotlinAbi
```

`checkKotlinAbi` compares the compiled libraries with the dumps; `updateKotlinAbi` rewrites them.
Commit the dumps together with the API change, and describe the change in `CHANGELOG.md`.

## API documentation

The API reference is generated with Dokka:

```bash
cd Android && ./gradlew :dokkaGeneratePublicationHtml
```
