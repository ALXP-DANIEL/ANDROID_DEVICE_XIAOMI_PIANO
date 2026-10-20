# Device tree for the Xiaomi Pad 8 Pro (piano)

Android 17 device tree for the Xiaomi Pad 8 Pro (`piano`, Snapdragon 8 Elite,
SM8750). Stock files come from HyperOS 4 China `OS4.0.0.44.XPYCNXM`; flash
that firmware before installing a build from this tree.

## Getting the sources

Add the [local manifest](https://github.com/xiaomi-pad-8-pro-development/local_manifests) to your ROM checkout:

```
git clone https://github.com/xiaomi-pad-8-pro-development/local_manifests -b seventeen .repo/local_manifests
repo sync
```

## Trees

| Path | Repository |
| --- | --- |
| `device/xiaomi/piano` | this repository |
| `device/xiaomi/sm8750-common` | [android_device_xiaomi_sm8750-common](https://github.com/xiaomi-pad-8-pro-development/android_device_xiaomi_sm8750-common) |
| `device/xiaomi/piano-kernel` | [android_device_xiaomi_piano-kernel](https://github.com/xiaomi-pad-8-pro-development/android_device_xiaomi_piano-kernel) |
| `vendor/xiaomi/piano` | [android_vendor_xiaomi_piano](https://github.com/xiaomi-pad-8-pro-development/android_vendor_xiaomi_piano) |
| `vendor/xiaomi/sm8750-common` | [android_vendor_xiaomi_sm8750-common](https://github.com/xiaomi-pad-8-pro-development/android_vendor_xiaomi_sm8750-common) |

## Building

```
source build/envsetup.sh
lunch lineage_piano-bp4a-userdebug
m bacon
```
