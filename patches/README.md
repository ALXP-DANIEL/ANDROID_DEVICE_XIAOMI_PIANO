# OrangeFox 16 source patches

The `ofox_*` directories hold the changes this device needs in OrangeFox's own
source. The device tree alone is not enough: a build from a fresh OrangeFox 16
sync is missing these fixes.

Source setup (OrangeFox R12.0, `fox_16.0`). The complete OrangeFox 16
manifest is not public, so start from the base manifest github.com/OrangeFox16/platform_manifest_twrp_aosp (branch `twrp-16`) and add the
two local manifests in `device/xiaomi/piano/manifests/`:

- `orangefox-r12.xml` swaps about 27 projects for OrangeFox's GitLab
  `fox_16.0` versions (recovery, build, security, sepolicy, vendor/twrp,
  WLAN, ttyd, rclone, libvterm and others). Three stay on TWRP-Test on
  purpose: `system/vold`, because OrangeFox's fox_16.0 vold fails the Weaver
  synthetic-password unwrap on this device; and `system/core` and
  `system/update_engine`, because with OrangeFox's versions a recovery-installed
  OTA is left in Virtual A/B snapshots, and HyperOS 4 (Android 17) then loops at
  the Mi logo. TWRP's versions write the partitions directly, and the device
  boots.
- `piano.xml` adds `hardware/qcom/bootctrl` (LineageOS, `lineage-23.2-caf`)
  for the recovery boot control HAL, which A/B OTA installs need.

`vendor/recovery` is cloned by hand from
gitlab.com/OrangeFox/vendor/recovery.git, branch `fox_16.0`.

```
cp device/xiaomi/piano/manifests/*.xml .repo/local_manifests/
repo sync
device/xiaomi/piano/tools/apply-ofox-patches.sh ~/android/ofox-16
```

The script is safe to run again, because it skips patches that are already
applied. If OrangeFox has changed and a patch no longer fits, the script stops
and names that patch.

After building, check that no library in `recovery/root/system/lib64` is older
than its `system/lib64` copy: the relink step does not refresh them, and a
stale copy can stop recovery from starting (`CANNOT LINK EXECUTABLE`). The
builder's `ofox-build-piano-full.sh` does this automatically.

Tested revisions:

| Project | Remote | Revision |
| --- | --- | --- |
| `bootable/recovery` | gitlab.com/OrangeFox/bootable/Recovery | `6ff71bed1506fec1893247f0c74d7ae87c594eed` |
| `system/core` | github.com/TWRP-Test/android_system_core (twrp-16.0) | `b6e6bf1` |
| `system/update_engine` | github.com/TWRP-Test/android_system_update_engine | `a4c7444` |
| `vendor/recovery` | gitlab.com/OrangeFox/vendor/recovery | `928aab803d2c1f759824a5222b9790a96f542502` |
| `system/vold` | github.com/TWRP-Test/android_system_vold | `4c83041` |

`ofox_bootable_recovery`:

| Patch | What it fixes |
| --- | --- |
| `0001` | Adds the `update_engine` headers that TWRP's libsnapshot needs, and uses `servicemanager.recovery`. The system servicemanager finds no VINTF manifest in recovery, so Weaver and Gatekeeper never register and decryption fails. It also stops OrangeFox relinking the system `libvintf.so`, which would replace the recovery variant that `servicemanager.recovery` needs. |
| `0002` | Waits for the QSEE listeners and KeyMint before decryption, and drops the startup `copySqliteDb()`. |
| `0003` | OZIP returns unsupported when there is no key. |
| `0004` | Follows Android's time zone until one is picked in OrangeFox. |
| `0005` | Starts MTP after decryption (it never started on encrypted devices), and runs the pre-decrypt hook. |
| `0006` | ORS `sideload` no longer installs the package twice, and the queue file is removed as soon as it is opened, so an unfinished run is not replayed by the next `twrp <cmd>`. |
| `0007` | Reads the clock offset from `/mnt/vendor/persist/time` (the clock started at 1970), and unmounts sub-mounts first. |
| `0008` | Passes `TW_X/Y/W/H_OFFSET` to the GUI build, so the phone-sized theme is drawn in the middle of the landscape screen, and keeps keyboard long-press labels inside their keys. |
| `0009` | Defaults: Dark style, on-screen navigation buttons instead of gestures, and no broken `menu_text` style on the splash screen. |
| `0010` | Fixes the recovery checksums used by the automatic self-reflash after an A/B ROM or OTA install. Without it, stock recovery is left in the new slot. |
| `0011` | GUI actions: skips a Magisk zip queued after an A/B ROM or OTA (it would patch the old slot), and waits for a finishing action instead of dropping a new `twrp` command, which used to leave the command line stuck. |

`ofox_system_core/0001` exports `libupdate_engine_headers` from libsnapshot
(`cow_reader.h` includes `payload_consumer/file_descriptor.h`).

The `build/make` changes in an OrangeFox checkout come from OrangeFox itself
and are not part of this set.

# Source patches — TWRP16 piano

Applied to the build checkout (`~/android/twrp-16`), not to this device tree.
The GitHub Actions workflow fetches the tested `bootable/recovery` revision
`580a0e89cb27007f4584cbb9649ba2607bb21d6a` and applies these patches
before building. Its `system/vold` and `system/security` patches likewise match
the locally tested source edits.

`bootable_recovery/0000` adds the pre-decryption device hook, `0001` waits for
the secure stack, `0002` preserves init's persist mount, `0003` reads Android's
time zone, and `0004` backs up and verifies recovery around a successful A/B
OTA while filtering the Mount screen. The A/B restore path has not yet been
exercised with the option enabled during an OTA.

## system_vold

### 0001-recovery-crypto-safety-invariants.patch

Phase 7S. Ports the safety invariants established during the TWRP14 bring-up
into this tree's `system/vold` at revision
`4c83041ec61f9b482085685f1e6aed5a62f103aa`.

This is a port of the *invariants*, not of the TWRP14 patch queue. Each was
re-checked against this revision first, and half of the original contract
turned out to be unnecessary because upstream TWRP-Test has already adopted it:

| Invariant | State at this revision | Action |
| --- | --- | --- |
| device key uses `neverGen()` | already present (`FsCrypt.cpp:557`) | none needed |
| user 0 DE/CE keys never created | already present, "refusing to create one" | none needed |
| Weaver record parsed correctly | **broken** | fixed |
| synthetic password authenticated | **broken** | fixed |
| metadata encrypt/format refused | **absent** | added |
| CE key material never deleted | **absent** | added |

#### Weaver record parsing

Upstream reads the slot with:

```c
const int* intptr = (const int*)weaver_data.data() + sizeof(unsigned char);
wd->slot = *intptr;
```

That is pointer arithmetic on `int*`, so it advances **four** bytes: it reads 4
bytes at offset 4 of a 5-byte record — one byte out of bounds — and interprets
them in native byte order. Android writes one version byte followed by a
big-endian `uint32` (`DataOutputStream.writeInt`, and `ByteBuffer` defaults to
big endian), so the value was wrong as well as overread.

Replaced with a length check, a version check, and an explicit big-endian
decode of bytes 1..4.

#### Synthetic password authentication

Upstream:

```c
EVP_DecryptUpdate(d_ctx, secret_key, &actual_size, intermediate_cipher_text, cipher_size);
unsigned char tag[AES_BLOCK_SIZE];
EVP_CIPHER_CTX_ctrl(d_ctx, EVP_CTRL_GCM_SET_TAG, 16, tag);
EVP_DecryptFinal_ex(d_ctx, secret_key + actual_size, &final_size);
```

The GCM tag is taken from an **uninitialised stack buffer**, `cipher_size`
includes the trailing 16-byte tag so the tag is decrypted as ciphertext, and
the `EVP_DecryptFinal_ex` result is discarded. The decryption is therefore
completely unauthenticated — any ciphertext "succeeds".

Replaced with: plaintext length `cipher_size - 16`, the real tag taken from the
trailing 16 bytes, every OpenSSL call's return checked, and the buffer
`OPENSSL_cleanse`d and the secret refused on authentication failure.

#### Metadata encryption: refuse to encrypt or format

`fscrypt_mount_metadata_encrypted()` now returns false immediately when
`needs_encrypt` or `should_format` is set, making `encrypt_inplace` and
`f2fs::Format` unreachable from recovery regardless of what any caller passes.

#### CE key material is never deleted

Both `fixate_user_ce_key()` call sites are removed — the direct one in
`fscrypt_set_ce_key_protection()` and the deferred pass in
`fscrypt_deferred_fixate_ce_keys()`. Fixation deletes every other on-disk
binding of the key; recovery only needs the key in memory, and a failed attempt
must leave the user's protectors exactly as they were.

## Not yet done

Negative tests have not been run, and crypto is still disabled in
`BoardConfig.mk`. These patches are staged, compiled only when crypto is
enabled, and unproven until then.

## Compile validation (2026-09-14)

The patch is now compiled by every build, even with crypto off: `libvold` is a
static dependency of `bootable/recovery:recovery`, so the code is built and
linked regardless of `TW_INCLUDE_CRYPTO`.

That surfaced a real defect in the first version of this patch. Removing the
`fixate_user_ce_key()` call from `read_user_ce_key()` left the function with no
callers, and the tree builds with `-Werror`:

```
system/vold/FsCrypt.cpp:193:13: error: unused function 'fixate_user_ce_key' [-Werror,-Wunused-function]
```

The function is now removed outright rather than silenced. Keeping a dead copy
of a routine whose whole purpose is to delete Keystore key bindings invites it
back; if the behaviour is ever wanted again it should be reintroduced
deliberately, with the recovery-specific reasoning written down at that point.

The patch has not yet been exercised at runtime — crypto is still off, so none
of these paths execute. It is compiled, not proven.

## bootable_recovery/0001-align-os-props-before-metadata-decrypt.patch

Adds `piano_align_os_props()` to `twrp.cpp` and calls it before
`Setup_Fstab_Partitions()`, under `TW_INCLUDE_CRYPTO`.

KeyMint binds keys to the OS version and security patch level. The recovery
shipped the AOSP defaults — security patch 2025-06-05, and no
`ro.vendor.build.security_patch` at all — while this firmware reports 2026-07-01
for system and 2026-02-01 for vendor. Metadata decryption therefore failed with
`-62 KEY_REQUIRES_UPGRADE`, and the `upgradeKey()` that followed failed with `-8`.

The function reads each value from the device's own `build.prop` and overrides
the recovery's, so the numbers are not written into the tree and stay correct if
the firmware is updated. It maps the logical partitions it needs itself, because
`Setup_Fstab_Partitions()` has not run yet.

Why not upstream's `TW_OVERRIDE_SYSTEM_PROPS`: it runs from
`process_recovery_mode()`, after `Setup_Fstab_Partitions()`, so it cannot affect
decryption. It is also unusable on this tree — `twrp_recovery_defaults.go` emits
it as `-DTW_OVERRIDE_SYSTEM_PROPS=%s` with no quotes, unlike its neighbours which
use `="%s"`. Unquoted, the compiler reads a bare identifier; quoted, the value
corrupts Soong's JSON variables file.

Result: `KEY_REQUIRES_UPGRADE` no longer occurs, and no key blob is rewritten —
which also satisfies the rule that key material must not be modified. Decryption
still fails afterwards with `INCOMPATIBLE_BLOCK_MODE`; see
docs/twrp16-migration-progress.md.

## Read-only fallback to the stock `key_back` blob

`BeginKeystoreOp()` retries with `<key_dir>/../key_back/keymaster_key_blob` when
`begin()` fails on the live blob.

HyperOS keeps a second copy of the metadata key beside the live one. On this
device the two directories hold an identical `encrypted_key` and a different
`keymaster_key_blob` — one wrapped payload re-bound under a new blob, which is
what a key upgrade leaves behind. Either could be the one a given TEE state will
open, so trying only the live one would give up a working key for nothing.

It writes nothing: the backup is read, tried, and the live blob is left exactly
as found even when the backup is the one that works.

On this device both blobs fail identically with `INCOMPATIBLE_BLOCK_MODE`, so
this is not the cause of the current failure. It is kept because the scenario it
covers is real on a vendor that maintains that backup, and it costs one read.

## bootable_recovery/0002-keep-persist-mounted-by-init.patch

`TWPartition::Mount_Persist_Root_If_Needed()` mounted persist, read the clock
offset from `time/ats_2`, and unmounted it - once per fstab pass - even when
init had mounted it first. The device mounts persist from init because
qseecomd crashes if the secure world asks for a persist file while it is
absent, and the Weaver trusted app cannot open without it. The function now
unmounts only what it mounted itself.

## bootable_recovery/0003-follow-android-time-zone.patch

TWRP keeps its time zone in its settings file on persist. persist is read-only
here and an old settings file pins US Central, so every boot came back to the
wrong zone. When the settings file is missing or unwritable, the zone is taken
from the installed system: the user's `persist.sys.timezone` in
`/data/property/persistent_properties` (a protobuf, readable once metadata
decryption has run), otherwise the ROM default from `build.prop`, searched in
Android's override order (product, odm, vendor, system_ext, system). Nothing is
written. A zone chosen in the UI still applies for the session.

## system_security/0001-recovery-keymint-mutation-policy.patch

Recovery key-mutation policy for keystore2:

- vold key blobs that report `KEY_REQUIRES_UPGRADE` are upgraded for the
  operation only; the upgraded blob is never written back.
- `INVALID_KEY_BLOB` is never answered with a compatibility import.
- The database-backed Synthetic Password key is used as-is and never upgraded
  or re-imported; if it needs an upgrade the operation is refused
  ("Recovery SP key requires upgrade"). Android upgrades it on the first
  unlock after an OTA, which is why a user must unlock once before TWRP can
  decrypt.
