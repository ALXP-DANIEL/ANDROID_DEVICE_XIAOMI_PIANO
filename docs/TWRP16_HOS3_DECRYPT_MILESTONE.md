# TWRP 16 HyperOS 3 Decryption Milestone

## Scope
- Device: Xiaomi Pad 8 Pro (piano)
- Tested ROM family: HyperOS 3 / Android 16
- Tested recovery slot: recovery_a
- SELinux: Enforcing

## Verified runtime result
PIN -> scrypt -> Weaver Read -> SP V3 unwrap -> V3 SP800-108 FBE derivation -> CE unlock -> prepare user storage.
After decrypt, /data/system_ce/0, /data/user/0, /data/media/0, and /sdcard were readable.
Stock Android booted afterward and the owner credential still unlocked normally.

## Images
| Purpose | SHA-256 |
| --- | --- |
| Live-tested production-equivalent | `307a445c576e833068fa7d48978ef2b5dcdb6083845fa4148477c043fe0777d5` |
| Clean rebuilt production image | `74caa2c19502fa4d30277570eee5a315048df0b8769fe99ee5653ba90e8bbe7b` |

The clean image passed preflash audit: PASS=7 FAIL=0. It uses boot header v4,
kernel_size=0, and a legacy-LZ4 ramdisk. It removes legacy diagnostic probe
artifacts while retaining the live-tested crypto/runtime path.

## Proven configuration
| Item | Value |
| --- | --- |
| Weaver geometry | 128 slots, 16-byte key, 16-byte value |
| Weaver operation | Read only; no vendor getConfig, no write |
| SP metadata | V3, type 0 |
| SP blob | 186 bytes |
| Outer KeyMint plaintext | 156 bytes |
| Raw Synthetic Password | 128 bytes |
| FBE derivation | Android 16 V3 SP800-108 |
| CE unlock and prepare | successful |

No PIN, Weaver data, protector secret, raw SP, FBE secret, or KeyMint blob is recorded.

## Safety invariants
- Keep stock persist read-only.
- Keep the stock persist mount read-only.
- Never use Weaver writes or vendor getConfig.
- Never upgrade, import, or convert the stock SP KeyMint key.
- Crypto errors fail cleanly and never format userdata/metadata or recreate security state.
