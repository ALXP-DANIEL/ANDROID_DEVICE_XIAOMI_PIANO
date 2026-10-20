#!/bin/sh
# SPDX-License-Identifier: BSD-2-Clause-Patent
# Build the Arch Linux ARM root filesystem for piano as a tarball.
#   build-rootfs.sh modules.tar device-firmware.tar authorized_keys out.tar
# modules.tar: vendor_dlkm and system_dlkm modules from the matching ROM build.
# device-firmware.tar: firmware trees pulled from the running tablet.
# Runs natively on an arm64 Docker host (Apple silicon).
set -eu
here=$(cd "$(dirname "$0")" && pwd)
abs() { echo "$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"; }
mods=$(abs "$1"); fw=$(abs "$2"); keys=$(abs "$3"); out=$(abs "$4")
name=piano-rootfs-$$

docker run --platform linux/arm64 --name "$name" \
	-v "$here/rootfs:/overlay:ro" -v "$mods:/in/modules.tar:ro" \
	-v "$fw:/in/firmware.tar:ro" -v "$keys:/in/authorized_keys:ro" \
	menci/archlinuxarm:latest /overlay/setup.sh
docker export "$name" > "$out"
docker rm "$name" >/dev/null
ls -l "$out"
