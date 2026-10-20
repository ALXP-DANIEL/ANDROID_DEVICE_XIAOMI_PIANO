#!/usr/bin/env python3
# SPDX-License-Identifier: BSD-2-Clause-Patent
"""Port byte patches from one build of a Qualcomm driver to another.

A reference pair (original, patched) from another SM8750 device shows what
changed. For each changed run, the surrounding instructions of the original
are turned into a signature with build-specific fields masked (branch and
ADRP/ADD/LDR immediates), then searched for in the target driver. A patch is
applied only where its signature matches exactly once.
"""
import argparse
import struct
import sys

CONTEXTS = (12, 24, 48, 96)   # instructions on each side, widened if ambiguous


def mask(insn):
    """Return (value, mask) for one AArch64 instruction word."""
    op = insn >> 26
    if op in (0b000101, 0b100101):            # B, BL: imm26
        return insn & 0xFC000000, 0xFC000000
    if (insn & 0x9F000000) in (0x90000000, 0x10000000):   # ADRP, ADR
        return insn & 0x9F00001F, 0x9F00001F
    if (insn & 0x7F800000) == 0x11000000:     # ADD/SUB (immediate), 64/32-bit
        return insn & 0xFFC003FF, 0xFFC003FF
    if (insn & 0x3B000000) == 0x39000000:     # LDR/STR (unsigned offset)
        return insn & 0xFFC003FF, 0xFFC003FF
    if (insn & 0x3B000000) == 0x18000000:     # LDR (literal)
        return insn & 0xFF00001F, 0xFF00001F
    if (insn & 0x7E000000) == 0x34000000 or (insn & 0xFF000010) == 0x54000000:
        return insn & 0xFF00001F, 0xFF00001F  # CBZ/CBNZ, B.cond
    return insn, 0xFFFFFFFF


def words(data, start, count):
    return [struct.unpack_from('<I', data, start + 4 * i)[0] for i in range(count)]


def runs(a, b):
    i = 0
    while i < len(a):
        if a[i] != b[i]:
            j = i
            while j < len(a) and a[j] != b[j]:
                j += 1
            yield i, j
            i = j
        else:
            i += 1


def find(sig, target):
    hits = []
    n = len(sig)
    for off in range(0, len(target) - 4 * n, 4):
        if all((w & m) == v for (v, m), w in zip(sig, words(target, off, n))):
            hits.append(off)
            if len(hits) > 1:
                break
    return hits


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('ref_orig')
    ap.add_argument('ref_patched')
    ap.add_argument('target')
    ap.add_argument('-o', '--output', required=True)
    a = ap.parse_args()

    orig, patched = open(a.ref_orig, 'rb').read(), open(a.ref_patched, 'rb').read()
    pristine = open(a.target, 'rb').read()
    target = bytearray(pristine)
    if len(orig) != len(patched):
        sys.exit('reference pair differs in size; port it by hand')

    ok = True
    for s, e in runs(orig, patched):
        for ctx in CONTEXTS:
            base = max((s & ~3) - 4 * ctx, 0)
            count = min((((e + 3) & ~3) - base) // 4 + ctx, (len(orig) - base) // 4)
            sig = [mask(w) for w in words(orig, base, count)]
            hits = find(sig, pristine)    # search the unmodified target
            if len(hits) < 2:
                break
        if not hits:
            # Function bodies drift between builds: try just the code that
            # follows the patch, shortest unique run first.
            for n in (6, 8, 12, 16, 24):
                base = s & ~3
                if base + 4 * n > len(orig):
                    break
                sig = [mask(w) for w in words(orig, base, n)]
                hits = find(sig, pristine)
                if len(hits) == 1:
                    break
        if not hits or len(hits) > 1:
            # Data, not code: match the raw bytes around it.
            for n in (8, 16, 32, 64):
                lo, hi = max(s - n, 0), min(e + n, len(orig))
                window = orig[lo:hi]
                first = pristine.find(window)
                if first >= 0 and pristine.find(window, first + 1) < 0:
                    hits, base = [first + (s - lo) - (s - lo)], lo
                    break
        if len(hits) != 1:
            print(f'  {s:#x}: {len(hits)} matches, not applied')
            ok = False
            continue
        t = hits[0] + (s - base)
        target[t:t + (e - s)] = patched[s:e]
        print(f'  {s:#x} -> {t:#x}: {orig[s:e].hex()} -> {patched[s:e].hex()}')

    open(a.output, 'wb').write(target)
    sys.exit(0 if ok else 1)


if __name__ == '__main__':
    main()
