# Kernel for Xiaomi Pad 8 Pro (piano)

Prebuilt kernel, device tree blobs and kernel modules matching stock HyperOS.

Goes in `device/xiaomi/piano-kernel`. The sync script on the [main branch](../../tree/main) checks
it out and prepares it for you.

## Android 16 and Android 17 in one branch

| Folder | Used for |
| --- | --- |
| `versions/android17` | Android 17 builds (from HyperOS 4 China) |
| `versions/android16` | Android 16 builds (from HyperOS 3 Global) |

After syncing, `select-version.py 16` or `17` copies the matching set into
place. The copied files are ignored by Git. Edit files under `versions/`,
never the copied ones. The two sets are never mixed.

## What is inside each version

| Path | What |
| --- | --- |
| `kernel` | Kernel image |
| `dtb`, `dtbo.img` | Device tree blobs |
| `vendor_dlkm`, `system_dlkm`, `vendor_ramdisk` | Kernel modules |
| `kernel-headers` | Headers for building HALs |
| `tools/build-gki.sh` | Rebuild the kernel from source |

## KernelSU Next

`tools/build-gki.sh` can rebuild the kernel with KernelSU Next v3.4.2 added
(`integrate-ksunext.py`). It refuses to run on unexpected kernel source.
The prebuilt kernel here does not include KernelSU yet. A rebuilt kernel
must be committed and tested on the tablet first.
