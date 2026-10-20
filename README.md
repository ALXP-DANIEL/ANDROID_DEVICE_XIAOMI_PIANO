# Evolution X 12.3 for Xiaomi Pad 8 Pro (piano)

Device tree for Evolution X 12.3 (Android 17).
Stock files come from HyperOS 4 China `OS4.0.0.44.XPYCNXM`.

Goes in `device/xiaomi/piano`. The sync script on the
[main branch](../../tree/main) adds every other tree it needs:

```sh
bash sync-device.sh --android 17 --rom evox --yes
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
