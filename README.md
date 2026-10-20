# TWRP for Xiaomi Pad 8 Pro (piano)

TWRP 16 recovery device tree.

## Build

- **GitHub:** run **TWRP Builder (piano)** from the Actions tab of the
  [main branch](../../tree/main).
- **Locally:** sync a TWRP 16 source, then `bash sync-device.sh` from
  `main` and pick Recovery, then TWRP.

The recovery is refused by the bootloader if its ramdisk is too big, so
the build checks the size and fails instead of making a broken image.

## Good to know

Decrypting data needs a recovery built with the same security patch level
as the firmware on the tablet. Rebuild after every HyperOS firmware update.
