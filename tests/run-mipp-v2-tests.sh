#!/usr/bin/env bash
set -euo pipefail
parts_dir=$(cd "$(dirname "$0")/.." && pwd)
out=$(mktemp -d)
trap 'rm -rf "$out"' EXIT
javac -d "$out" \
    "$parts_dir/src/org/lineageos/pianoparts/stylus/firmware/FirmwareImage.java" \
    "$parts_dir/src/org/lineageos/pianoparts/stylus/firmware/MippV2Protocol.java" \
    "$parts_dir/src/org/lineageos/pianoparts/stylus/firmware/MippV2Session.java" \
    "$parts_dir/tests/firmware/MippV2ProtocolTest.java" \
    "$parts_dir/tests/firmware/MippV2SessionTest.java"
java -cp "$out" MippV2ProtocolTest
java -cp "$out" MippV2SessionTest
