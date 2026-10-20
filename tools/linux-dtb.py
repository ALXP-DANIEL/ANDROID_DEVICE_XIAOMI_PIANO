#!/usr/bin/env python3
# SPDX-License-Identifier: BSD-2-Clause-Patent
"""Prepare a mainline piano DTB for the boot menu, which runs after ABL.

ABL normally fills in /memory and /chosen; the menu hands Linux our DTB
directly, so do it here: copy the memory ranges from a device tree dumped
on the running tablet (live.dts), and add 64-bit initrd placeholders that
the menu fills in at boot.
   linux-dtb.py piano.dts live.dts out.dts      (compile out.dts with dtc)
"""
import re
import sys

src, live, out = sys.argv[1:4]
dts = open(src).read()
reg = re.search(r'\n\tmemory \{.*?\n\t\treg = <([^>]*)>;', open(live).read(), re.S).group(1).split()
cells = [int(x, 16) for x in reg]
ranges = [cells[i:i + 4] for i in range(0, len(cells), 4)]
ranges = [r for r in ranges if r[2] or r[3]]          # drop empty ranges
ranges.sort(key=lambda r: (r[0] << 32) | r[1])

# The framebuffer ABL leaves running (cont_splash) is not RAM for Linux:
# cut it out and describe it as a simple-framebuffer, so the kernel
# console is on screen before the native display driver loads.
FB, FB_SIZE, FB_W, FB_H = 0xFC800000, 0x2B00000, 3200, 2136
cut = []
for hi, lo, shi, slo in ranges:
    a, n = (hi << 32) | lo, (shi << 32) | slo
    for s0, s1 in ((a, min(a + n, FB)), (max(a, FB + FB_SIZE), a + n)):
        if s1 > s0:
            cut.append([s0 >> 32, s0 & 0xffffffff, (s1 - s0) >> 32, (s1 - s0) & 0xffffffff])
ranges = cut
memory = ' '.join(f'0x{c:x}' for r in ranges for c in r)

# No serial console here; properties must also precede the new subnode.
dts = dts.replace('\t\tstdout-path = "serial0:115200n8";\n', '')
# Drop every memory node in the source, then add one with ABL's ranges.
dts = re.sub(r'\n\tmemory(@[0-9a-f]+)? \{.*?\n\t\};', '', dts, flags=re.S)
# Root properties come before subnodes, so add memory next to /chosen.
if '\n\tchosen {' not in dts:
    sys.exit('no /chosen node in the source')
dts = dts.replace('\n\tchosen {', f'\n\tmemory@0 {{\n\t\tdevice_type = "memory";\n'
                  f'\t\treg = <{memory}>;\n\t}};\n\n\tchosen {{\n'
                  '\t\tlinux,initrd-start = <0x0 0x0>;\n\t\tlinux,initrd-end = <0x0 0x0>;', 1)
# The framebuffer subnode goes at the end of /chosen, after its properties.
start = dts.index('\n\tchosen {')
end = dts.index('\n\t};', start)
if 'simple-framebuffer' not in dts:
    fb = ('\n\t\t#address-cells = <2>;\n\t\t#size-cells = <2>;\n\t\tranges;\n'
          f'\t\tframebuffer@{FB:x} {{\n\t\t\tcompatible = "simple-framebuffer";\n'
          f'\t\t\treg = <0x0 0x{FB:x} 0x0 0x{FB_SIZE:x}>;\n'
          f'\t\t\twidth = <{FB_W}>;\n\t\t\theight = <{FB_H}>;\n'
          f'\t\t\tstride = <{FB_W * 4}>;\n\t\t\tformat = "a8r8g8b8";\n\t\t}};')
    dts = dts[:end] + fb + dts[end:]
open(out, 'w').write(dts)
print(f'{len(ranges)} memory ranges')
