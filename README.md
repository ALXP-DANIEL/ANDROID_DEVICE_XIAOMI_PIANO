# Xiaomi Pad 8 Pro (piano)

Android device trees for the Xiaomi Pad 8 Pro (SM8750).

## Branches

| Branch | Purpose |
| --- | --- |
| `lineage-23.2` | LineageOS device tree |
| `device-common-16` | SM8750 common device tree |
| `vendor-16` | Device vendor files |
| `vendor-common-16` | SM8750 common vendor files |
| `kernel-16` | Prebuilt kernel and modules |
| `miuicamera-16` | MiuiCamera |
| `twrp-16` | TWRP recovery |
| `ofox-16` | OrangeFox recovery |

The shared Android 16 branches are intended to be reused by compatible ROMs.

## Sync

Run from the root of an existing ROM or recovery source checkout:

```sh
curl -fsSLO https://raw.githubusercontent.com/ALXP-DANIEL/android_device_xiaomi_piano/main/sync-device.sh
bash sync-device.sh
```

The script syncs device trees only.
