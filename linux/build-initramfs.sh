#!/bin/sh
# SPDX-License-Identifier: BSD-2-Clause-Patent
# Build the Arch Linux first-stage initramfs.
#   build-initramfs.sh modules.tar out.cpio.gz
# modules.tar holds vendor_ramdisk/lib/modules from the ROM build that
# matches the kernel in boot.img. busybox comes from Alpine's static build.
set -eu
here=$(cd "$(dirname "$0")" && pwd)
tar=$(cd "$(dirname "$1")" && pwd)/$(basename "$1")
out=$(cd "$(dirname "$2")" && pwd)/$(basename "$2")
root=$(mktemp -d)
trap 'rm -rf "$root"' EXIT

mkdir -p "$root/bin" "$root/etc" "$root/lib/modules"
tar -xf "$tar" -C "$root" vendor_ramdisk/lib/modules
mv "$root"/vendor_ramdisk/lib/modules/* "$root/lib/modules/"
rm -rf "$root/vendor_ramdisk"
docker run --rm --platform linux/arm64 alpine:3.20 \
	sh -c 'apk add -q busybox-static && cat /bin/busybox.static' > "$root/bin/busybox"
chmod 755 "$root/bin/busybox"
install -m 755 "$here/initramfs/init" "$root/init"
install -m 644 "$here/initramfs/udhcpd.conf" "$root/etc/udhcpd.conf"
/opt/homebrew/opt/llvm/bin/clang --target=aarch64-linux-gnu -O2 -static -nostdlib -ffreestanding \
	-fno-stack-protector -fuse-ld=/opt/homebrew/opt/lld/bin/ld.lld \
	-o "$root/rb" "$here/tools/rb.c"
(cd "$root" && find . | LC_ALL=C sort | cpio -o -H newc -R 0:0 2>/dev/null | gzip -9) > "$out"
ls -l "$out"
