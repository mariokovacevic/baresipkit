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

## Options the samples turn on

The libraries' defaults suit any SIP peer. Behaviour that only some deployments need is an option
that is off by default; the samples turn these on, because the intercoms they are tested with
need them. Each is a setting in the samples, and your app sets the same property when its
deployment needs it.

| Option (Swift; Kotlin differs only in the case of acronyms) | Library default | Samples | What it is for |
|---|---|---|---|
| `nat.pinhole` | off | on | A media relay that forwards the caller's media only after it has seen a packet from the app |
| `nat.rtpTimeoutPrefersAudio` | off | on | A peer that stops its video without a new offer while the conversation goes on |
| `answerEarlyMedia` | off | on | Seeing and hearing the door while it rings |
| `pictureUpdateOverSIPInfo` | off | on | A peer that sends a new picture only when it is asked with SIP INFO, not with RTCP |
| `dtmfMode` | `.auto` | `.sipInfo` | A door opener that reads digits from SIP INFO |

## Local profiles

A git-ignored local profile adapts either app to a particular SIP deployment without changing
its code: `Examples/iOS/Local/` on iOS and `Examples/Android/src/local/` on Android. Each README
describes it.
