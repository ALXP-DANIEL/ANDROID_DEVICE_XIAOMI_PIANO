# Phase 2 notes: Recovery and the other slot

The menu runs before Linux, so it can only use what the hardware offers at
that point: the framebuffer, the PMIC (through SPMI), IMEM and PSCI.

## Recovery and bootloader

Qualcomm bootloaders read a restart reason after a warm reset.

| Store | Where | Known values |
|---|---|---|
| IMEM restart_reason | `0x14680000 + 0x65c` (stock DTB, `qcom,msm-imem-restart_reason`) | recovery `0x77665502`, bootloader `0x77665500` |
| PMIC SDAM reboot mode | `reboot-mode` node with an nvmem cell; not in the base DTB, look in the live DT | recovery `0x01`, bootloader `0x02` |

Tested on the tablet: ABL ignores the IMEM reason and reads the PMIC one.
The live DT's `qcom,reboot-reason` node points at SID 0 SDAM `0x7100`, cell
`0x48` bits 1-7. Writing recovery (1) or bootloader (2) there through the
SPMI write channel, then PSCI `SYSTEM_RESET`, lands in recovery or fastboot.

## Other slot

Slots are chosen by ABL from the GPT slot attributes, before we run. Booting
the other slot from the menu needs one of:

1. Write the GPT attributes (active/successful/retry) and reset. Needs a UFS
   driver, which only exists once UEFI runs (Phase 3).
2. Rewrite `androidboot.slot_suffix` in the boot arguments before Linux
   starts. Only safe when both slots carry the same kernel.

Option 1 is the right one; option 2 is a stopgap.
