# Installation

Requires an unlocked bootloader. Download the image from the
[releases page](https://github.com/ALXP-DANIEL/android_device_xiaomi_piano/releases/latest)
and check its SHA-256 against the value listed there.

```bash
# Reboot to fastboot: hold Volume Down + Power
fastboot flash recovery twrp-3.7.1_16-0-ALXP-piano.img    # active slot only
fastboot flash recovery_ab twrp-3.7.1_16-0-ALXP-piano.img # or both slots
fastboot reboot recovery
```

Enter your lock screen PIN when TWRP asks to decrypt.

Flashing both slots means switching slots — from the TWRP Reboot menu or
through an OTA — still lands in TWRP rather than stock recovery.

## After an OTA

- The OTA writes stock recovery to the updated slot. Flash TWRP again.
- Unlock the tablet once in Android before decrypting in TWRP.
- Install OTAs from TWRP, not stock recovery sideload (broken on this device).

Only flash the recovery partition. Do not flash boot, init_boot, vendor_boot,
vbmeta, dtbo, super, userdata or metadata with this image.
