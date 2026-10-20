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

## KernelSU Next

Both versioned `tools/build-gki.sh` recipes integrate KernelSU Next v3.4.2
(`4c706b5938665866ebe16944a481a7b91e3396af`) into GKI 6.6.143 before
building. `integrate-ksunext.py` checks the exact GKI revision and refuses
unrelated source edits. KMI checks, Xiaomi vendor modules, module signatures,
SELinux, and official manager certificate checks remain enabled.

Run the versioned recipe directly from this Git checkout on Linux, with
`fsck.erofs` on PATH. It needs the complete kernel build workspace, not the
Xiaomi OSS source checkout. Back up the workspace before its first integration.
Re-running the integration is permitted only when its existing source matches.

The integration recipe does not by itself replace the committed kernel or
system modules. Those prebuilts must be rebuilt, checked together, and committed
before ROM builds include KernelSU. Until that binary update lands, existing ROM
builds continue using the earlier kernel. Physical boot/root verification is
required before calling a newly built kernel supported.
