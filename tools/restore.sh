#!/bin/sh
# SPDX-License-Identifier: BSD-2-Clause-Patent
# Put a plain boot.img back on the active slot (tablet in fastboot).
# The image must come from that same slot: boot and system must match.
set -e
[ -f "$1" ] || { echo "usage: $0 original-boot.img" >&2; exit 1; }
slot=$(fastboot getvar current-slot 2>&1 | awk '/current-slot/{print $2}')
[ -n "$slot" ] || { echo "no device in fastboot" >&2; exit 1; }
fastboot flash "boot_$slot" "$1"
fastboot set_active "$slot"
fastboot reboot
