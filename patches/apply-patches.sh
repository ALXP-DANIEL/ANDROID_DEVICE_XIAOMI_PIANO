#!/bin/bash
# Apply piano's changes to LineageOS source projects.
# Run from the top of the LineageOS tree: device/xiaomi/piano/patches/apply-patches.sh
# Existing source edits are preserved. Already applied patches are skipped.
set -e
TOP=$(pwd)
DIR=$(cd "$(dirname "$0")" && pwd)
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
