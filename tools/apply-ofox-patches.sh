#!/bin/bash
# Apply the piano OrangeFox source patches to an OrangeFox 16 checkout.
# Usage: tools/apply-ofox-patches.sh <source root>
# Safe to run again: a patch that is already applied is skipped. If a patch no
# longer fits (OrangeFox moved on), nothing more is applied and the script
# stops with the name of that patch.
set -euo pipefail
ROOT=$(realpath "${1:?usage: $0 <OrangeFox source root>}")
PATCHES=$(cd "$(dirname "$0")/../patches" && pwd)

apply_dir() {
	local project=$1 dir=$2 p
	for p in "$PATCHES/$dir"/*.patch; do
		if git -C "$ROOT/$project" apply --check "$p" 2>/dev/null; then
			git -C "$ROOT/$project" apply "$p"
			echo "applied   $project  $(basename "$p")"
		elif git -C "$ROOT/$project" apply --reverse --check "$p" 2>/dev/null; then
			echo "present   $project  $(basename "$p")"
		else
			echo "FAILED    $project  $(basename "$p") does not apply; update it for this revision" >&2
			exit 1
		fi
	done
}

apply_dir bootable/recovery ofox_bootable_recovery
apply_dir system/core ofox_system_core

# Maintainer photo for the About page.
cp "$PATCHES/../theme/maintainer.png" "$ROOT/bootable/recovery/gui/theme/portrait_hdpi/images/Default/About/maintainer.png"
echo "copied    maintainer.png"

# repo leaves projects on a detached HEAD, which OrangeFox reports as
# "Branch: (no branch)". Name the branch; the patched working tree is kept.
git -C "$ROOT/bootable/recovery" checkout -q -B fox_16.0
echo "branch    bootable/recovery  fox_16.0"
