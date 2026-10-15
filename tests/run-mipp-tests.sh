#!/usr/bin/env bash
set -eu
parts_dir=$(cd "$(dirname "$0")/.." && pwd)
check_dir=$(mktemp -d)
trap 'rm -rf "$check_dir"' EXIT
kotlin_compiler=${KOTLINC:-kotlinc}
javac -d "$check_dir" "$parts_dir/tests/Log.java" \
    "$parts_dir/tests/UEventObserver.java" \
    "$parts_dir/tests/InputDevice.java" \
    "$parts_dir/src/org/lineageos/pianoparts/stylus/MippProtocol.java"
"$kotlin_compiler" -classpath "$check_dir" \
    "$parts_dir/src/org/lineageos/pianoparts/stylus/MippSession.kt" \
    "$parts_dir/src/org/lineageos/pianoparts/stylus/PenUEventObserver.kt" \
    "$parts_dir/src/org/lineageos/pianoparts/stylus/XiaomiStylusDevice.kt" \
    "$parts_dir/tests/MippSessionTest.kt" "$parts_dir/tests/PenUEventTest.kt" \
    -include-runtime -d "$check_dir/tests.jar"
java -cp "$check_dir/tests.jar:$check_dir" org.lineageos.pianoparts.stylus.MippSessionTestKt
