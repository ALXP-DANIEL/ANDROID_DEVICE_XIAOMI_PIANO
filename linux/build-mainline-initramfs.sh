#!/bin/sh
# SPDX-License-Identifier: BSD-2-Clause-Patent
# Build the first-stage initramfs for the mainline kernel with
# blu-sharky/debian-piano's builder (MIT) in rootfs mode. Its /pianoinit
# repairs the SMMU stream matches, enables the UFS reference clock, loads
# the UFS modules and switches to the root; we only point it at our
# "linux" partition instead of userdata.
#   build-mainline-initramfs.sh debian-piano/ mods/ authorized_keys out.cpio.gz
# mods/ is the kernel's INSTALL_MOD_PATH (lib/modules/<release>/...).
set -eu
abs() { echo "$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"; }
dp=$(abs "$1"); mods=$(abs "$2"); keys=$(abs "$3"); out=$(abs "$4")
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
cp -R "$dp" "$work/dp"
sed -i.orig 's/userdata/linux/g' "$work/dp/initramfs/rootfs-init"
rm "$work/dp/initramfs/rootfs-init.orig"

docker run --rm --platform linux/arm64 -v "$work:/w" -v "$mods:/mods:ro" \
	-v "$keys:/keys:ro" debian:trixie sh -euc '
	apt-get update -qq && apt-get install -y -qq busybox-static dropbear-bin kmod cpio gzip libgmp10 python3 openssh-client >/dev/null 2>&1
	mkdir -p /w/bb /w/db/usr/sbin /w/db/usr/bin
	cp /bin/busybox /w/bb/
	cp /usr/sbin/dropbear /w/db/usr/sbin/; cp /usr/bin/dropbearkey /w/db/usr/bin/
	for f in $(ldd /usr/sbin/dropbear /usr/bin/dropbearkey | grep -o "/[^ ]*\.so[^ ]*" | sort -u); do
		mkdir -p /w/db$(dirname $f); cp -L $f /w/db$f
	done
	# The builder expects the Debian tools-tree layout (fetch-arm64-tools.sh).
	mkdir -p /w/db/lib /w/db/usr/lib/aarch64-linux-gnu
	cp -L /lib/ld-linux-aarch64.so.1 /w/db/lib/
	cp -L /usr/lib/aarch64-linux-gnu/libgmp.so.10 /w/db/usr/lib/aarch64-linux-gnu/
	rel=$(ls /mods/lib/modules)
	set --
	for m in phy-qcom-qmp-ufs ufs_qcom ufshcd-pltfrm ufshcd-core; do
		# Module files use - or _ freely (ufs-qcom.ko loads as ufs_qcom).
		f=$(find /mods/lib/modules/$rel -name "$m.ko*" -o -name "$(echo $m | tr _ -).ko*" | head -1)
		[ -n "$f" ] || { echo "missing module $m" >&2; exit 1; }
		[ -n "$f" ] && set -- "$@" --module "$f"
	done
	cd /w/dp && bash scripts/build-initramfs.sh --mode rootfs --busybox /w/bb \
		--dropbear-tree /w/db --authorized-keys /keys --kernel-version "$rel" \
		"$@" --output /w/initramfs.cpio.gz'
cp "$work/initramfs.cpio.gz" "$out"
ls -l "$out"
