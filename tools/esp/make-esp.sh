#!/usr/bin/env bash
# SPDX-License-Identifier: BSD-2-Clause-Patent
# Build the ESP contents: Visor as the boot manager, plus the UEFI Shell.
#
#   tools/esp/make-esp.sh OUT_DIR WALLPAPER...
#
# Each WALLPAPER (any image, cropped to the 1600x1068 UEFI screen with sips)
# becomes a Visor theme named after the file. The first is the default; to
# swap, set theme= in \EFI\visor\boot.conf to another name, or to random or
# cycle. A new wallpaper can be added on the ESP the same way by hand.
# Copy OUT_DIR/EFI and OUT_DIR/loader to the root of the esp partition, which
# must be FAT32 with 4 KiB clusters (mkfs.fat -F 32 -S 4096 -s 1): EDK2's FAT
# driver rejects the stock format, which has too few clusters for FAT32.
set -euo pipefail
cd "$(dirname "$0")/../.."

VISOR_URL=https://github.com/IO-ZetZor/Visor-BootManager
VISOR_COMMIT=71a05b5945e37903cfeeaf69c76220fdf5861ac9   # v1.5.7
GNU_EFI_URL=https://github.com/ncroxon/gnu-efi
GNU_EFI_TAG=3.0.18
# ext4/btrfs drivers so Visor can find kernels in Linux root partitions (GPL).
REFIND_ZIP=https://sourceforge.net/projects/refind/files/0.14.2/refind-bin-0.14.2.zip/download
REFIND_SHA256=410c7828c4fec2f2179bd956073522415831d27c00416381b8f71153c190a311

out=$1
shift
[ $# -ge 1 ] || { echo "usage: make-esp.sh OUT_DIR WALLPAPER..."; exit 1; }
src=deps/visor
shell=deps/Mu-Silicium/Build/pianoPkg/RELEASE_CLANGPDB/AARCH64/Shell.efi

[ -f "$shell" ] || { echo "make-esp: build the firmware first ($shell)"; exit 1; }

if [ ! -d "$src" ]; then
	git clone -q "$VISOR_URL" "$src"
	git -C "$src" checkout -q "$VISOR_COMMIT"
	git clone -q --depth 1 --branch "$GNU_EFI_TAG" "$GNU_EFI_URL" "$src/gnu-efi-src"
fi

if [ ! -d deps/refind-bin-0.14.2 ]; then
	curl -sL -o deps/refind.zip "$REFIND_ZIP"
	echo "$REFIND_SHA256  deps/refind.zip" | shasum -a 256 -c - >/dev/null
	unzip -q -o deps/refind.zip -d deps
fi

docker run --rm -v "$PWD/$src:/w" -w /w piano-uefi-build sh -euc '
	make -C gnu-efi-src ARCH=aarch64 CROSS_COMPILE=aarch64-linux-gnu- >/dev/null
	cp gnu-efi-src/aarch64/lib/libefi.a gnu-efi-src/aarch64/gnuefi/
	make ARCH=aarch64 CRT0=gnu-efi-src/aarch64/gnuefi/crt0-efi-aarch64.o \
		LDS=gnu-efi-src/gnuefi/elf_aarch64_efi.lds >/dev/null'

rm -rf "$out"
mkdir -p "$out/EFI/BOOT" "$out/EFI/visor/icons" "$out/EFI/visor/backgrounds" "$out/EFI/visor/themes" \
	"$out/EFI/visor/drivers" "$out/EFI/tools" "$out/loader/entries"
cp "$src/visor_aa64.efi" "$out/EFI/BOOT/BOOTAA64.EFI"
cp "$src"/assets/icons/*.png "$out/EFI/visor/icons/"
cp deps/refind-bin-0.14.2/refind/drivers_aa64/{ext4,btrfs}_aa64.efi "$out/EFI/visor/drivers/"
cp tools/esp/boot.conf "$out/EFI/visor/boot.conf"
cp tools/esp/uefi-shell.conf "$out/loader/entries/"
cp "$shell" "$out/EFI/tools/shellaa64.efi"

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
first=
for wallpaper in "$@"; do
	name=$(basename "${wallpaper%.*}" | tr -c 'A-Za-z0-9_-\n' '-')
	w=$(sips -g pixelWidth "$wallpaper" | awk '/pixelWidth/ {print $2}')
	h=$(sips -g pixelHeight "$wallpaper" | awk '/pixelHeight/ {print $2}')
	if [ $((w * 1068)) -gt $((h * 1600)) ]; then
		sips -s format png --resampleHeightWidth 1068 $(((w * 1068 + h - 1) / h)) "$wallpaper" --out "$tmp/s.png" >/dev/null
	else
		sips -s format png --resampleHeightWidth $(((h * 1600 + w - 1) / w)) 1600 "$wallpaper" --out "$tmp/s.png" >/dev/null
	fi
	sips --cropToHeightWidth 1068 1600 "$tmp/s.png" --out "$out/EFI/visor/backgrounds/$name.png" >/dev/null
	printf 'background=\\EFI\\visor\\backgrounds\\%s.png\n' "$name" > "$out/EFI/visor/themes/$name.conf"
	first=${first:-$name}
done
sed -i '' "s/^theme=.*/theme=$first/" "$out/EFI/visor/boot.conf"
echo "$out (theme $first)"
