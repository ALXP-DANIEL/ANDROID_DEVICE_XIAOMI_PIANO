#!/usr/bin/env python3
"""Append app-local compatibility DEX while preserving Notes code and resources."""
import argparse
import hashlib
from pathlib import Path
import re
import struct
import zipfile


def definitions(data):
    if not data.startswith(b"dex\n"):
        raise ValueError("expected standard DEX")
    u32 = lambda offset: struct.unpack_from("<I", data, offset)[0]
    strings = []
    for index in range(u32(56)):
        pos = u32(u32(60) + index * 4)
        while data[pos] & 128:
            pos += 1
        pos += 1
        # DEX uses modified UTF-8: embedded NUL is C0 80 and UTF-16
        # surrogate code units are encoded separately.
        raw = data[pos:data.index(0, pos)].replace(b"\xc0\x80", b"\x00")
        strings.append(raw.decode("utf-8", "surrogatepass"))
    types = [strings[u32(u32(68) + index * 4)] for index in range(u32(64))]
    return {types[u32(u32(100) + index * 32)] for index in range(u32(96))}


def append(stock, compat, output, features):
    metadata = Path(features).read_bytes()
    if hashlib.sha256(metadata).hexdigest() != "22f2f00ad81f499354a02e313955ae47a72fad34d14d9de83d762f7782501931":
        raise ValueError("piano feature metadata is not the pinned .44 file")
    with zipfile.ZipFile(stock) as original, zipfile.ZipFile(compat) as library:
        dex_names = [name for name in library.namelist()
                     if re.fullmatch(r"classes(?:[2-9]|[1-9][0-9]+)?\.dex", name)]
        if dex_names != ["classes.dex"]:
            raise ValueError("expected one compatibility DEX")
        payload = library.read("classes.dex")
        added = definitions(payload)
        if added != {"Lmiui/os/Build;", "Lmiui/util/FeatureParser;"}:
            raise ValueError("unexpected compatibility classes")
        existing = set()
        indices = []
        for name in original.namelist():
            match = re.fullmatch(r"classes([2-9]|[1-9][0-9]+)?\.dex", name)
            if match:
                existing |= definitions(original.read(name))
                indices.append(int(match[1] or "1"))
        if not indices or existing & added:
            raise ValueError("missing Notes DEX or duplicate compatibility class")
        destination = "classes%d.dex" % (max(indices) + 1)
        if "assets/piano-device-features.xml" in original.namelist():
            raise ValueError("compatibility metadata already present")
        with zipfile.ZipFile(output, "w") as target:
            for info in original.infolist():
                # The staged input has no valid signature. Never retain obsolete
                # signing entries if another unsigned diagnostic input is used.
                name = info.filename.upper()
                if name.startswith("META-INF/") and (
                        name.endswith((".SF", ".RSA", ".DSA", ".EC"))
                        or name == "META-INF/MANIFEST.MF"):
                    continue
                target.writestr(info, original.read(info.filename))
            for name, data, compression in (
                    (destination, payload, zipfile.ZIP_STORED),
                    ("assets/piano-device-features.xml", metadata, zipfile.ZIP_DEFLATED)):
                info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
                info.compress_type = compression
                info.external_attr = 0o100644 << 16
                target.writestr(info, data)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("stock")
    parser.add_argument("compat")
    parser.add_argument("output")
    parser.add_argument("features")
    args = parser.parse_args()
    append(args.stock, args.compat, args.output, args.features)
