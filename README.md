# LineageOS 23.2 for Xiaomi Pad 8 Pro (piano)

Device tree for LineageOS 23.2 (Android 16).
Stock files come from HyperOS 3 Global (Android 16).

Goes in `device/xiaomi/piano`. The sync script on the
[main branch](../../tree/main) adds every other tree it needs:

```sh
bash sync-device.sh --android 16 --rom lineage --yes
```

## What is here

| Path | What |
| --- | --- |
| `BoardConfig*.mk`, `device.mk` | Build and partition setup |
| `lineage_piano.mk` | Product definition |
| `rootdir/` | Init scripts and fstab |
| `sepolicy/` | SELinux rules |
| `overlay/` | Settings and framework tweaks |
| `proprietary-files.txt` | List of stock files pulled from HyperOS |
| `patches/` | Small ROM source patches applied at build time |
