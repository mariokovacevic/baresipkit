# Examples

The sample apps, one per platform. Both are the same app, a video intercom with saved doors, a
ring screen with a preview before answering, calls with an Open button, a keypad, messages and
settings. Both use every module of their SDK and run in CI.

| Folder | Platform | Built with | How to run it |
|---|---|---|---|
| [iOS](iOS/README.md) | iOS 16 or later | SwiftUI, an XcodeGen project (`project.yml`) that uses the Swift package from the repository root | `cd Examples/iOS && xcodegen generate && open BaresipKitSample.xcodeproj` |
| [Android](Android/README.md) | Android 9 (API 28) or later | Jetpack Compose; the Gradle project `:sample` of the build in `Android/`, next to the libraries | `cd Android && ./gradlew :sample:installDebug` |

Both apps are named "BaresipKit Sample". They are not part of the libraries and are not covered
by [Semantic Versioning](../Docs/STABILITY.md).

A git-ignored local profile adapts either app to a particular SIP deployment without changing
its code: `Examples/iOS/Local/` on iOS and `Examples/Android/src/local/` on Android. Each README
describes it.
