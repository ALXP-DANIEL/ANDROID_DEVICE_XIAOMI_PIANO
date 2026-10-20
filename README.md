# Common vendor files for Xiaomi Pad 8 Pro (piano)

Stock files shared by SM8750 devices, taken from HyperOS.

Goes in `vendor/xiaomi/sm8750-common`. The sync script on the [main branch](../../tree/main) checks
it out and prepares it for you.

## Android 16 and Android 17 in one branch

| Folder | Used for |
| --- | --- |
| `versions/android17` | Android 17 builds (from HyperOS 4 China) |
| `versions/android16` | Android 16 builds (from HyperOS 3 Global) |

After syncing, `select-version.py 16` or `17` copies the matching set into
place. The copied files are ignored by Git. Edit files under `versions/`,
never the copied ones. The two sets are never mixed.
