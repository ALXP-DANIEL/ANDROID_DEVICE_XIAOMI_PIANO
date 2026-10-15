# Building

## Sources

```bash
mkdir -p ~/android/twrp-16 && cd ~/android/twrp-16
repo init -u https://github.com/TWRP-Test/platform_manifest_twrp_aosp.git -b twrp-16.0 --depth=1
repo sync -c -j8 --no-clone-bundle --no-tags
git clone -b twrp-16.0 https://github.com/ALXP-DANIEL/android_device_xiaomi_piano.git device/xiaomi/piano
```

## Source patches

The tree needs changes outside `device/xiaomi/piano`. Apply each patch in
[`patches/`](../patches/) to the matching checkout, in number order:

```bash
for d in bootable_recovery system_vold system_security; do
  repo_dir=$(echo "$d" | sed 's|_|/|')   # bootable_recovery -> bootable/recovery
  for p in device/xiaomi/piano/patches/$d/*.patch; do
    git -C "$repo_dir" apply "$(pwd)/$p"
  done
done
```

What each patch does is described in [`patches/README.md`](../patches/README.md).

## Signing key

The recovery image is AVB-signed. Provide your own RSA-4096 key; it is not part
of this repository and must never be committed:

```bash
mkdir -p device/xiaomi/piano/security
openssl genrsa -out device/xiaomi/piano/security/recovery_avb.pem 4096
```

## Build

```bash
source build/envsetup.sh
export ALLOW_MISSING_DEPENDENCIES=true
lunch twrp_piano-bp2a-eng
mka recoveryimage -j6
```

Output: `out/target/product/piano/recovery.img` (padded to 104857600 bytes).

Notes:

- Do not set `PLATFORM_SECURITY_PATCH` in the environment; it breaks `lunch`.
  The values in `BoardConfig.mk` are only a fallback — the installed ROM's
  values are applied at boot.
- On a machine with about 16-20 GB RAM, keep `-j6` or lower. Soong can crash
  intermittently under memory pressure; re-running the build succeeds.
- Keep the ramdisk compression as LZ4. gzip and zstd bootloop on this device.

## Verify

```bash
sha256sum out/target/product/piano/recovery.img
bash device/xiaomi/piano/tools/piano16-preflash-audit.sh out/target/product/piano/recovery.img
```
