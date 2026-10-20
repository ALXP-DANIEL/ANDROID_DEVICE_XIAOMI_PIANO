#!/bin/bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Builds ALXP.app into ./build. macOS keeps Screen Recording, Accessibility
# and Local Network permissions per app, so the binary runs from a bundle.
set -euo pipefail
cd "$(dirname "$0")"
swift build -c release
app=build/ALXP.app
rm -rf "$app"
mkdir -p "$app/Contents/MacOS"
cp .build/release/ALXPMac "$app/Contents/MacOS/ALXP"
cat > "$app/Contents/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
    <key>CFBundleIdentifier</key><string>org.alxp.mac</string>
    <key>CFBundleName</key><string>ALXP</string>
    <key>CFBundleExecutable</key><string>ALXP</string>
    <key>CFBundlePackageType</key><string>APPL</string>
    <key>CFBundleShortVersionString</key><string>1.0</string>
    <key>LSMinimumSystemVersion</key><string>14.0</string>
    <key>LSUIElement</key><true/>
    <key>NSLocalNetworkUsageDescription</key><string>ALXP finds your Pad on the local network.</string>
    <key>NSBonjourServices</key><array><string>_alxp._tcp</string></array>
</dict></plist>
PLIST
codesign --force --sign - "$app"
echo "Built $PWD/$app"
