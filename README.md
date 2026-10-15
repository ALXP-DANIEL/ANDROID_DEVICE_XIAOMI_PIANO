# Xiaomi Pad 8 Pro (piano)

Android device trees for the Xiaomi Pad 8 Pro (SM8750).

## Branches

| Android 17 (active) | Android 16 (maintenance) | Purpose |
| --- | --- | --- |
| `lineage-24.0` | `lineage-23.2` | LineageOS device tree |
| `device-common` | `device-common` | Common device source, version selected automatically |
| `vendor` | `vendor` | Device vendor blobs, version selected automatically |
| `vendor-common` | `vendor-common` | Automatically stages stock common vendor files by Android version |
| `kernel` | `kernel` | GKI kernel and matching stock modules, version selected automatically |
| `camera` | `camera` | Shared MiuiCamera tree with Android-specific stock binaries |
| `pianopart` | `pianopart` | Shared latest PianoParts app with ROM-specific Settings placement |
| `notes` | `notes` | Shared Xiaomi Notes package without opt-in |
| `twrp` | `twrp` | TWRP recovery (shared) |
| `ofox` | `ofox` | OrangeFox recovery (shared) |
| `uefi` | `uefi` | UEFI firmware, boot menu and Visor (shared) |

The four hardware branches retain separate Android 16 and Android 17 file sets
under `versions/`. The sync script stages only the matching set at the usual
Android source paths. No separate package selection or flag is required.

## Sync

Run from the root of an existing ROM or recovery source checkout:

```sh
curl -fsSLO https://raw.githubusercontent.com/ALXP-DANIEL/android_device_xiaomi_piano/main/sync-device.sh
bash sync-device.sh
```

The script first asks for Android 17 ROM, Android 16 ROM or Recovery.
Android 17 then offers LineageOS 24.0 and Evolution X 12.3; Android 16
offers LineageOS 23.2. Recovery separately offers TWRP or OrangeFox.
For ROM trees, the script syncs all eight required repositories automatically:
the ROM-specific device tree, common device, device vendor, common vendor,
kernel, XiaomiParts, Camera and Notes. It stages the matching Android 16/17
hardware package set, Camera binaries and ROM-specific XiaomiParts Settings
placement without an opt-in. Recovery uses `twrp` or `ofox` separately.

The original numbered hardware branches were retired after the exact commits
were preserved in GitHub tags named `archive/pre-universal/<old-branch>`.
These tags remain available as rollback points if complete ROM builds or
tablet hardware testing reveal a compatibility problem.

## Refresh and build on the builder

The generated `.repo/local_manifests/piano.xml` is kept up to date automatically.
Re-running the sync script does **not** require selecting every branch or
answering an existing-manifest replacement prompt. If the manifest changes, its
previous contents are backed up first. The script refuses to sync tracked local
edits rather than silently overwriting them. Unrelated local manifests remain
untouched.

For a non-interactive Android 17 EvoX update, run from the ROM root:

```sh
bash sync-device.sh --android 17 --rom evox --yes
```

`repo sync` is the equivalent of `git pull` for these `repo`-managed, detached
Git checkouts. It updates **all eight** selected Git branches, then stages the
matching hardware files and Xiaomi apps. Do **not** run `git pull` directly
inside one of the detached device directories.

`build-piano.sh` is the matching build launcher. Copy the published main-branch
script to the builder once. Every subsequent execution fetches or fast-forward
pulls the latest `main` build tools into
`~/android/piano-shared/piano-tools`, runs their `sync-device.sh`, applies the
selected ROM's compatibility patches and builds with the existing external AVB
key. It never fetches or checks out retired numbered hardware branches.

```sh
# Bootstrap from GitHub on the builder (once):
curl -fsSL https://raw.githubusercontent.com/ALXP-DANIEL/android_device_xiaomi_piano/main/build-piano.sh \
    -o ~/android/piano-shared/build-piano.sh

# Refresh all eight trees, without building:
bash ~/android/piano-shared/build-piano.sh --sync-only

# Refresh and build an Android 17 Evolution X OTA:
bash ~/android/piano-shared/build-piano.sh
```

Defaults: `PIANO_ANDROID=17`, `PIANO_ROM=evox`, `BUILD_VARIANT=userdebug`,
`BUILD_JOBS=2`, `BUILD_TARGET=evolution` for EvoX (`bacon` for LineageOS). Override `ANDROID_ROOT` and
`PIANO_AVB_KEY_PATH` if the source directory or existing external signing key
differs. Android 16 supports `PIANO_ANDROID=16 PIANO_ROM=lineage`; Android 17
LineageOS uses `PIANO_ANDROID=17 PIANO_ROM=lineage`. The launcher does not
create new signing keys, alter installed ROMs or flash the device.
