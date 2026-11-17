# Releasing

How a release is cut. Nothing here happens on its own: a maintainer starts every step. Both
platforms release the same version from the same commit, each with its own workflow:

| Platform | Workflow | Guide | Published to |
|---|---|---|---|
| iOS and macOS | `.github/workflows/release-ios.yml` (**Release iOS**) | [Apple/Releasing.md](Apple/Releasing.md) | a GitHub release with the XCFramework, and a tag `v<version>` whose `Package.swift` points at it |
| Android | `.github/workflows/release-android.yml` (**Release Android**) | [Android/Releasing.md](Android/Releasing.md) | Maven Central |

**Release iOS** creates the tag `v<version>`, and **Release Android** builds from that tag, so
run them in that order. Both default to a dry run that checks and assembles everything and
publishes nothing.

## Versioning

BaresipKit follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html); what it covers is
in [STABILITY.md](STABILITY.md). A version is `MAJOR.MINOR.PATCH`, optionally with a pre-release
suffix such as `-rc.1`, and is published as a GitHub pre-release when it has one. Tags are
`v<version>`.

The version lives in one place, the `VERSION` file at the root. The core reads it at build time
(`bsk_version_string()` and the default `User-Agent`, `BaresipKit/<version>`), and the release
workflow refuses a version that differs from it.

## Before a release

In a pull request to the default branch:

1. Set `VERSION` to the version to release.
2. In `CHANGELOG.md`, rename `[Unreleased]` to `## [<version>] - <YYYY-MM-DD>`, add a link
   definition `[<version>]: …` at the end, and add a fresh, empty `[Unreleased]` section above.

Merge it and wait until CI is green for the merge commit.
