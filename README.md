# Xiaomi Pad 8 Pro (piano) UEFI

UEFI firmware and boot menu for the Xiaomi Pad 8 Pro (SM8750), loaded from
the Android boot image. It lets the tablet boot Android, a UEFI boot manager
(Visor) and, later, Linux, without replacing anything Xiaomi ships.

> **Work in progress.** It boots and is usable from the touch screen, but
> some things below do not work yet. Use it only with an unlocked
> bootloader and a way back (fastboot, a recovery).

## How it boots

```
ABL ──► boot.img (Android kernel + piano menu + UEFI)
          │
          ├─ Android      the kernel starts exactly as before
          ├─ Recovery / Fastboot
          └─ UEFI ──► Visor on the ESP ──► Linux, UEFI Shell, …
```

The piano menu (`selector/`) is placed after the kernel in any header v3/v4
`boot.img` whose kernel is a raw arm64 GKI Image (EvoX, LineageOS, HyperOS).
The kernel's first branch is redirected to the menu; picking Android returns
to the kernel untouched. Picking UEFI starts the Mu-Silicium firmware built
from `uefi/pianoPkg`, which runs Visor from the ESP.

## Status

| Feature | State |
|---|---|
| Piano menu before Android (volume/power keys) | Works |
| UEFI display (1600×1068, scaled) | Works |
| Visor boot manager on the ESP | Works |
| Volume and power keys in UEFI | Works: volume moves, power selects |
| Touch screen in UEFI | Works: swipe moves one step, double tap selects |
| Reboot / Shut down from UEFI | Works |
| Debug fastboot in UEFI (`piano-uefi`) | Works: screenshot, log, memory dump, run an EFI app |
| Android entry inside Visor | Not yet |
| Linux (Arch) from Visor | Not yet |
| Installer ZIP for recovery | Not yet |

### Touch screen

The NT36532 touch controller has no flash and reports no coordinates: the
host loads its firmware at every boot and turns its raw 60×40 sensor frames
into a touch point. Its serial engine (QUP1 SE2) runs GSI-only firmware, so
transfers go over GPI DMA through Qualcomm's GpiDxe. Details are in
`uefi/pianoPkg/Library/DeviceBootManagerLib/PianoTouch.c`.

## Build

Needs Docker, plus Homebrew `llvm` and `lld` for the menu.

```sh
tools/setup-deps.sh                       # Mu-Silicium at the tested commit
docker build -f docker/Dockerfile -t piano-uefi-build .
docker run --rm -v "$PWD:/p" -w /p/deps/Mu-Silicium piano-uefi-build \
	python3 build_uefi.py -d piano -r RELEASE
make -C selector
python3 tools/pack.py boot.img out/selector.bin \
	--uefi deps/Mu-Silicium/Mu-piano.img -o boot-uefi.img
```

`boot.img` is the boot image of the Android build you run. The ESP with
Visor and your own wallpaper is made by `tools/esp/make-esp.sh`.

## Test

Boot it once without flashing:

```sh
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

`oem log` holds what was printed on the screen. `oem dump` reads memory;
`81CE4000 10000` is the stock bootloader's boot log. While an app started
with `boot` runs, the service pauses.

## Layout

| Path | What |
|---|---|
| `selector/` | Piano menu, runs before the kernel |
| `uefi/pianoPkg/` | Mu-Silicium device package: drivers, boot manager, touch |
| `uefi/Binaries/piano/` | Qualcomm UEFI drivers extracted from the stock firmware |
| `uefi/pianoPkg/Firmware/` | Stock touch controller firmware (BOE and CSOT panels) |
| `uefi/patches/` | Changes to Mu-Silicium's Mu_Basecore |
| `tools/` | Packing, driver extraction and patching, ESP builder |
| `linux/` | Early Linux bring-up (initramfs, rootfs) |

## Credits

- [Project Silicium](https://github.com/Project-Silicium/Mu-Silicium): the
  UEFI framework, and the OnePlus 13 (`dodge`) package this one starts from.
- [Project SunUEFI](https://github.com/slwyts/Project-SunUEFI): piano memory
  and configuration maps (BSD-2-Clause-Patent).
- [blu-sharky/debian-piano](https://github.com/blu-sharky/debian-piano) and
  [yzddmr6/xiaomipad-8pro-mainline](https://github.com/yzddmr6/xiaomipad-8pro-mainline):
  the QUP SMMU routing and THP frame layout, from their Linux bring-up.
- [Visor](https://github.com/IO-ZetZor/Visor-BootManager): the boot manager.

## License

Our code is BSD-2-Clause-Patent, except `selector/keys.c`, which is
GPL-2.0-only because its register layout follows Linux's SPMI PMIC arbiter
driver. The Qualcomm drivers in `uefi/Binaries/piano` and the touch firmware
in `uefi/pianoPkg/Firmware` are proprietary files from the device's own
stock firmware, included so the image can be built.
