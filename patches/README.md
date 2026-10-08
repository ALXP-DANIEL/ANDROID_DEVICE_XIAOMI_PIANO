# Source patches — TWRP16 piano

Applied to the build checkout (`~/android/twrp-16`), not to this device tree.
The GitHub Actions workflow fetches the tested `bootable/recovery` revision
`580a0e89cb27007f4584cbb9649ba2607bb21d6a` and applies these patches
before building. Its `system/vold` and `system/security` patches likewise match
the locally tested source edits.
The `vendor/twrp` patch exports the FBE and fscrypt policy flags to Soong,
matching the tested builder checkout.

`bootable_recovery/0000` adds the pre-decryption device hook, `0001` waits for
the secure stack, `0002` preserves init's persist mount, `0003` reads Android's
time zone, and `0004` backs up and verifies recovery around a successful A/B
OTA while filtering the Mount screen. `0006` runs the device's
`twrp-pre-unmap` hook before the logical partitions are destroyed, warns when
a wipe will cancel an update that has not booted, and makes the time zone
lookup release its temporary mounts. The A/B restore path was validated on
the device with the option enabled during an OTA.

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
