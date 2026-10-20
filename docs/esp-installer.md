# Real ESP installer

The recovery ZIP installs Visor before writing either boot slot. Firmware only
tries GPT EFI System Partitions; a missing or returned loader falls through to
the AndroidBoot application in the firmware volume. No embedded ESP is published.

Existing ESP: inspect and verify both GPT copies and checksums, reject ambiguous
or incorrectly typed ESPs, mount FAT, back up existing files, check free space,
copy and compare the packaged files. Other files remain. Never format an existing
ESP. A failed update attempts to restore overwritten files from its tar backup.

Fresh ESP: require userdata last on the disk, a spare GPT entry, 4K UFS sectors,
decrypted mounted F2FS `/data`, equal superblocks, a verified whole-userdata
encryption mapping and enough free filesystem blocks. Back up GPT before shrink.
Invoke F2FS_IOC_RESIZE_FS, sync and verify superblocks, then recheck GPT has not
changed. Shorten only userdata's end and create a 512 MiB ESP. Write backup GPT
first, primary second, sync and read back. The FAT16 image has about 117 MiB
filesystem capacity. It is written directly to the newly allocated GPT extent,
since mounted userdata can prevent kernel partition reread until reboot.

The shrink helper is adapted from Project-SunUEFI commit
`1b0e201e3e7fb0737d73c41624a855482a4df7c5`, under BSD-2-Clause-Patent.
Upstream also describes fresh partition creation as not hardware-validated.
This integration has not performed a resize or flash on the tablet.

Failure boundaries:

- Unsupported/decryption-unavailable recovery: abort before shrink or GPT write.
- Shrink succeeds but later steps fail: the filesystem can remain smaller even
  if GPT remains unchanged. Never assume all failures undo filesystem changes.
- GPT write/readback failure: attempt to restore the old GPT; power loss is not
  an atomic transaction. The backup is this helper's binary snapshot of headers,
  entries and geometry, not an sgdisk backup file.
- ESP image write failure after GPT creation: boot stays unpatched; the new ESP
  can be incomplete. It requires repair rather than automatic reformat on retry.
- Existing-ESP restore does not remove newly introduced files. It restores the
  backed-up files that may have been overwritten.
- Boot slots are written sequentially, not atomically. Originals are backed up
  before changing storage. Uninstall restores kernels but keeps ESP and does
  not grow userdata.

Local checks: strict native and ARM64 compilation, synthetic GPT inspection
tests, injector no-ESP roundtrip, shell syntax and the full RELEASE firmware build.
These do not establish OrangeFox shrink support, encrypted-data preservation or
successful physical-device boot. Fresh creation is experimental.

## Installer and uninstall packages (2026-10-18)

`make-zip.sh` produces `-installer.zip`, `-uninstall.zip` and
`-uninstall-clean.zip` from the same payload:

- Install: preserve/update an existing ESP, or shrink F2FS and create a fresh
  512 MiB ESP. Fresh creation now saves a checksummed ownership receipt containing
  original and installed GPT snapshots plus the pre-shrink filesystem geometry.
  It publishes this with root-only permissions at `/data/adb/piano-uefi/esp-reclaim.receipt` before boot patching.
- Ordinary uninstall: remove the injection from the current compatible boot
  images; leave ESP, userdata boundary and receipt intact.
- `-uninstall-clean.zip`: remove boot injection and reclaim an installer-owned
  ESP, then finish filesystem growth in a second recovery invocation.

Reclaim is deliberately separate because it deletes the entire owned ESP,
including any files subsequently added there. The full ESP volume is backed up
and compared before boot/GPT changes. Backups are in a dated directory beneath
`/sdcard/piano-uefi`. Copy them off the tablet before hardware testing; on-device
backups do not protect against all storage failures.

### Reclaim stage 1

Decrypt/mount data in a supported recovery and flash the reclaim ZIP. It requires
both `fsck.f2fs` and `resize.f2fs` in recovery, an intact receipt from fresh creation,
and exact agreement between current GPT and the receipt. The receipt must describe
only a shortened userdata end and our adjacent 512 MiB ESP. It rejects pre-existing
ESPs, legacy installs without receipts, different disks, other partition edits,
invalid types/names, corrupt receipts, and mounted ESPs. It backs up the ESP and
boot images, removes the UEFI injection from the current boot images, then restores
the recorded original GPT boundaries. It does not restore stale pre-OTA boot images.
The final message explicitly says **NOT FINISHED**.

Reboot **recovery**, decrypt/mount data, then flash the same reclaim ZIP again.
Do not assume a normal Android reboot will grow F2FS on EvoX or Lineage.

### Reclaim stage 2

The receipt and script are staged in recovery RAM before data is unmounted.
The helper requires the physical userdata device and plaintext mapping to both
match the restored partition size. It verifies the existing zero-offset mapping,
matching F2FS superblock geometry and saved section size. It rejects any mount of
the raw partition or its mapped device in the recovery mount namespace.

The script unmounts data and its submounts without force/lazy flags. Busy storage,
MTP mounts, unavailable decryption, a vanished mapping or a stale partition/mapping
size aborts safely, retaining the expanded GPT and pending state. Recovery must
support retaining an existing plaintext mapping while /data is unmounted; this
has not yet been established on hardware.

After the filesystem check it grows through the **mapped** device to the recorded
pre-install target, never raw encrypted userdata. It skips resize when the saved
capacity is already reached, making repeat invocations resumable. It verifies
capacity (within one F2FS section of the original), remounts data with its prior
options, and only then reports completion. A failed grow never triggers rollback
to a smaller GPT, which could truncate a partially grown filesystem. Error logs
remain in `/tmp/piano-uefi` during that recovery session.

The ZIP uses recovery-provided F2FS tools; compatible versions/features remain a
hardware preflight requirement. It does not bundle arbitrary desktop tools or
change encryption keys, run mkfs, export keys, or disable encryption. Checking
that the tool exists is not proof of feature compatibility.

### Validation boundary

21 host tests cover GPT inspection, injection roundtrip, receipt validation,
foreign/changed-layout refusal, resumable states, and synthetic GPT restoration
with a userdata sentinel preserved. Native and static ARM64 builds use `-Werror`;
shell scripts pass syntax checks. These tests do not execute an encrypted F2FS
resize or validate actual OrangeFox/TWRP mount/decryption lifecycle behavior.
No tablet storage or boot writes have been performed for this implementation.
Both fresh creation and reclamation remain experimental until hardware tested.

## Boot AVB metadata

The native injector regenerates an AVB 1.0 footer and a SHA-256 boot hash
after install and removal. Its vbmeta algorithm is NONE for an unlocked
bootloader; no private signing key is distributed. Boot partition capacity is
preserved and insufficient metadata space is rejected before writing. This
repairs the malformed/missing metadata rejection observed in ABL boot 1759.
The tablet accepted the unsigned replacement and reached Visor.

## Native ESP sectors

New FAT16 images use 4096-byte sectors and clusters to match piano UFS.
A 512-byte BPB is rejected by the recovery kernel even when its FAT is otherwise
valid. Existing ESPs are still never automatically reformatted. An ESP created
by the old builder needs a separately backed-up repair; an ordinary update
aborts rather than replacing an unmountable volume.

Boot property descriptors and rollback index/location are preserved from the
input image. Dropping the Android version/security-patch metadata caused
KeyMint INVALID_ARGUMENT during metadata-key upgrade; preserving it allowed
Android to complete boot from Visor on slot B (sys.boot_completed=1).
Inputs already damaged by an older injector must first be restored from the
pre-install backup, because absent properties cannot be reconstructed reliably.
Reclaim and repeated suspend/resume behaviour remain untested.
