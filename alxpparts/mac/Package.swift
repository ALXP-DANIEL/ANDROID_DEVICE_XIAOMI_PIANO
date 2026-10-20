// swift-tools-version:5.9
// SPDX-License-Identifier: GPL-3.0-or-later
import PackageDescription

let package = Package(
    name: "ALXPMac",
    platforms: [.macOS(.v14)],
    targets: [
        // CoreGraphics' private virtual display classes, as used by display tools.
        .target(name: "CVirtualDisplay"),
        .executableTarget(
            name: "ALXPMac",
            dependencies: ["CVirtualDisplay"],
            linkerSettings: [
                .linkedFramework("AppKit"),
                .linkedFramework("ScreenCaptureKit"),
                .linkedFramework("VideoToolbox"),
                .linkedFramework("CoreMedia"),
            ]
        ),
    ]
)
