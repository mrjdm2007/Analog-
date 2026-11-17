#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PACKAGE="$ROOT/macOS/AnalogMac"
APP="$ROOT/build/Analog.app"
swift build --package-path "$PACKAGE" --triple arm64-apple-macosx13.0 -c release
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS"
cp "$PACKAGE/.build/release/AnalogMac" "$APP/Contents/MacOS/AnalogMac"
cat > "$APP/Contents/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleExecutable</key><string>AnalogMac</string>
    <key>CFBundleIdentifier</key><string>com.mrjdm2007.analog.mac</string>
    <key>CFBundleName</key><string>Analog</string>
    <key>CFBundleDisplayName</key><string>Analog</string>
    <key>CFBundlePackageType</key><string>APPL</string>
    <key>CFBundleVersion</key><string>1</string>
    <key>CFBundleShortVersionString</key><string>1.0</string>
    <key>LSMinimumSystemVersion</key><string>13.0</string>
    <key>NSPrincipalClass</key><string>NSApplication</string>
    <key>NSMicrophoneUsageDescription</key><string>Analog uses the microphone when you start a recording.</string>
    <key>NSHighResolutionCapable</key><true/>
</dict>
</plist>
PLIST
codesign --force --deep --sign - "$APP" >/dev/null 2>&1 || true
ditto -c -k --sequesterRsrc --keepParent "$APP" "$ROOT/build/Analog-macOS.zip"
echo "Created $APP and build/Analog-macOS.zip"
