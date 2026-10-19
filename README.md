# Xiaomi Pad 8 Pro (piano)

Custom Android for the Xiaomi Pad 8 Pro (Snapdragon 8 Elite, SM8750).
This repository holds everything needed to build it: device trees, stock
vendor files, kernel, Xiaomi apps, recoveries and an optional UEFI boot menu.

| ROM | Android | State |
| --- | --- | --- |
| Evolution X 12.3 | 17 | Active, daily tested |
| LineageOS 24.0 | 17 | Active |
| LineageOS 23.2 | 16 | Maintenance |

Recoveries: TWRP and OrangeFox. Boot menu: [UEFI with Visor](../../tree/uefi).

## Install

You need an unlocked bootloader.

1. Flash a recovery built from the `twrp` or `ofox` branch.
2. In recovery, flash the ROM zip.
3. Coming from HyperOS or another ROM: format data once.
4. Reboot.

Updates: flash the new ROM zip in recovery, no wipe needed.

## What comes from stock HyperOS

These Xiaomi features are carried over and set up for AOSP:

- Xiaomi Pen and keyboard (pairing, pen settings, pressure, shortcuts)
- Xiaomi Camera
- Xiaomi Notes (offline, no Xiaomi account or cloud)
- Xiaomi Parts settings: tap to wake, reading mode, refresh rate and more
- Stock audio (Dolby), display tuning and battery care

## Branches

Every tree lives in this one repository, one branch each. The sync script
picks the right branches and the right Android version for you.

| Branch | What it is |
| --- | --- |
| `main` | This page, the sync and build scripts, GitHub build workflows |
| `evox-12.3` | Evolution X device tree (Android 17) |
| `lineage-24.0` | LineageOS device tree (Android 17) |
| `lineage-23.2` | LineageOS device tree (Android 16) |
| `device-common` | Settings shared by every SM8750 build |
| `vendor` | Stock files for this tablet, taken from HyperOS |
| `vendor-common` | Stock files shared by SM8750 devices |
| `kernel` | Prebuilt kernel and its modules |
| `pianopart` | Xiaomi Parts settings app, pen and keyboard support |
| `camera` | Stock Xiaomi Camera |
| `notes` | Stock Xiaomi Notes, made to work offline |
| `twrp` | TWRP recovery |
| `ofox` | OrangeFox recovery |
| `uefi` | UEFI firmware and the Visor boot menu |

The `device-common`, `vendor`, `vendor-common`, `kernel` and `camera`
branches hold both Android 16 and Android 17 files under `versions/`. Only
the set matching your build is used.

## Other code the build downloads

| Folder | From | Why |
| --- | --- | --- |
| `hardware/xiaomi` | [LineageOS/android_hardware_xiaomi](https://github.com/LineageOS/android_hardware_xiaomi) | LineageOS's shared code for Xiaomi devices (touch, fingerprint and similar). Branch `lineage-24.0` for Android 17, `lineage-23.2` for Android 16 |

## Build it yourself

From the top folder of your ROM source:

```sh
curl -fsSLO https://raw.githubusercontent.com/ALXP-DANIEL/android_device_xiaomi_piano/main/sync-device.sh
bash sync-device.sh
```

It asks which ROM or recovery you want, downloads the matching trees and
prepares them. Run it again any time to update. Then build as usual for
your ROM (`brunch piano` style, or `m evolution` for EvoX).

Without questions, for example Evolution X:

```sh
bash sync-device.sh --android 17 --rom evox --yes
```

Add `--alxpparts` (Android 17 only) for ALXP Features, the experimental,
opt-in AirPods switching app. It builds from source with the ROM and patches
`packages/modules/Bluetooth`; syncing without the option undoes that.

### Build server helper

`build-piano.sh` updates the trees and builds in one go:

```sh
curl -fsSL https://raw.githubusercontent.com/ALXP-DANIEL/android_device_xiaomi_piano/main/build-piano.sh \
    -o ~/android/piano-shared/build-piano.sh
bash ~/android/piano-shared/build-piano.sh              # update and build EvoX
bash ~/android/piano-shared/build-piano.sh --sync-only  # update only
```

Pick another ROM with `PIANO_ANDROID=16|17` and `PIANO_ROM=lineage|evox`.
It uses your existing signing keys and never flashes the tablet.

### GitHub Actions

From the Actions tab of `main`:

- **TWRP Builder** and **OrangeFox Builder** build the recoveries.
- **UEFI Builder** builds the UEFI installer, uninstall and clean uninstall zips.

## Notes for contributors

Use `repo sync` or the sync script, not `git pull`, inside the synced
folders.
