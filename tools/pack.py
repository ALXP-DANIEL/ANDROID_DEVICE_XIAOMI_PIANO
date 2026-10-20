#!/usr/bin/env python3
# SPDX-License-Identifier: BSD-2-Clause-Patent
"""Wrap a boot.img (header v4, raw arm64 GKI kernel) with the selector.

The selector goes after the kernel's memory footprint (image_size), and the
kernel's second instruction, its branch to primary_entry, is redirected to
the selector. The selector branches back to primary_entry, so the kernel
itself runs unchanged. image_size grows so the bootloader reserves room for
the selector. Any AVB footer is dropped; this image is for unlocked devices.
"""
import argparse
import hashlib
import os
import struct
import subprocess
import sys

PAGE = 4096
META_MAGIC = b'PIANO-UEFI-SEL1\0'
BRANCH_RANGE = 1 << 27          # B imm26 reaches +-128 MiB
ABL_KERNEL_BASE = 0xA8010000    # where piano's ABL places the kernel


def fail(msg):
    sys.exit(f'pack: {msg}')


def align(n, a=PAGE):
    return (n + a - 1) // a * a


def read_boot(data):
    if data[:8] != b'ANDROID!':
        fail('not an Android boot image')
    kernel_size, ramdisk_size, os_version, header_size = struct.unpack_from('<4I', data, 8)
    header_version = struct.unpack_from('<I', data, 40)[0]
    if header_version not in (3, 4):
        fail(f'boot header v{header_version}; only v3/v4 are supported')
    sig_size = struct.unpack_from('<I', data, 1580)[0] if header_version == 4 else 0
    off = PAGE
    kernel = data[off:off + kernel_size]
    off += align(kernel_size)
    ramdisk = data[off:off + ramdisk_size]
    off += align(ramdisk_size)
    signature = data[off:off + sig_size]
    return {'header': bytearray(data[:header_size]), 'kernel': kernel,
            'ramdisk': ramdisk, 'signature': signature, 'version': header_version}


def kernel_info(kernel):
    if kernel[:2] != b'MZ' or kernel[56:60] != b'ARMd':
        fail('kernel is not a raw arm64 Image (compressed, or already wrapped?)')
    code0, code1, text_offset, image_size, flags = struct.unpack_from('<IIQQQ', kernel)
    if code1 >> 26 != 0b000101:
        fail('kernel code1 is not a branch to primary_entry')
    imm = code1 & 0x3ffffff
    if imm & 0x2000000:
        imm -= 0x4000000
    target = 4 + imm * 4
    if not 64 <= target < len(kernel):
        fail('primary_entry branch target is outside the kernel')
    if image_size < len(kernel) or flags & 1:
        fail('unexpected image_size or big-endian kernel')
    if META_MAGIC in kernel:
        fail('kernel already contains a selector')
    return code1, image_size


def wrap(kernel, selector, uefi=b''):
    code1, image_size = kernel_info(kernel)
    meta = selector.find(META_MAGIC)
    if meta < 0 or selector.find(META_MAGIC, meta + 1) >= 0:
        fail('selector metadata block not found exactly once')

    sel_off = align(image_size)        # past the kernel's BSS, which Linux clears
    uefi_off = align(sel_off + len(selector) + 16384, 0x10000) if uefi else 0
    end = (uefi_off + len(uefi)) if uefi else sel_off + len(selector) + 16384
    total = align(end)
    if sel_off - 4 >= BRANCH_RANGE:
        fail('selector out of branch range')

    out = bytearray(uefi_off + len(uefi) if uefi else sel_off + len(selector))
    out[:len(kernel)] = kernel
    out[sel_off:sel_off + len(selector)] = selector
    if uefi:
        out[uefi_off:uefi_off + len(uefi)] = uefi
    struct.pack_into('<QIIQQQQ', out, sel_off + meta + 16, sel_off, code1,
                     image_size & 0xffffffff, image_size, uefi_off, len(uefi), len(kernel))
    struct.pack_into('<I', out, 4, 0x14000000 | ((sel_off - 4) // 4))
    struct.pack_into('<Q', out, 16, total)

    # Undoing the two header edits must give back the original kernel.
    check = bytearray(out[:len(kernel)])
    struct.pack_into('<I', check, 4, code1)
    struct.pack_into('<Q', check, 16, image_size)
    if bytes(check) != kernel:
        fail('internal error: kernel bytes changed beyond the header')
    return bytes(out), sel_off, total


def write_boot(boot, kernel):
    header = boot['header']
    struct.pack_into('<I', header, 8, len(kernel))
    if boot['version'] == 4:
        struct.pack_into('<I', header, 1580, 0)      # old signature no longer matches
    out = bytearray(header) + bytes(PAGE - len(header))
    for part in (kernel, boot['ramdisk']):
        out += part + bytes(align(len(part)) - len(part))
    return bytes(out)


def add_footer(a, original):
    info = subprocess.run([sys.executable, a.avbtool, 'info_image', '--image', original],
                          check=True, capture_output=True, text=True).stdout
    size = rollback = None
    props = []
    for line in info.splitlines():
        line = line.strip()
        if line.startswith('Image size:'):
            size = line.split()[2]
        elif line.startswith('Rollback Index:'):
            rollback = line.split()[2]
        elif line.startswith('Prop: '):
            key, val = line[6:].split(' -> ', 1)
            props += ['--prop', f"{key}:{val.strip(chr(39))}"]
    if not size:
        fail('original boot.img has no AVB footer')
    subprocess.run([sys.executable, a.avbtool, 'add_hash_footer', '--image', a.output,
                    '--partition_name', 'boot', '--partition_size', size,
                    '--algorithm', 'SHA256_RSA4096', '--key', a.avb_key,
                    '--rollback_index', rollback or '0'] + props, check=True)


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('boot', help='input boot.img, or a raw kernel Image with --kernel-only')
    ap.add_argument('selector', help='selector.bin')
    ap.add_argument('-o', '--output', required=True)
    ap.add_argument('--kernel-only', action='store_true',
                    help='wrap a kernel Image and write the wrapped Image; a ROM build '
                    'that uses it as its prebuilt kernel then keeps the selector in every '
                    'boot.img and OTA it makes')
    ap.add_argument('--avb', metavar='ORIGINAL',
                    help='sign for flashing: copy partition size, rollback index and '
                    'props from this original boot.img and add a hash footer '
                    '(fastboot boot does not need it; a flashed boot does)')
    ap.add_argument('--avb-key', default=os.path.join(os.path.dirname(__file__), '..',
                    'deps', 'avb', 'testkey_rsa4096.pem'))
    ap.add_argument('--avbtool', default=os.path.join(os.path.dirname(__file__), '..',
                    'deps', 'avb', 'avbtool.py'))
    ap.add_argument('--uefi', help='Mu-Silicium boot.img whose kernel (BootShim + FD) '
                    'started instead of Android')
    a = ap.parse_args()

    data = open(a.boot, 'rb').read()
    boot = {'kernel': data} if a.kernel_only else read_boot(data)
    uefi = read_boot(open(a.uefi, 'rb').read())['kernel'] if a.uefi else b''
    kernel, sel_off, total = wrap(boot['kernel'], open(a.selector, 'rb').read(), uefi)
    image = kernel if a.kernel_only else write_boot(boot, kernel)
    if a.kernel_only and a.avb:
        fail('--avb is for boot images; the ROM build signs its own')
    open(a.output, 'wb').write(image)
    if a.avb:
        add_footer(a, a.avb)
        image = open(a.output, 'rb').read()
    print(f'kernel {len(boot["kernel"])} bytes, selector at {sel_off:#x}, '
          f'image_size {total:#x}, boot.img {len(image)} bytes')
    print(f'sha256 {hashlib.sha256(image).hexdigest()}  {a.output}')


if __name__ == '__main__':
    main()
