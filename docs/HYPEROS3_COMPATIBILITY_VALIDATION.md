# HyperOS 3 Compatibility Validation

This procedure applies separately to Global, China, and each future Android 16 OTA.
Status values: UNTESTED, BOOT-COMPATIBLE, SECURE-STACK-COMPATIBLE, DECRYPTION-COMPATIBLE, FULLY-VALIDATED, FAILED.

## Stage A: boot-only
Record ROM region/build and active slot. Boot recovery_a, verify active-ROM kernel compatibility, UI, and basic vendor/ODM mounts. Do not enter a PIN. Passing Stage A is BOOT-COMPATIBLE.

## Stage B: non-secret storage/platform
Inspect metadata and data mount behavior, F2FS, inlinecrypt flags, and recovery fstab behavior without credentials. Record failures without formatting or repair actions.

## Stage C: secure stack health
Verify SELinux Enforcing and service health for qseecomd, minkdaemon, ssgtzd, qwesd, KeyMint, Gatekeeper, Weaver, RPMB, and the TEE filesystem. Do not invoke secure credential operations.

## Stage D: protector metadata
Read only nonsecret metadata: SP version/type, pwd structure, weaver record version/slot, spblob length, and CE key directory layout. Do not print credentials, Weaver secrets, SP/FBE material, application IDs, or KeyMint blobs.

## Stage E: owner credential test
Only after A-D pass, owner enters credential in TWRP UI. Verify decrypt result, Internal Storage, CE directories, then reboot stock Android and verify normal unlock. Never automate or pass a credential via shell history.

Only all stages including stock reboot/unlock qualify as FULLY-VALIDATED.
