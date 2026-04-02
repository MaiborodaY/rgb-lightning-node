// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "SwiftUniffiE2E",
    platforms: [
        .macOS(.v13),
    ],
    products: [
        .library(name: "RGBLightningNode", targets: ["RGBLightningNode"]),
    ],
    targets: [
        .systemLibrary(
            name: "RGBLightningNodeFFI",
            path: "FFI"
        ),
        .target(
            name: "RGBLightningNode",
            dependencies: ["RGBLightningNodeFFI"],
            path: "Sources/RGBLightningNode",
            linkerSettings: [
                .unsafeFlags([
                    "-L",
                    "Libraries/macos",
                ]),
                .linkedLibrary("rgb_lightning_node"),
            ]
        ),
        .testTarget(
            name: "SwiftUniffiE2ETests",
            dependencies: ["RGBLightningNode"],
            path: "Tests/SwiftUniffiE2ETests"
        ),
    ]
)
