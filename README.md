# Xiaomi Pad 8 Pro (piano) UEFI

UEFI firmware and the Visor boot manager for the Xiaomi Pad 8 Pro (SM8750),
loaded from the Android boot image. Visor starts Android, Linux and other
UEFI systems, without replacing anything Xiaomi ships.

> **Work in progress.** Use it only with an unlocked bootloader and a way
> back (fastboot, a recovery).

## How it boots

```
ABL ──► boot.img (Android kernel + selector + UEFI)
          │
          ├─ nothing held   UEFI ──► Visor on the ESP ──► Android, Linux, …
          ├─ volume down    restart into fastboot
          └─ volume up      restart into recovery
```

The selector (`selector/`) is placed after the kernel in any header v3/v4
`boot.img` whose kernel is a raw arm64 GKI Image (EvoX, LineageOS, HyperOS).
The kernel's first branch is redirected to it. It reads the keys held
during the Mi logo and starts the Mu-Silicium firmware built from
`uefi/pianoPkg`, which runs Visor from the `esp` partition.

Android is an entry in Visor: `AndroidBoot.efi` leaves UEFI and starts the
kernel ABL already loaded, with no restart. A boot into recovery goes
straight to the kernel.

## Status

| Feature | State |
|---|---|
| Visor boot manager on the ESP | Works |
| Android from Visor, no restart | Works |
| Volume and power keys in Visor | Works: volume moves through entries and power icons, power selects |
| Fastboot / recovery by holding a volume key | Works |
| Reboot / Shut down from UEFI | Works |
| Installer, uninstall and clean uninstall zips | Built; making the ESP not yet tested on a tablet |
| Debug fastboot in UEFI (`piano-uefi`) | Works: screenshot, log, memory dump, run an EFI app |
| Linux (Arch) from Visor | Not yet |
| Touch screen in UEFI | Removed: its SMMU streams keep Android from starting from Visor |

## Install

From a run of the **UEFI Builder (piano)** action (see Build), download and
flash in OrangeFox or TWRP:

| Zip | What it does |
| --- | --- |
| `piano-uefi-N-installer.zip` | Installs the UEFI and Visor |
| `piano-uefi-N-uninstall.zip` | Removes the UEFI, keeps the ESP partition |
| `piano-uefi-N-uninstall-clean.zip` | Removes the UEFI and gives the ESP space back to data |

**Installer.** Puts the selector and UEFI into `boot_a` and `boot_b`, whatever
ROM they hold, and saves the old images in `/sdcard/piano-uefi/`. If the
tablet already has an ESP, it only updates Visor's files. If not, it makes a
512 MiB ESP by shrinking the data partition, without formatting it: data must
be decrypted in recovery first. Flash it again after every ROM update.

**Uninstall.** Puts the original boot images back. The ESP stays.

**Clean uninstall.** Needs two runs:

1. Flash it: boot images are restored, the ESP is backed up and removed,
   and the data partition grows back.
2. Reboot to recovery, decrypt data, flash the same zip again: the data
   filesystem grows to fill the space.

It only removes an ESP the installer made (it checks a receipt saved in
`/data/adb/piano-uefi/`). A factory reset deletes that receipt.

> Making and removing the ESP is new and not yet tested on a tablet.
> Back up your data first. Details: [docs/esp-installer.md](docs/esp-installer.md).

## Build

On GitHub, run the "UEFI Builder (piano)" action from the Actions tab of the
`main` branch. It asks for a wallpaper (a link, or files from
`tools/esp/wallpapers`), the color style taken from it, the entry selected
at start, the delay before it starts and a quiet boot, and uploads installer, uninstall and uninstall-clean
ZIPs.

Locally, with Docker and Homebrew `llvm` and `lld`:

```sh
tools/setup-deps.sh                       # Mu-Silicium at the tested commit
docker build -f docker/Dockerfile -t piano-uefi-build .
docker run --rm -v "$PWD:/p" -w /p/deps/Mu-Silicium piano-uefi-build \
	python3 build_uefi.py -d piano -r RELEASE
make -C selector
tools/inject/make-zip.sh deps/Mu-Silicium/Mu-piano.img piano-uefi.zip wallpaper.jpg
```

`tools/esp/make-esp.sh` builds the ESP on its own. A ROM can also carry the
UEFI in every boot image and OTA it makes: wrap its prebuilt kernel with
`tools/pack.py --kernel-only Image out/selector.bin --uefi deps/Mu-Silicium/Mu-piano.img -o Image`.

## Test

Boot a wrapped image once without flashing:

```sh
python3 tools/pack.py boot.img out/selector.bin \
	--uefi deps/Mu-Silicium/Mu-piano.img -o boot-uefi.img
fastboot boot boot-uefi.img
```

A normal reboot returns to the installed boot image. The image has no AVB
footer, so the bootloader must be unlocked.

### Debug fastboot

While UEFI runs, the tablet is also a fastboot device named `piano-uefi`,
served by Qualcomm's own USB driver. It is for debugging only: it never
writes storage, so `flash` and `erase` are refused. Always pass
`-s piano-uefi` so commands cannot reach the real bootloader.

```sh
fastboot -s piano-uefi oem screenshot && fastboot -s piano-uefi get_staged shot.bmp
fastboot -s piano-uefi oem log && fastboot -s piano-uefi get_staged log.txt
fastboot -s piano-uefi oem dump 81CE4000 10000 && fastboot -s piano-uefi get_staged xbl-log.bin
fastboot -s piano-uefi boot app.efi       # run an EFI app from RAM
fastboot -s piano-uefi reboot
```

`oem log` holds what was printed on the screen. A DEBUG build
(`build_uefi.py -d piano -r DEBUG`) also keeps every driver's debug output
in RAM instead of drawing it over the screen; read it with `oem debuglog`. `oem dump` reads memory;
`81CE4000 10000` is the stock bootloader's boot log. While an app started
with `boot` runs, the service pauses.

## Layout

| Path | What |
|---|---|
| `selector/` | Runs before the kernel: keys, then UEFI |
| `uefi/pianoPkg/` | Mu-Silicium device package: drivers, boot manager, AndroidBoot |
| `uefi/Binaries/piano/` | Qualcomm UEFI drivers extracted from the stock firmware |
| `uefi/patches/` | Changes to Mu-Silicium's Mu_Basecore |
| `tools/esp/` | ESP builder: Visor, its patches, icons and look |
| `tools/inject/` | Recovery installer ZIP and its on-device injector |
| `tools/` | Packing, driver extraction and patching |
| `linux/` | Early Linux bring-up (initramfs, rootfs) |

## Credits

- [Project Silicium](https://github.com/Project-Silicium/Mu-Silicium): the
  UEFI framework, and the OnePlus 13 (`dodge`) package this one starts from.
- [Project SunUEFI](https://github.com/slwyts/Project-SunUEFI): piano memory
  and configuration maps (BSD-2-Clause-Patent).
- [blu-sharky/debian-piano](https://github.com/blu-sharky/debian-piano) and
  [yzddmr6/xiaomipad-8pro-mainline](https://github.com/yzddmr6/xiaomipad-8pro-mainline):
  piano hardware notes from their Linux bring-up.
- [Visor](https://github.com/IO-ZetZor/Visor-BootManager): the boot manager.
- The Android robot is reproduced or modified from work created and shared
  by Google and used according to terms described in the Creative Commons
  3.0 Attribution License.

## License

Our code is BSD-2-Clause-Patent, except `selector/keys.c`, which is
GPL-2.0-only because its register layout follows Linux's SPMI PMIC arbiter
driver. The Qualcomm drivers in `uefi/Binaries/piano` are proprietary files
from the device's own stock firmware, included so the image can be built.
