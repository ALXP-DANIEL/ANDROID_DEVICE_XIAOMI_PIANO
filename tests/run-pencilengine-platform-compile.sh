#!/usr/bin/env bash
# Compile the actual service/transport against caller-supplied full framework jars.
# Public android.jar omits the platform APIs this privileged ROM app requires.
set -euo pipefail
[[ $# == 2 ]] || { echo "Usage: $0 android16-framework.jar android17-framework.jar" >&2; exit 2; }
parts_dir=$(cd "$(dirname "$0")/.." && pwd)
out=$(mktemp -d)
trap 'rm -rf "$out"' EXIT
sources=()
while IFS= read -r -d '' file; do sources+=("$file"); done < <(
    find "$parts_dir/pencilengine-service/src" \
         "$parts_dir/src/org/lineageos/pianoparts/pencilengine" -name '*.java' -print0
)
for release in 16 17; do
    framework=$1
    shift
    [[ -f "$framework" ]] || { echo "Missing framework jar: $framework" >&2; exit 1; }
    mkdir "$out/$release"
    javac -cp "$framework" -d "$out/$release" "${sources[@]}"
    echo "Android $release PencilEngine service/transport compile passed"
done
