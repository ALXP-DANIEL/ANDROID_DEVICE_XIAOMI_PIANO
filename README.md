# Xiaomi Pad 8 Pro (piano)

Android device trees for the Xiaomi Pad 8 Pro (SM8750).

## Branches

| Android 17 (active) | Android 16 (maintenance) | Purpose |
| --- | --- | --- |
| `lineage-24.0` | `lineage-23.2` | LineageOS device tree |
| `device-common` | `device-common` | Common device source, version selected automatically |
| `vendor` | `vendor` | Device vendor blobs, version selected automatically |
| `vendor-common` | `vendor-common` | Automatically stages stock common vendor files by Android version |
| `kernel` | `kernel` | GKI kernel and matching stock modules, version selected automatically |
| `camera` | `camera` | Shared MiuiCamera tree with Android-specific stock binaries |
| `pianopart` | `pianopart` | Shared latest PianoParts app with ROM-specific Settings placement |
| `notes` | `notes` | Shared Xiaomi Notes package without opt-in |
| `twrp` | `twrp` | TWRP recovery (shared) |
| `ofox` | `ofox` | OrangeFox recovery (shared) |
| `uefi` | `uefi` | UEFI firmware, boot menu and Visor (shared) |

The four hardware branches retain separate Android 16 and Android 17 file sets
under `versions/`. The sync script stages only the matching set at the usual
Android source paths. No separate package selection or flag is required.

## Sync

Run from the root of an existing ROM or recovery source checkout:

```sh
curl -fsSLO https://raw.githubusercontent.com/ALXP-DANIEL/android_device_xiaomi_piano/main/sync-device.sh
bash sync-device.sh
```

The script first asks for Android 17 ROM, Android 16 ROM or Recovery.
Android 17 then offers LineageOS 24.0 and Evolution X 12.3; Android 16
offers LineageOS 23.2. Recovery separately offers TWRP or OrangeFox.
For ROM trees, the script syncs all eight required repositories automatically:
the ROM-specific device tree, common device, device vendor, common vendor,
kernel, XiaomiParts, Camera and Notes. It stages the matching Android 16/17
hardware package set, Camera binaries and ROM-specific XiaomiParts Settings
placement without an opt-in. Recovery uses `twrp` or `ofox` separately.

The original numbered hardware branches were retired after the exact commits
were preserved in GitHub tags named `archive/pre-universal/<old-branch>`.
These tags remain available as rollback points if complete ROM builds or
tablet hardware testing reveal a compatibility problem.
