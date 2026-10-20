# OrangeFox for Xiaomi Pad 8 Pro (piano)

OrangeFox recovery device tree, based on TWRP 16.

## Build

- **GitHub:** run **OrangeFox Builder (piano)** from the Actions tab of the
  [main branch](../../tree/main).
- **Locally:** sync an OrangeFox source, then `bash sync-device.sh` from
  `main` and pick Recovery, then OrangeFox.

The recovery is refused by the bootloader if its ramdisk is too big, so
the build checks the size and fails instead of making a broken image.

## Good to know

Decrypting data needs a recovery built with the same security patch level
as the firmware on the tablet. Rebuild after every HyperOS firmware update.
