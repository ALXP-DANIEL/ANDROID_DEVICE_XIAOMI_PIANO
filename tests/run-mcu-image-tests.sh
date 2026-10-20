#!/usr/bin/env bash
# Host-only pinned HyperOS .44 connector MCU inspection and packet-preview checks.
set -euo pipefail
parts_dir=$(cd "$(dirname "$0")/.." && pwd)
image="${1:-${PIANO_MCU_STOCK_IMAGE:-vendor/xiaomi/piano/proprietary/odm/firmware/MCU_Upgrade.bin}}"
build_dir=$(mktemp -d)
trap 'rm -rf "$build_dir"' EXIT
javac -d "$build_dir" \
    "$parts_dir/src/org/lineageos/pianoparts/keyboard/KeyboardProtocol.java" \
    "$parts_dir/src/org/lineageos/pianoparts/keyboard/KeyboardMcuFirmware.java" \
    "$parts_dir/tests/KeyboardMcuFirmwareTest.java"
java -cp "$build_dir" org.lineageos.pianoparts.keyboard.KeyboardMcuFirmwareTest "$image"
