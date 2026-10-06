# TWRP 16 Functional Test Matrix

Manual owner testing only. Never enter credentials through shell history.

Risk classes: READ-ONLY, LOW-RISK STATE CHANGE, STATE-CHANGING, DESTRUCTIVE / HIGH-RISK.

| Feature | Risk | Prerequisites / procedure | Expected / failure | Persistent state / recovery | Status |
|---|---|---|---|---|---|
| Recovery boot | LOW-RISK STATE CHANGE | Boot recovery_a only. | UI appears; failure: fastboot/black screen. | No intended state change; flash known-good recovery_a if needed. | Validated HOS3 |
| Touch/display/brightness | READ-ONLY | Check rotation, touch, brightness control. | Input and panel respond. | None. | Touch/display validated; brightness untested |
| Vibration/battery/charging/RTC | READ-ONLY | Observe UI values with USB attached. | Values plausible; failure: missing/stale. | None. | Untested |
| ADB | READ-ONLY | Connect USB, run adb devices from host. | recovery device listed. | None. | Validated |
| Reboot system/recovery/bootloader | LOW-RISK STATE CHANGE | Use TWRP Reboot menu one target at a time. | Selected target boots. | No data change; use fastboot if recovery fails. | System validated; others untested |
| Active slot reporting | READ-ONLY | View TWRP/fastboot slot information. | Slot agrees with known active slot. | None. | Untested |
| Metadata and /data mount | READ-ONLY | Inspect mount state before credential. | Clean failure or expected mount state. | None. | Untested |
| PIN decrypt | LOW-RISK STATE CHANGE | Owner enters PIN in TWRP UI. | Data successfully decrypted; Internal Storage readable. | Kernel keys only; reboot system if unexpected. | Validated HOS3 |
| Password/pattern decrypt | LOW-RISK STATE CHANGE | Use only a test device or known backup. | Correct credential decrypts; wrong one fails cleanly. | No destructive fallback expected. | Untested |
| /sdcard, media, user, system_ce | READ-ONLY | After successful decrypt inspect paths. | Readable expected content. | None. | Validated HOS3 |
| Internal Storage / File Manager | LOW-RISK STATE CHANGE | Browse only, do not edit/delete. | Size and files are correct. | Avoid file changes. | Storage validated; File Manager untested |
| MTP / USB OTG | LOW-RISK STATE CHANGE | Connect one host/device and browse only. | Storage enumerates. | Avoid writes during first test. | Untested |
| ADB sideload / ZIP install | STATE-CHANGING | Use disposable test package; verify target partition. | Install result matches package. | Can change partitions; keep image/firmware backup. | Untested |
| Magisk / boot image workflow | STATE-CHANGING | Use only documented, device-specific workflow. | Patched boot result is bootable. | Can break boot; retain stock matching images. | Untested |
| Recovery image flashing | STATE-CHANGING | Flash recovery_a only after SHA verification. | Recovery boots. | Never casually touch recovery_b. | recovery_a validated |
| Partition selector | LOW-RISK STATE CHANGE | Inspect UI only first. | Correct slot/partition names. | Do not flash from selector initially. | Untested |
| Backup boot/vendor_boot/recovery | STATE-CHANGING | Save to external storage with space. | Archive verifies. | Backup writes destination only. | Untested |
| Backup data/decrypted data/metadata/super | STATE-CHANGING | Use disposable storage and record hashes. | Archive completes. | Large and sensitive; do not test casually. | Untested |
| Restore any partition | DESTRUCTIVE / HIGH-RISK | Require verified backup and explicit recovery plan. | Exact intended content restored. | Can brick/lose data; do not test casually. | Untested |
| Dynamic/super/logical partitions | READ-ONLY | Inspect visibility and sizes only. | Matches stock layout. | Do not resize/delete logical partitions. | Untested |
| Slot switching | DESTRUCTIVE / HIGH-RISK | Only with known-good bootable content on both slots. | Selected slot boots. | Can strand device; do not test casually. | Untested |

Safe now: UI, ADB, read-only mounts/path inspection. Needs disposable data/backup: sideload, MTP writes, ZIPs, backups. Do not test casually: restore, slot switching, super/dynamic modification.


## Recommended owner execution order

### Tier 1  safe / read-only
Perform one item at a time and record the result before proceeding.

1. Boot recovery_a; verify touch and display.
2. Check brightness, vibration, battery reporting, charging indication, and RTC/time.
3. Confirm ADB.
4. Inspect active slot reporting.
5. Inspect dynamic partition visibility and super/logical partition detection.
6. After an owner-entered PIN decrypt, browse Internal Storage and File Manager without changing files.
7. Check MTP enumeration without writing data.
8. Check USB OTG detection with a read-only or disposable device.
9. Reboot system, then separately test reboot recovery and reboot bootloader.

### Tier 2  low-risk state-changing
Use a current backup and record the exact firmware/build first.

1. ADB sideload a harmless test package.
2. Install a harmless test ZIP that does not alter boot-critical partitions.
3. Follow a documented Magisk workflow only with matching stock images ready.
4. Create backups; verify that output files are complete and readable.

### Tier 3  backup-required
Do not proceed without verified known-good backups and a restoration plan.

1. Validate data backup contents.
2. Validate boot, vendor_boot, and recovery backup contents.
3. Test one restore using a known-good backup.
4. Test slot switching only when both slots have known bootable content.

### Tier 4  destructive / last resort
Do not test casually. Require explicit owner decision, external backups, and fastboot recovery plan.

1. Metadata restore.
2. Super restore.
3. Any wipe/format path.
4. Cross-slot recovery testing, including recovery_b.

## Safe manual validation commands

Run only after the owner has manually booted recovery and, where relevant, manually decrypted data. Do not enter credentials through shell or automate credential entry.

```bash
adb devices
adb shell getenforce
adb shell date
adb shell getprop ro.boot.slot_suffix
adb shell ls -la /sdcard
adb shell ls -la /data/media/0
adb shell ls -la /data/user/0
```
