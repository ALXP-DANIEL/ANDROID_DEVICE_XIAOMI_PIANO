# Xiaomi Pad 8 Pro (piano)

Android device trees for the Xiaomi Pad 8 Pro (SM8750).

## Branches

| Android 17 (active) | Android 16 (maintenance) | Purpose |
| --- | --- | --- |
| `lineage-24.0` | `lineage-23.2` | LineageOS device tree |
| `device-common-17` | `device-common-16` | SM8750 common device tree |
| `vendor-17` | `vendor-16` | Device vendor files |
| `vendor-common-17` | `vendor-common-16` | SM8750 common vendor files |
| `kernel-17` | `kernel-16` | GKI kernel built from source, with the stock modules |
| `miuicamera-17` | `miuicamera-16` | MiuiCamera matching each stock generation |
| `twrp` | `twrp` | TWRP recovery (shared) |
| `ofox` | `ofox` | OrangeFox recovery (shared) |

Android 16 branches only get fixes and monthly security updates. The shared
branches are intended to be reused by compatible ROMs.

## Sync

Run from the root of an existing ROM or recovery source checkout:

```sh
curl -fsSLO https://raw.githubusercontent.com/ALXP-DANIEL/android_device_xiaomi_piano/main/sync-device.sh
bash sync-device.sh
```

The script asks for the ROM or recovery, and asks for the Android version
for ROM trees. Recovery uses the shared `twrp` or `ofox` branch. It syncs
the device trees only.
