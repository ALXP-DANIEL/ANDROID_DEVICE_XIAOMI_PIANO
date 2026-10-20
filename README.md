# Xiaomi Pad 8 Pro unified kernel tree

One branch supports both Android 16 and Android 17. **Do not mix**
firmware, kernel modules, library imports or vendor binaries.

The versioned directories preserve the original files from:

- Android 16: `kernel-16` at `c87f16e82818`
- Android 17: `kernel-17` at `fe3f33229223`

`sync-device.sh` runs `python3 select-version.py 16` or `17`
after syncing. The selector stages the matching files at the
original build paths and checks for collisions. The generated
root files are ignored by Git; sources under `versions/` are
immutable. Android.bp/Android.mk are stored as `.in` to avoid
duplicate Soong/Make modules in the inactive version.

A complete ROM build and device smoke tests remain necessary.
