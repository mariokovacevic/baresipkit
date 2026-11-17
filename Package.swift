// swift-tools-version: 6.0
import Foundation
import PackageDescription

let coreReleaseURL = ""
let coreReleaseChecksum = ""

let localCorePath = "Core/Build/out/BaresipKitCore.xcframework"
let useLocalCore = coreReleaseURL.isEmpty || ProcessInfo.processInfo.environment["BARESIPKIT_LOCAL_CORE"] == "1"

let coreTarget: Target = useLocalCore
    ? .binaryTarget(name: "BaresipKitCore", path: localCorePath)
    : .binaryTarget(name: "BaresipKitCore", url: coreReleaseURL, checksum: coreReleaseChecksum)

let package = Package(
    name: "BaresipKit",
    platforms: [
        .iOS(.v16),
        .macOS(.v13),
    ],
    products: [
        .library(name: "BaresipKit", targets: ["BaresipKit"]),
        .library(name: "BaresipKitAudio", targets: ["BaresipKitAudio"]),
        .library(name: "BaresipKitVideo", targets: ["BaresipKitVideo"]),
        .library(name: "BaresipKitCallKit", targets: ["BaresipKitCallKit"]),
        .library(name: "BaresipKitLiveCommunication", targets: ["BaresipKitLiveCommunication"]),
        .library(name: "BaresipKitPushKit", targets: ["BaresipKitPushKit"]),
        .library(name: "BaresipKitSwiftUI", targets: ["BaresipKitSwiftUI"]),
    ],
    targets: [
        coreTarget,
        .target(
            name: "BaresipKitBridge",
            dependencies: ["BaresipKitCore"],
            path: "Apple/Sources/BaresipKitBridge",
            linkerSettings: [
                .linkedFramework("AudioToolbox"),
                .linkedFramework("AVFoundation"),
                .linkedFramework("CoreAudio"),
                .linkedFramework("CoreFoundation"),
                .linkedFramework("CoreMedia"),
                .linkedFramework("CoreVideo"),
                .linkedFramework("Security"),
                .linkedFramework("SystemConfiguration"),
                .linkedFramework("VideoToolbox"),
                .linkedLibrary("resolv"),
                .linkedLibrary("z"),
            ]
        ),
        .target(
            name: "BaresipKit",
            dependencies: ["BaresipKitCore", "BaresipKitBridge"],
            path: "Apple/Sources/BaresipKit",
            resources: [.copy("Resources/PrivacyInfo.xcprivacy")]
        ),
        .target(
            name: "BaresipKitAudio",
            dependencies: ["BaresipKit"],
            path: "Apple/Sources/BaresipKitAudio"
        ),
        .target(
            name: "BaresipKitVideo",
            dependencies: ["BaresipKit"],
            path: "Apple/Sources/BaresipKitVideo"
        ),
        .target(
            name: "BaresipKitSystemCall",
            dependencies: ["BaresipKit"],
            path: "Apple/Sources/BaresipKitSystemCall"
        ),
        .target(
            name: "BaresipKitCallKit",
            dependencies: ["BaresipKit", "BaresipKitSystemCall"],
            path: "Apple/Sources/BaresipKitCallKit"
        ),
        .target(
            name: "BaresipKitLiveCommunication",
            dependencies: ["BaresipKit", "BaresipKitSystemCall"],
            path: "Apple/Sources/BaresipKitLiveCommunication"
        ),
        .target(
            name: "BaresipKitPushKit",
            dependencies: ["BaresipKit", "BaresipKitSystemCall"],
            path: "Apple/Sources/BaresipKitPushKit"
        ),
        .target(
            name: "BaresipKitSwiftUI",
            dependencies: ["BaresipKit", "BaresipKitVideo", "BaresipKitSystemCall"],
            path: "Apple/Sources/BaresipKitSwiftUI"
        ),
        .testTarget(
            name: "BaresipKitTests",
            dependencies: [
                "BaresipKitCore", "BaresipKitBridge", "BaresipKit", "BaresipKitAudio",
                "BaresipKitVideo", "BaresipKitSystemCall", "BaresipKitCallKit", "BaresipKitLiveCommunication",
                "BaresipKitPushKit", "BaresipKitSwiftUI",
            ],
            path: "Apple/Tests/BaresipKitTests"
        ),
    ],
    swiftLanguageModes: [.v6]
)
