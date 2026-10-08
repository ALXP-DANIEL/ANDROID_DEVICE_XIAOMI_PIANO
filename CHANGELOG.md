# TWRP for Xiaomi Pad 8 Pro (piano)

## Unreleased

- Format Data no longer fails with "Unable to unmap dynamic partitions". The
  new `twrp-pre-unmap` hook (patch 0006) stops the secure stack and
  `batterysecret`, which run from the installed ROM's vendor, and unmounts the
  stock vendor and odm views before TWRP destroys the logical partitions.
  Touch is unaffected: `touch_report` runs from the ramdisk.
- The time zone lookup mounts each partition in its own directory and detaches
  it afterwards. A leftover `/tmp/tz_system` mount also blocked Format Data.
- Wiping data after installing a ROM that has not booted yet now shows a
  warning. AOSP cancels that update during the wipe and marks its slot
  unbootable, so the device boots the previous slot.
- Not yet tested on the device.

## 3.7.1_16-1(ALXP) — Android 16/17 compatibility candidate

- Selects the matching HyperOS 3 or HyperOS 4 Weaver service and touch modules.
- Starts Weaver before the first decryption attempt. Android 17 PIN decryption
  passed after booting recovery from both slots.
- Fixes sideload USB enumeration and reduces the Mount screen to user-facing
  partitions.
- Adds recovery backup and verified restore around successful A/B OTAs. The
  slot-fix candidate passed an end-to-end OTA preservation test on the device.
- The Android 17 OS4.0.0.42 full OTA completed successfully, booted from slot B,
  and decrypted in TWRP. Recovery B was restored manually after that test.
- The slot-fix candidate was subsequently validated on Android 16 and 17,
  including automatic recovery restoration to both A/B slots.
- The tested slot-fix image has SHA-256
  `1da418829c2142e9f5acf41e9459ea3e449cf940b0801a5faa220e400a2b9563`.
  Its embedded version is `3.7.1_16-0(ALXP)`; `-1` remains the release target.

## Android 16/17 compatibility development (`twrp-16.0`)

The device tree now packages both HyperOS 3 and HyperOS 4 Weaver HALs.
`piano-prepdecrypt.sh` reads the installed ROM release and patch levels, starts
the matching Weaver service, then starts KeyMint. The same TWRP 16 recovery
core is used on both Android versions. The service start is retried because
the recovery briefly remounts `/vendor` during startup; a property-only
Weaver start missed the first decrypt request on OS4.0.0.42.

An earlier dual-Weaver image decrypted Android 16 OS3.0.307 and OS3.0.308 and
Android 17 OS4.0.0.42. Normal ADB and sideload enumeration worked on both
versions after removing duplicate sideload USB init actions. The latest
Weaver startup change and the recovery-core OTA preservation change are still
under device validation. The Android 16/17 touch behavior also needs a
separate check before calling this a universal release.

## HyperOS 4 / Android 17 compatibility candidate (`twrp-17.0`)

This device-tree branch still builds on the TWRP 16 recovery core. It is not
an upstream TWRP 17 release. The candidate image SHA-256 is
`2394f4741c92c079956c253b95b26689af22a1c9431ea93e166f0dbe7c26784a`.

HyperOS 4 changed the kernel ABI used by the touch modules and removed the
ODM `libmi_weaver.so` needed by the earlier Weaver service. This branch uses
the touch modules and Weaver service from OS4.0.0.42.XPYCNXM, plus a matching
patch-level fallback. The pre-decryption script still reads the installed ROM
version and patch levels before KeyMint starts.

On OS4.0.0.42.XPYCNXM, slot B, the candidate passed five clean recovery boots
with PIN decryption, wrong PIN then correct PIN, touch, `/product`
mount/unmount, Android boot after recovery, and recovery re-entry from Android.
The battery charge counter rose on Mac USB while in recovery. The HyperOS 3
`twrp-16.0` release remains the tested Android 16 image. A combined Android
16/17 image needs a compatibility selector and fresh Android 16 device tests.

## 3.7.1_16-0(ALXP) — universal HyperOS 3

Image SHA-256 `3f5a9c3be95a7431f31a524c0c9788e183af8cb568e1947622b84e53ba0eb6ea`.
Tested on HyperOS 3 CN OS3.0.307 and OS3.0.308 (fastboot and OTA) and Global
OS3.0.303. HyperOS 4 is not supported.

### One image for every HyperOS 3 build

KeyMint caches the OS version and the system and vendor patch levels when it
starts, and this TEE refuses to upgrade a key blob to a different level in
either direction. A build-time value therefore tied one image to one firmware:
307 is 2026-07-01 / 2026-02-01, 308 is 2026-08-01 / 2026-08-01.

`piano-prepdecrypt.sh` now reads the installed system's and vendor's values
from the active slot, applies them with `resetprop`, and only then starts
KeyMint. KeyMint is the only secure service that reads them, so it alone waits
for the script; qseecomd, Mink, SSGTZD, Gatekeeper and Weaver start as before.
The start is retried, because a single `ctl.start` can land while TWRP briefly
remounts `/vendor`. The `BoardConfig.mk` values remain as the fallback.

`2099-12-31`, the common fallback in other trees, breaks the Synthetic Password
key on this device and is not used.

### Secure stack reliability

A 30-boot loop exposed two faults that showed only intermittently:

- qseecomd crashed (SIGSEGV in its own retry path) on about one boot in ten when
  the secure world asked for a file on persist before persist was mounted.
  persist is now mounted read-only in `on post-fs`, before any secure service.
- TWRP unmounted persist again after reading the clock offset from it, leaving
  the Weaver trusted app unable to open (`TEEC_OpenSession 0xFFFF0007`).
  `bootable_recovery/0002` makes TWRP leave a mount it did not create.

Result: 30/30 clean recovery boots with every release gate passing.
The charging update passed another 5/5 clean recovery boots on slot A,
including a wrong PIN followed by the correct PIN.

### OTA install from TWRP

An OTA wrote its whole payload to the inactive slot and then failed with
`kPostinstallRunnerError` at the slot switch. The boot control HAL is enforcing
and must rewrite the partition table of every UFS LUN, but only `sda` and `sde`
carried the label it may write. All whole-disk nodes are now labelled, and
`/dev/block/bootdevice` is created in `on fs`, before the HAL starts.

Installing an OTA from recovery deletes the source slot's logical partitions
(Virtual A/B cannot snapshot from recovery). If a switch ever fails, finish it
with `fastboot set_active <slot>`.

### Battery, USB and clock

- The gauge reads `/sys/class/power_supply/battery` directly
  (`TW_USE_LEGACY_BATTERY_SERVICES`); it used to show a fake 100%.
- `piano-boot-adsp.sh` starts the ADSP, which runs USB and Type-C detection.
  The blobs are copied into the firmware search path rather than read in place,
  because reading them needs a capability AOSP forbids the kernel domain.
- Recovery starts the stock `batterysecret` daemon after the ADSP. Its battery
  authentication event releases the charger's 100 mA input limit. On the same
  Mac USB connection, the limit rose to 1,600 mA and the battery charge
  counter increased by 86,000 µAh over five minutes. The previous image
  detected USB power but discharged despite showing `Charging`.
- A wall charger stayed connected for 5 minutes 38 seconds. During a logged
  two-minute interval the charge counter rose by 243,000 µAh, and the displayed
  level rose from 25% to 33% over the visit. The kernel reported about 9 V
  after connection. The maximum charging wattage has not been measured.
- The clock follows Android's time zone: the user's choice from
  `persistent_properties`, otherwise the ROM default from `build.prop`.
  `bootable_recovery/0003`.

### Known issues

- After an OTA, unlock once in Android before decrypting in TWRP. The Synthetic
  Password key stays bound to the old patch level until Android upgrades it,
  and recovery deliberately never writes that key (`system_security/0001`).
- Stock recovery sideload does not work on this device.

## Release candidate — OS3.0.307.0.WPYCNXM

Base: TWRP 16 on the Android 16 recovery tree (BP2A.250605.031.A2), built for
the Xiaomi Pad 8 Pro, codename `piano`, Snapdragon 8 Elite (`sun`), A/B with
Virtual A/B logical partitions.

### Packaging

- The recovery partition is 104857600 bytes and the image is padded to that
  exact size.
- Ramdisk compression is legacy LZ4. gzip and zstd both bootloop on this
  device and must not be substituted.
- The image is AVB-signed with SHA256_RSA4096 against a local test key, with
  rollback index 1 at index location 0. The private key is not part of this
  tree.

### Metadata encryption

Recovery brings up the metadata-encryption layer itself and mounts /data
through /dev/block/mapper/userdata. The raw userdata partition is never
mounted before that mapper exists, and the `formattable` flag is removed from
every fstab entry so fs_mgr cannot decide a metadata-encrypted partition looks
wiped and reformat it. The mapper is torn down through libdm
DeviceMapper::DeleteDevice rather than by shelling out to dmctl.

### File-based encryption and Weaver

The PIN is backed by the Xiaomi Weaver implementation, whose GlobalPlatform
trusted application lives on the modem partition. Stock
/vendor/etc/ssg/ta_config.json looks for it under /vendor/firmware_mnt, so the
credential path only works while that mount is present.

TWRP unmounts sub-directory mounts of /vendor during its own partition
lifecycle, so the mount cannot simply be established at boot. Instead,
TWPartitionManager::Decrypt_Device runs an optional device hook,
/system/bin/twrp-pre-decrypt, in the statement immediately before
Decrypt_User, and refuses the credential decrypt if it fails. The hook mounts
the modem partition for the active slot read-only at /vendor/firmware_mnt with
the stock firmware_file label, creates its own mountpoint when the stock
vendor image is not mounted, and detects a mount that a later /vendor mount
has shadowed by testing that the trusted-application tree is reachable rather
than by reading /proc/mounts.

The canonical firmware mount at /firmware is unchanged and remains a normal
fstab entry.

### Secure stack

The stock QSEE-side daemons run from the recovery ramdisk: qseecomd,
minkdaemon, keymint, gatekeeper, weaver, ssgtzd and qwesd. Their stock shared
libraries come from read-only views of the stock vendor and odm images.

### /vendor lifecycle

Those views are mounted at /piano/vendor-stock and /piano/odm-stock. They were
previously under /vendor, where the mounting and unmounting of the real
/vendor by TWRP turned them into independent peer mounts that could neither be
unmounted nor mounted around, leaving /vendor permanently busy. Nothing of
ours is mounted under /vendor any more except the credential-time firmware
mount, which exists only for the duration of the decrypt call.
