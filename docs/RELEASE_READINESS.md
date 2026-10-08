# Release Readiness

Working title: TWRP 16 for Xiaomi Pad 8 Pro (piano), HyperOS 3 / Android 16, Initial Test Release.

| Requirement | Status | Evidence / gate |
| --- | --- | --- |
| Build | PROVEN | Clean image SHA 74caa2c19502fa4d30277570eee5a315048df0b8769fe99ee5653ba90e8bbe7b |
| Preflash audit | PROVEN | PASS=7 FAIL=0 |
| Clean-image boot | PENDING | Live proof used production-equivalent image; clean image requires owner boot confirmation |
| Boot | PROVEN | Production-equivalent image booted |
| Decryption | PROVEN | PIN, SP V3, CE unlock, and prepare-user-storage worked |
| Internal Storage | PROVEN | media and sdcard readable |
| Stock reboot | PROVEN | Stock Android rebooted and unlocked |
| SELinux | PROVEN | Enforcing during validation |
| ADB | PROVEN | Recovery ADB worked |
| MTP | PENDING | Owner validation required |
| OTG | PENDING | Owner validation required |
| Sideload | PENDING | Owner validation required |
| ZIP install | PENDING | Owner validation required |
| Backup | PENDING | Owner validation required |
| Restore | PENDING | High-risk owner test required |
| Dynamic partitions | PENDING | Read-only visibility check required |
| Slot behavior | PENDING | recovery_a proven; broader slot tests pending |
| Global firmware | PENDING | Must be tested separately |
| China firmware | PENDING | Must be tested separately |
| HyperOS 4 | NOT VALIDATED | Outside initial Android 16 / HyperOS 3 scope |

## Initial test-release gates

Required before release:
- clean build;
- PASS=7 FAIL=0 preflash audit;
- clean-image boot;
- PIN decrypt;
- Internal Storage;
- ADB;
- stock reboot and unlock;
- no probe artifacts;
- no secret debug logging;
- no Weaver write;
- no vendor Weaver getConfig transaction;
- KeyMint fail-closed protection;
- known limitations documented;
- published SHA-256.

MTP, OTG, backup/restore, and China ROM testing may remain explicitly untested for the initial test release. They must not be presented as supported.
