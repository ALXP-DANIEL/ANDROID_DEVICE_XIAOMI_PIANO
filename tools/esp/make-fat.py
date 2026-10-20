#!/usr/bin/env python3
# SPDX-License-Identifier: BSD-2-Clause-Patent
"""Pack a directory into a FAT16 disk image, for a newly created ESP.

make-fat.py DIR OUT.img

The image has no partition table: the installer writes it inside the new ESP. Long file names are written as VFAT
entries, so Visor finds its files by their real names. Plain Python, so it
runs the same on macOS and in CI without mtools.
"""
import os
import struct
import sys

SECTOR = 4096
CLUSTER = 4096                 # one native UFS sector
ROOT_ENTRIES = 512
FAT16_MIN, FAT16_MAX = 4085, 65524


def short_name(name, used):
    """An 8.3 alias unique within its directory, plus whether it is exact."""
    base, _, ext = name.upper().rpartition('.') if '.' in name else (name.upper(), '', '')
    if not base:
        base, ext = ext, ''
    clean = lambda s: ''.join(c for c in s if c.isalnum() or c in '_-$~!#%&()@^{}')
    b, e = clean(base), clean(ext)[:3]
    if b == base and len(b) <= 8 and e == ext.upper() and len(ext) <= 3 and name == name.upper():
        alias = b.ljust(8) + e.ljust(3)
        if alias not in used:
            return alias, True
    for n in range(1, 1000000):
        tail = f'~{n}'
        alias = (b[:8 - len(tail)] + tail).ljust(8) + e.ljust(3)
        if alias not in used:
            return alias, False
    raise SystemExit('make-fat: too many similar names')


def lfn_entries(name, alias):
    csum = 0
    for c in alias.encode('ascii'):
        csum = (((csum & 1) << 7) + (csum >> 1) + c) & 0xFF
    units = list(name.encode('utf-16-le'))
    chars = [units[i] | units[i + 1] << 8 for i in range(0, len(units), 2)] + [0]
    while len(chars) % 13:
        chars.append(0xFFFF)
    parts = [chars[i:i + 13] for i in range(0, len(chars), 13)]
    out = []
    for seq, part in enumerate(parts, 1):
        order = seq | (0x40 if seq == len(parts) else 0)
        u = lambda a, b: b''.join(struct.pack('<H', c) for c in part[a:b])
        out.append(bytes([order]) + u(0, 5) + bytes([0x0F, 0, csum]) + u(5, 11) +
                   b'\0\0' + u(11, 13))
    return out[::-1]


def entry(alias, attr, cluster, size):
    return (alias.encode('ascii') + bytes([attr, 0, 0]) + b'\0' * 6 +
            struct.pack('<H', 0) + b'\0' * 4 + struct.pack('<HI', cluster, size))


class Fat:
    def __init__(self, clusters):
        self.fat = [0xFFF8, 0xFFFF] + [0] * clusters
        self.data = {}
        self.next = 2

    def alloc(self, payload):
        """Store PAYLOAD in a cluster chain; returns its first cluster (0 if empty)."""
        n = (len(payload) + CLUSTER - 1) // CLUSTER
        if n == 0:
            return 0
        first = self.next
        for i in range(n):
            c = self.next + i
            self.fat[c] = c + 1 if i < n - 1 else 0xFFFF
            self.data[c] = payload[i * CLUSTER:(i + 1) * CLUSTER]
        self.next += n
        return first


def build(path, fat, me):
    """Directory entries for PATH (whose own cluster is ME, 0 for the root);
    its files and subdirectories go into the FAT on the way."""
    entries, used = [], set()
    for name in sorted(os.listdir(path)):
        full = os.path.join(path, name)
        alias, exact = short_name(name, used)
        used.add(alias)
        lfn = [] if exact else lfn_entries(name, alias)
        if os.path.isdir(full):
            entries += lfn + [entry(alias, 0x10, build_dir(full, fat, me), 0)]
        else:
            data = open(full, 'rb').read()
            entries += lfn + [entry(alias, 0x20, fat.alloc(data), len(data))]
    return entries


def build_dir(path, fat, parent):
    """Store subdirectory PATH; returns its first cluster. Its size is known
    from its entry count, so its clusters are taken before its contents."""
    used, count = set(), 2
    for name in sorted(os.listdir(path)):
        alias, exact = short_name(name, used)
        used.add(alias)
        count += 1 + (0 if exact else len(lfn_entries(name, alias)))
    size = (count * 32 + CLUSTER - 1) // CLUSTER * CLUSTER
    me = fat.alloc(b'\0' * size)
    body = [entry('.'.ljust(11), 0x10, me, 0), entry('..'.ljust(11), 0x10, parent, 0)]
    raw = b''.join(body + build(path, fat, me)).ljust(size, b'\0')
    for i in range(size // CLUSTER):        # alloc() made the chain contiguous
        fat.data[me + i] = raw[i * CLUSTER:(i + 1) * CLUSTER]
    return me


def main():
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    src, out = sys.argv[1], sys.argv[2]
    total = sum(os.path.getsize(os.path.join(d, f)) for d, _, fs in os.walk(src) for f in fs)
    files = sum(len(fs) + len(ds) for _, ds, fs in os.walk(src))
    # About 117 MiB of FAT16 capacity leaves room for future Visor updates.
    # The BPB sector must match piano UFS logical blocks (4096 bytes).
    clusters = max(30000, (total + files * CLUSTER) * 5 // 4 // CLUSTER + 64)
    if clusters > FAT16_MAX:
        sys.exit('make-fat: too large for FAT16')
    fat_sectors = ((clusters + 2) * 2 + SECTOR - 1) // SECTOR
    root_sectors = ROOT_ENTRIES * 32 // SECTOR
    reserved = 4
    data_start = reserved + 2 * fat_sectors + root_sectors
    sectors = data_start + clusters * (CLUSTER // SECTOR)

    fat = Fat(clusters)
    root = b''.join(build(src, fat, 0))
    if len(root) > ROOT_ENTRIES * 32:
        sys.exit('make-fat: too many files in the root directory')

    img = bytearray(sectors * SECTOR)
    boot = bytearray(SECTOR)
    boot[0:3] = b'\xEB\x3C\x90'
    boot[3:11] = b'PIANOESP'
    struct.pack_into('<HBHBHHBHHHII', boot, 11, SECTOR, CLUSTER // SECTOR, reserved, 2,
                     ROOT_ENTRIES, sectors if sectors < 0x10000 else 0, 0xF8, fat_sectors,
                     63, 255, 0, sectors if sectors >= 0x10000 else 0)
    struct.pack_into('<BBBI', boot, 36, 0x80, 0, 0x29, 0x50494E4F)
    boot[43:54] = b'ESP        '
    boot[54:62] = b'FAT16   '
    boot[510:512] = b'\x55\xAA'
    img[0:SECTOR] = boot
    fat_bytes = b''.join(struct.pack('<H', v) for v in fat.fat).ljust(fat_sectors * SECTOR, b'\0')
    for i in range(2):
        o = (reserved + i * fat_sectors) * SECTOR
        img[o:o + len(fat_bytes)] = fat_bytes
    o = (reserved + 2 * fat_sectors) * SECTOR
    img[o:o + len(root)] = root
    for c, chunk in fat.data.items():
        o = (data_start + (c - 2) * (CLUSTER // SECTOR)) * SECTOR
        img[o:o + len(chunk)] = chunk
    open(out, 'wb').write(img)
    print(f'{out}: {len(img)} bytes, {total} bytes in {files} entries')


if __name__ == '__main__':
    main()
