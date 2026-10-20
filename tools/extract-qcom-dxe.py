#!/usr/bin/env python3
# SPDX-License-Identifier: BSD-2-Clause-Patent
"""Turn a Qualcomm uefi.img into a Mu-Silicium Binaries/<device> tree.

Every driver in the image's firmware volume becomes
QcomPkg/Drivers/<Name>/<Name>.{efi,depex,inf}, and every raw file (panel
XML, configs, logo) lands in RawFiles/ and is listed in RAW.inc. These are the device's own
proprietary drivers; a patched copy is kept in uefi/Binaries/piano.

Needs the uefi_firmware Python package.
"""
import argparse
import os
import sys
import uuid

import uefi_firmware
from uefi_firmware.uefi import FirmwareFile, FirmwareFileSystemSection

# FFS file types we keep, mapped to EDK2 module types.
MODULE_TYPES = {
    0x05: 'DXE_CORE',
    0x07: 'DXE_DRIVER',
    0x09: 'UEFI_APPLICATION',
}
RAW_FILES = (0x01, 0x02)		# raw and freeform files
SECTION_PE32, SECTION_TE, SECTION_DXE_DEPEX, SECTION_UI, SECTION_RAW = 0x10, 0x12, 0x13, 0x15, 0x19

INF = """[Defines]
  INF_VERSION                    = 0x0001001B
  BASE_NAME                      = {name}
  FILE_GUID                      = {guid}
  MODULE_TYPE                    = {mtype}
  VERSION_STRING                 = 1.0

[Binaries.AARCH64]
{binaries}
[Depex]
  TRUE
"""


def walk(obj):
    yield obj
    for child in getattr(obj, 'objects', []) or []:
        yield from walk(child)


def sections(ffs):
    for obj in walk(ffs):
        if isinstance(obj, FirmwareFileSystemSection):
            yield obj


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('uefi_img')
    ap.add_argument('out', help='e.g. deps/Mu-Silicium/Binaries/piano')
    a = ap.parse_args()

    data = open(a.uefi_img, 'rb').read()
    fvh = data.find(b'_FVH')
    if fvh < 40:
        sys.exit('no firmware volume found')
    root = uefi_firmware.AutoParser(data[fvh - 40:]).parse()

    drivers = os.path.join(a.out, 'QcomPkg', 'Drivers')
    raw_dir = os.path.join(a.out, 'RawFiles')
    os.makedirs(drivers, exist_ok=True)
    os.makedirs(raw_dir, exist_ok=True)
    count = 0
    raw_inc = []

    for ffs in walk(root):
        if not isinstance(ffs, FirmwareFile):
            continue
        secs = list(sections(ffs))
        name = next((s.name for s in secs if s.type == SECTION_UI and s.name), None)
        if not name:
            continue
        name = name.strip('\0').strip()
        if ffs.type in RAW_FILES:
            raw = next((s for s in secs if s.type == SECTION_RAW), None)
            payload = raw.data if raw else ffs.data
            open(os.path.join(raw_dir, name), 'wb').write(payload)
            raw_inc.append(f'  FILE FREEFORM = {str(uuid.UUID(bytes_le=ffs.guid)).upper()} {{\n'
                           f'    SECTION UI = "{name}"\n'
                           f'    SECTION RAW = Binaries/{os.path.basename(os.path.normpath(a.out))}/RawFiles/{name}\n  }}\n')
            continue
        if ffs.type not in MODULE_TYPES:
            continue
        image = next((s for s in secs if s.type in (SECTION_PE32, SECTION_TE)), None)
        if not image:
            continue
        depex = next((s for s in secs if s.type == SECTION_DXE_DEPEX), None)
        d = os.path.join(drivers, name)
        os.makedirs(d, exist_ok=True)
        kind = 'PE32' if image.type == SECTION_PE32 else 'TE'
        ext = 'efi' if kind == 'PE32' else 'te'
        open(os.path.join(d, f'{name}.{ext}'), 'wb').write(image.data)
        lines = []
        if depex:
            open(os.path.join(d, f'{name}.depex'), 'wb').write(depex.data)
            lines.append(f'  DXE_DEPEX|{name}.depex|*')
        lines.append(f'  {kind}|{name}.{ext}|*')
        open(os.path.join(d, f'{name}.inf'), 'w').write(INF.format(
            name=name, guid=str(uuid.UUID(bytes_le=ffs.guid)).upper(),
            mtype=MODULE_TYPES[ffs.type], binaries='\n'.join(lines) + '\n'))
        count += 1

    open(os.path.join(a.out, 'RAW.inc'), 'w').write('\n'.join(raw_inc))
    print(f'{count} drivers, {len(os.listdir(raw_dir))} raw files -> {a.out}')


if __name__ == '__main__':
    main()
