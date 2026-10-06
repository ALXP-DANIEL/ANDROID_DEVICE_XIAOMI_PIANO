# Known Limitations

- **The battery does not charge in TWRP.** The ADSP starts and USB/Type-C is
  detected, but the charger input limit stays 0, so the battery drains even
  while plugged in. `status=Charging` is misleading, and on this device a
  positive `current_now` means discharge.
- **After an OTA, unlock once in Android before decrypting in TWRP.** The
  Synthetic Password key stays bound to the old patch level until Android
  upgrades it; recovery deliberately never writes that key.
- **Stock recovery sideload does not work.** Install OTAs through TWRP.
- **An OTA installed from recovery removes the previous slot's logical
  partitions** (Virtual A/B cannot snapshot from recovery). If the slot switch
  ever fails, finish it with `fastboot set_active <slot>`.
- **HyperOS 4 / Android 17 is not supported.**
- Validated on HyperOS 3: China OS3.0.307 and OS3.0.308, Global OS3.0.303.
- The recovery uses the active slot's ROM kernel (`kernel_size=0`).
- Not yet validated: full backup/restore round trip, MTP, USB OTG, SD card.
