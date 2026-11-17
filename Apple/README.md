# Apple

The Swift package of BaresipKit for iOS and macOS: the products `BaresipKit`, `BaresipKitAudio`,
`BaresipKitVideo`, `BaresipKitCallKit`, `BaresipKitLiveCommunication`, `BaresipKitPushKit` and
`BaresipKitSwiftUI`. The manifest, `Package.swift`, stays at the repository root, where SwiftPM
looks for it; it takes its sources from `Sources/` and its tests from `Tests/` here. The sample
app lives in [Examples/iOS](../Examples/iOS/README.md).

| Path | Content |
|---|---|
| `Sources/` | `BaresipKitBridge` (C helpers), `BaresipKit` (the Swift API and its DocC catalog) and the adapters |
| `Tests/` | `BaresipKitTests` (XCTest) |
| `API/` | the public API baseline, one `swift-api-digester` dump per product |
| `Scripts/` | the API baseline check, the privacy manifest check and the simulator picker CI uses |
| `.swiftformat`, `.swiftlint.yml` | the formatting and lint settings `Scripts/format.sh` and `Scripts/lint.sh` apply |

| Guide | |
|---|---|
| [Docs/Apple/Setup.md](../Docs/Apple/Setup.md) | installation, a quick start, the setup recipes and the app checklist |
| [Docs/Apple/Building.md](../Docs/Apple/Building.md) | building from source, tests and the public API baseline |
| [Docs/Apple/Releasing.md](../Docs/Apple/Releasing.md) | releasing the package |
