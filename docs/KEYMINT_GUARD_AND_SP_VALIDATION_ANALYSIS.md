# KeyMint Guard and SP Validation Analysis

## Phase 3: KeyMint detector
Recovery resolves the Synthetic Password alias in the LockSettings SELinux namespace.
Keystore2 returns a database-backed KEY_ID descriptor, so createOperation uses KEY_ID,
not Domain::BLOB.

The proven SP operation is AES-GCM decrypt with no padding, a 12-byte nonce, and
a 128-bit MAC. The narrow guard currently identifies a database-backed request by:
key_id_guard.is_some(), params.len() == 5, and ALGORITHM/BLOCK_MODE/PADDING/NONCE/MAC_LENGTH.

The exact parameter count is brittle: Android/vendor implementations may add valid tags,
and another AES-GCM operation could share the same shape. A future detector should carry
a reliable SP identity from alias resolution, then require database-backed AES-GCM decrypt
and valid nonce/MAC semantics. No implementation change is made here.

The broad utils.rs mutation guard remains. For SP operations KEY_REQUIRES_UPGRADE and
INVALID_KEY_BLOB must fail without upgrade, import, strip, or conversion.

## Phase 4: validation classification
| Check | Class | Reason |
| --- | --- | --- |
| Two-byte header | Android protocol | version then protector type |
| type 0 | LSKF plus piano observation | piano owner protector is LSKF-based |
| version 3 | V3 plus piano observation | tested owner protector is V3 |
| raw SP 128 | V3 invariant | SHA-512 result is hex encoded |
| SP800-108 FBE derivation | V3 invariant | current Android V3 subkey behavior |
| SP blob 186 | current protector observation | current IV/tag/ciphertext composition |
| outer plaintext 156 | current protector observation | 128 raw-SP plus inner GCM overhead |
| Weaver 128/16/16 | piano invariant | fixed known Xiaomi geometry |

Do not relax any check without separate compatibility analysis and testing.
