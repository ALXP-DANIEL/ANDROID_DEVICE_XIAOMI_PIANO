# Owner Validation Results

Record one firmware build and one recovery SHA per result. Do not record credentials or secret material.

| Feature | Firmware | Recovery SHA | Result | Observed behavior | Notes | Date |
| --- | --- | --- | --- | --- | --- | --- |
| Recovery boot | HyperOS 3 / Android 16, exact build not recorded | 307a445c576e833068fa7d48978ef2b5dcdb6083845fa4148477c043fe0777d5 | PASS | TWRP booted on recovery_a. | Production-equivalent image. | 2026-09 |
| SELinux Enforcing | HyperOS 3 / Android 16, exact build not recorded | 307a445c576e833068fa7d48978ef2b5dcdb6083845fa4148477c043fe0777d5 | PASS | Enforcing during validated touch/decrypt work. |  | 2026-09 |
| PIN decrypt | HyperOS 3 / Android 16, exact build not recorded | 307a445c576e833068fa7d48978ef2b5dcdb6083845fa4148477c043fe0777d5 | PASS | User 0 decrypted successfully. | Owner entered PIN manually. | 2026-09 |
| CE storage | HyperOS 3 / Android 16, exact build not recorded | 307a445c576e833068fa7d48978ef2b5dcdb6083845fa4148477c043fe0777d5 | PASS | system_ce, user, media, and sdcard became readable. | prepare-user-storage succeeded. | 2026-09 |
| ADB | HyperOS 3 / Android 16, exact build not recorded | 307a445c576e833068fa7d48978ef2b5dcdb6083845fa4148477c043fe0777d5 | PASS | Recovery ADB worked. |  | 2026-09 |
| Stock Android reboot/unlock | HyperOS 3 / Android 16, exact build not recorded | 307a445c576e833068fa7d48978ef2b5dcdb6083845fa4148477c043fe0777d5 | PASS | Stock Android booted and owner credential remained valid. |  | 2026-09 |
