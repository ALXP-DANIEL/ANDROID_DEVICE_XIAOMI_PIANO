#!/usr/bin/env python3
"""Recolor one of Visor's white icons in place.

tint-icon.py ICON.png RRGGBB    every pixel becomes RRGGBB, alpha kept

Visor's OS icons are white shapes on transparency, so the alpha channel is
the whole drawing. Reads and writes 8-bit RGBA PNGs without Pillow.
"""
import struct
import sys
import zlib


def paeth(a, b, c):
    p = a + b - c
    pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
    return a if pa <= pb and pa <= pc else b if pb <= pc else c


def read_rgba(path):
    data = open(path, 'rb').read()
    pos, idat, ihdr = 8, b'', None
    while pos < len(data):
        n, kind = struct.unpack('>I4s', data[pos:pos + 8])
        body = data[pos + 8:pos + 8 + n]
        if kind == b'IHDR':
            ihdr = struct.unpack('>IIBBBBB', body)
        elif kind == b'IDAT':
            idat += body
        pos += 12 + n
    w, h, depth, ctype, _, _, interlace = ihdr
    if (depth, ctype, interlace) != (8, 6, 0):
        sys.exit(f'tint-icon: {path} is not 8-bit RGBA')
    raw, stride, rows, prev = zlib.decompress(idat), w * 4, [], bytearray(w * 4)
    for y in range(h):
        f = raw[y * (stride + 1)]
        line = bytearray(raw[y * (stride + 1) + 1:(y + 1) * (stride + 1)])
        for i in range(stride):
            a = line[i - 4] if i >= 4 else 0
            c = prev[i - 4] if i >= 4 else 0
            line[i] = (line[i] + (0, a, prev[i], (a + prev[i]) // 2,
                                  paeth(a, prev[i], c))[f]) & 0xFF
        rows.append(line)
        prev = line
    return w, h, rows


def write_rgba(path, w, h, rows):
    def chunk(kind, body):
        return (struct.pack('>I', len(body)) + kind + body +
                struct.pack('>I', zlib.crc32(kind + body) & 0xFFFFFFFF))

    raw = b''.join(b'\0' + bytes(r) for r in rows)
    with open(path, 'wb') as f:
        f.write(b'\x89PNG\r\n\x1a\n')
        f.write(chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 6, 0, 0, 0)))
        f.write(chunk(b'IDAT', zlib.compress(raw, 9)))
        f.write(chunk(b'IEND', b''))


if __name__ == '__main__':
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    rgb = bytes.fromhex(sys.argv[2])
    w, h, rows = read_rgba(sys.argv[1])
    for r in rows:
        for x in range(w):
            r[x * 4:x * 4 + 3] = rgb
    write_rgba(sys.argv[1], w, h, rows)
