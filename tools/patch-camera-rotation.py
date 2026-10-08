#!/usr/bin/env python3
#
# SPDX-License-Identifier: Apache-2.0
#
"""Patch the stock tablet MiuiCamera manifest so the camera handles rotation.

Edits typed attribute values in the binary AndroidManifest.xml in place and
copies every other APK entry unchanged:

- <application android:resizeableActivity> false -> true
- <activity> and <activity-alias> android:screenOrientation portrait ->
  fullSensor
- android:configChanges of the camera activities drops orientation,
  screenLayout and screenSize, so Android recreates them on rotation.
"""

import argparse
import hashlib
import struct
import zipfile
from pathlib import Path

STOCK_SHA256 = '810459635b5dff72495566356f65bb551ce03daf75820660f082425bb78e19c5'

ATTR_NAME = 0x01010003
ATTR_SCREEN_ORIENTATION = 0x0101001E
ATTR_CONFIG_CHANGES = 0x0101001F
ATTR_RESIZEABLE_ACTIVITY = 0x010104F6

SCREEN_ORIENTATION_PORTRAIT = 1
SCREEN_ORIENTATION_FULL_SENSOR = 10
CONFIG_ROTATION = 0x0080 | 0x0100 | 0x0400  # orientation|screenLayout|screenSize

CAMERA_ACTIVITIES = {
    'com.android.camera.Camera',
    'com.android.camera.OneShotCamera',
    'com.android.camera.OneShotLivephotoCamera',
}

RES_STRING_POOL_TYPE = 0x0001
RES_XML_RESOURCE_MAP_TYPE = 0x0180
RES_XML_START_ELEMENT_TYPE = 0x0102
UTF8_FLAG = 0x100


def read_strings(data, off):
    count, _, flags, strings_start = struct.unpack_from('<IIII', data, off + 8)
    offsets = struct.unpack_from(f'<{count}I', data, off + 28)
    base = off + strings_start
    strings = []
    for o in offsets:
        p = base + o
        if flags & UTF8_FLAG:
            p += 2 if data[p] & 0x80 else 1  # UTF-16 length
            n = data[p]
            if n & 0x80:
                n = ((n & 0x7F) << 8) | data[p + 1]
                p += 1
            strings.append(data[p + 1:p + 1 + n].decode('utf-8'))
        else:
            n = struct.unpack_from('<H', data, p)[0]
            if n & 0x8000:
                n = ((n & 0x7FFF) << 16) | struct.unpack_from('<H', data, p + 2)[0]
                p += 2
            strings.append(data[p + 2:p + 2 + n * 2].decode('utf-16-le'))
    return strings


def patch_manifest(data):
    data = bytearray(data)
    strings, res_ids = [], []
    changes = {'resizeable': 0, 'orientation': 0, 'config': 0}
    off = struct.unpack_from('<H', data, 2)[0]
    while off < len(data):
        ctype, _, csize = struct.unpack_from('<HHI', data, off)
        if ctype == RES_STRING_POOL_TYPE:
            strings = read_strings(data, off)
        elif ctype == RES_XML_RESOURCE_MAP_TYPE:
            res_ids = struct.unpack_from(f'<{(csize - 8) // 4}I', data, off + 8)
        elif ctype == RES_XML_START_ELEMENT_TYPE:
            tag = strings[struct.unpack_from('<I', data, off + 20)[0]]
            attr_start, attr_size, attr_count = struct.unpack_from('<HHH', data, off + 24)
            attrs = {}
            for i in range(attr_count):
                a = off + 16 + attr_start + i * attr_size
                name = struct.unpack_from('<I', data, a + 4)[0]
                if name < len(res_ids):
                    attrs[res_ids[name]] = a
            def value(attr):
                return struct.unpack_from('<I', data, attrs[attr] + 16)[0]
            def set_value(attr, v):
                struct.pack_into('<I', data, attrs[attr] + 16, v)
            if tag == 'application' and ATTR_RESIZEABLE_ACTIVITY in attrs:
                if value(ATTR_RESIZEABLE_ACTIVITY) == 0:
                    set_value(ATTR_RESIZEABLE_ACTIVITY, 0xFFFFFFFF)
                    changes['resizeable'] += 1
            if tag in ('activity', 'activity-alias'):
                if (ATTR_SCREEN_ORIENTATION in attrs and
                        value(ATTR_SCREEN_ORIENTATION) == SCREEN_ORIENTATION_PORTRAIT):
                    set_value(ATTR_SCREEN_ORIENTATION, SCREEN_ORIENTATION_FULL_SENSOR)
                    changes['orientation'] += 1
                raw_name = struct.unpack_from('<I', data, attrs[ATTR_NAME] + 8)[0]
                if (strings[raw_name] in CAMERA_ACTIVITIES and
                        ATTR_CONFIG_CHANGES in attrs):
                    set_value(ATTR_CONFIG_CHANGES,
                              value(ATTR_CONFIG_CHANGES) & ~CONFIG_ROTATION)
                    changes['config'] += 1
        off += csize
    if changes['resizeable'] != 1 or changes['config'] != len(CAMERA_ACTIVITIES):
        raise SystemExit(f'Unexpected manifest layout: {changes}')
    return bytes(data), changes


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('stock_apk', type=Path)
    parser.add_argument('output_apk', type=Path)
    args = parser.parse_args()

    if hashlib.sha256(args.stock_apk.read_bytes()).hexdigest() != STOCK_SHA256:
        raise SystemExit('Input differs from the Global 303 tablet MiuiCamera')

    with zipfile.ZipFile(args.stock_apk) as stock, \
            zipfile.ZipFile(args.output_apk, 'w') as out:
        for info in stock.infolist():
            content = stock.read(info)
            if info.filename == 'AndroidManifest.xml':
                content, changes = patch_manifest(content)
                print('Manifest changes:', changes)
            out.writestr(info, content)

    print('Patched APK SHA256:',
          hashlib.sha256(args.output_apk.read_bytes()).hexdigest())


if __name__ == '__main__':
    main()
