# Releasing the Android libraries

How **Release Android** (`.github/workflows/release-android.yml`) publishes a version. Versioning
and the pull request that prepares a release are in [Releasing.md](../Releasing.md).

## What the workflow does

Run **Release Android** from the Actions tab with the same version, once **Release iOS** has
tagged `v<version>`. **Dry run** is on by default.

1. Checks the version, checks out the tag `v<version>`, and checks `VERSION`, the changelog
   section and the CI result of the commit the release commit is based on.
2. Builds the core for Android (`MODULE_SET=full`), and builds, ABI-checks and tests the
   libraries with that version.
3. Uploads the six AARs, an SPDX SBOM (`baresipkit-android.spdx.json`) and `SHA256SUMS` as the
   workflow artifact `android-release-<version>`.
4. Without dry run: attests their provenance, signs the publications and **stages** the
   deployment on Maven Central (`publishToMavenCentral`). Nothing is released until a
   maintainer checks the deployment on the Central Portal and releases it there.

## Secrets

The workflow publishes to Maven Central from the default branch once CI passed for the commit.
It needs the secrets `MAVEN_CENTRAL_USERNAME` and `MAVEN_CENTRAL_PASSWORD` (a Central Portal
token) and `SIGNING_KEY` and `SIGNING_KEY_PASSWORD` (an ASCII-armored GPG key).
