# Xiaomi Pad 8 Pro Kernel

Kernel and modules for the Xiaomi Pad 8 Pro.

Path:

```text
device/xiaomi/piano-kernel
```

| Part | Source |
|---|---|
| `kernel`, `system_dlkm` | Built from Google's GKI `common-android15-6.6` (6.6.143) with `tools/build-gki.sh` |
| `vendor_dlkm`, `vendor_ramdisk`, `dtb`, `dtbo.img` | Stock HyperOS 3; they load on the frozen GKI KMI |

To update the kernel, change the pinned commit in `tools/build-gki.sh`, run
it on the builder and commit the result.

Reusable with compatible Android 16 ROMs.

See the [main branch](../../tree/main) for the other shared trees.
