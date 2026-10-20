# Xiaomi Camera for Xiaomi Pad 8 Pro (piano)

The stock Xiaomi Camera app and its libraries, unchanged.

Goes in `vendor/xiaomi/piano-miuicamera`. The sync script on the
[main branch](../../tree/main) checks it out and prepares it for you.

## Android 16 and Android 17 in one branch

| Folder | Used for |
| --- | --- |
| `versions/android17` | Android 17 builds (from HyperOS 4 China) |
| `versions/android16` | Android 16 builds (from HyperOS 3 Global) |

After syncing, `select-camera.py 16` or `17` writes the matching
`Android.bp`. `miuicamera.mk` stops the build if it does not match the
Android version being built. The two sets are never mixed.
