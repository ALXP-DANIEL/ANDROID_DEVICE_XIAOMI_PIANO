#!/usr/bin/env bash
# SPDX-License-Identifier: BSD-2-Clause-Patent
# Set up deps/ for a UEFI build: Mu-Silicium at the tested commit, our
# Mu_Basecore changes, and links to pianoPkg, its config and the bundled
# Qualcomm drivers (uefi/Binaries/piano). Run from anywhere; safe to rerun.
set -euo pipefail
cd "$(dirname "$0")/.."

MU_URL=https://github.com/Project-Silicium/Mu-Silicium
MU_COMMIT=e51cd877e31c8fbb322032dc4cb6715e83d25ad3
mu=deps/Mu-Silicium

if [ ! -d "$mu" ]; then
	git clone -q "$MU_URL" "$mu"
	git -C "$mu" checkout -q "$MU_COMMIT"
	git -C "$mu" submodule update -q --init --recursive
	git -C "$mu/Mu_Basecore" apply "$PWD/uefi/patches/mu-basecore.patch"
fi

link() {
	[ -L "$2" ] || ln -s "$1" "$2"
}
link ../../../../uefi/pianoPkg "$mu/Platforms/Xiaomi/pianoPkg"
link ../../../../uefi/piano.toml "$mu/Resources/Configs/piano.toml"
link ../../../uefi/Binaries/piano "$mu/Binaries/piano"
echo "deps ready: $mu"
