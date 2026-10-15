# Xiaomi Pad 8 Pro stock Camera

One `camera` branch provides both HyperOS 3 Global (Android 16) and HyperOS 4
China (Android 17) camera imports. Android native camera libraries and APKs
are retained per version under `versions/android16` and `versions/android17`.
They are never mixed across Android versions.

After syncing this repo to `vendor/xiaomi/piano-miuicamera`, the source sync
script automatically runs `python3 select-camera.py 16` or `17` and stages
only the matching Soong configuration as an ignored `Android.bp` file.
`miuicamera.mk` verifies the staged version against the platform SDK.
There is no optional user flag; selecting the ROM and Android version is enough.

The original signed camera APKs and libraries remain unchanged in the branch;
Android build compatibility and camera hardware functionality still require
checking on the chosen ROM.
