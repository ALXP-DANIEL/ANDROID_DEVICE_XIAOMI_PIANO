#!/bin/bash
# Apply piano's changes to LineageOS source projects.
# Run from the top of the LineageOS tree: device/xiaomi/piano/patches/apply-patches.sh
# Existing source edits are preserved. Already applied patches are skipped.
set -e
TOP=$(pwd)
DIR=$(cd "$(dirname "$0")" && pwd)
# The EvoX setup-wizard rotation patch was retired. If an earlier build
# applied it in this source checkout, restore only that exact hunk. Do not
# reset the vendor project or discard unrelated source modifications.
pixel_common="$TOP/vendor/pixel-style/config/common.mk"
if [ -f "$pixel_common" ]; then
  python3 - "$pixel_common" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
previous = (
    "# PRODUCT_CHARACTERISTICS is not set yet when this file is read, so lock\n"
    "# rotation by default and let tablets set ro.setupwizard.rotation_locked=false.\n"
    "PRODUCT_PRODUCT_PROPERTIES += ro.setupwizard.rotation_locked?=true\n"
)
original = (
    "ifeq ($(PRODUCT_CHARACTERISTICS),tablet)\n"
    "PRODUCT_PRODUCT_PROPERTIES += ro.setupwizard.rotation_locked=false\n"
    "else\n"
    "PRODUCT_PRODUCT_PROPERTIES += ro.setupwizard.rotation_locked=true\n"
    "endif\n"
)
content = path.read_text()
if previous in content:
    if content.count(previous) != 1:
        raise SystemExit("Unexpected duplicate retired rotation patch; manual review required")
    path.write_text(content.replace(previous, original, 1))
    print("reverted retired EvoX setup-wizard rotation patch")
PY
fi
for d in "$DIR"/*/; do
  project=$(basename "$d" | tr _ /)
  # Later patches can change lines an earlier one touched, so a per-patch
  # check fails once the whole series is in. Remember the applied series.
  stamp="$(git -C "$TOP/$project" rev-parse --absolute-git-dir)/piano-patches"
  sum=$(cat "$d"*.patch | sha1sum | cut -d' ' -f1)
  if [ "$(cat "$stamp" 2>/dev/null)" = "$sum" ] && ! git -C "$TOP/$project" diff --quiet; then
    echo "already applied: $project (all)"
    continue
  fi
  # A changed series cannot be applied over the old one. Start clean.
  if [ -f "$stamp" ]; then
    git -C "$TOP/$project" checkout -q -- .
    git -C "$TOP/$project" clean -qfd
  fi
  for p in "$d"*.patch; do
    if git -C "$TOP/$project" apply --reverse --check "$p" 2>/dev/null; then
      echo "already applied: $project $(basename "$p")"
    else
      git -C "$TOP/$project" apply --check "$p"
      git -C "$TOP/$project" apply "$p"
      echo "applied: $project $(basename "$p")"
    fi
  done
  echo "$sum" > "$stamp"
done
