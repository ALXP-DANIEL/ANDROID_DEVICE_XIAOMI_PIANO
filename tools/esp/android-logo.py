#!/usr/bin/env python3
"""Draw the Android robot's head as Visor's icon for the Android entry.

android-logo.py OUT.png    320x320 RGBA in Android green, the size of Visor's
                           icons; with accent=1 Visor tints it like the others

The Android robot is reproduced or modified from work created and shared by
Google and used according to terms described in the Creative Commons 3.0
Attribution License.
"""
import math
import struct
import sys
import zlib

SS = 4  # samples per pixel per axis


def inside(x, y):
    """Head, antennas and eyes on a 320x320 canvas."""
    cx, cy, r = 160.0, 232.0, 120.0
    head = y <= cy and math.hypot(x - cx, y - cy) <= r
    for side in (-1, 1):
        # Antennas leave the head 60 degrees off the horizontal.
        a = math.radians(60)
        x0, y0 = cx + side * r * 0.62 * math.cos(a), cy - r * 0.62 * math.sin(a)
        x1, y1 = cx + side * (r + 40) * math.cos(a), cy - (r + 40) * math.sin(a)
        dx, dy = x1 - x0, y1 - y0
        t = max(0.0, min(1.0, ((x - x0) * dx + (y - y0) * dy) / (dx * dx + dy * dy)))
        if math.hypot(x - (x0 + t * dx), y - (y0 + t * dy)) <= 8:
            head = True
        if math.hypot(x - (cx + side * 52), y - (cy - 62)) <= 13:
            return False
    return head


def coverage(size):
    """Fraction of each pixel covered, drawn at SIZE x SIZE."""
    k = 320.0 / size
    rows = []
    for py in range(size):
        row = []
        for px in range(size):
            n = 0
            for sy in range(SS):
                for sx in range(SS):
                    n += inside((px + (sx + 0.5) / SS) * k, (py + (sy + 0.5) / SS) * k)
            row.append(n / (SS * SS))
        rows.append(row)
    return rows


def write_png(path, cov):
    size = len(cov)
    raw = b''.join(b'\0' + b''.join(bytes((0x3D, 0xDC, 0x84, round(c * 255))) for c in row)
                   for row in cov)

    def chunk(kind, data):
        return (struct.pack('>I', len(data)) + kind + data +
                struct.pack('>I', zlib.crc32(kind + data) & 0xFFFFFFFF))

    with open(path, 'wb') as f:
        f.write(b'\x89PNG\r\n\x1a\n')
        f.write(chunk(b'IHDR', struct.pack('>IIBBBBB', size, size, 8, 6, 0, 0, 0)))
        f.write(chunk(b'IDAT', zlib.compress(raw, 9)))
        f.write(chunk(b'IEND', b''))


if __name__ == '__main__':
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    write_png(sys.argv[1], coverage(320))
