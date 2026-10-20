#!/usr/bin/env python3
"""Run inside the build container: python3 tools/inject/test-esp.py.

Only synthetic regular files are inspected. No tablet or block device writes.
"""
import pathlib
import struct
import subprocess
import tempfile
import unittest
import zlib

ROOT = pathlib.Path(__file__).resolve().parents[2]
ESP = bytes.fromhex('28732ac11ff8d211ba4b00a0c93ec93b')


def fixture(path, entries, sectors=2048):
    table = bytearray(8192)
    for index, kind, start, end, name in entries:
        off = (index - 1) * 128
        table[off:off + 16] = kind
        table[off + 16:off + 32] = bytes([index]) * 16
        struct.pack_into('<QQ', table, off + 32, start, end)
        text = name.encode('utf-16-le')
        table[off + 56:off + 56 + len(text)] = text
    def header(lba, other, table_lba):
        h = bytearray(4096)
        struct.pack_into('<8sIIIIQQQQ16sQIII', h, 0, b'EFI PART', 0x10000,
                         92, 0, 0, lba, other, 6, sectors - 6,
                         bytes(range(16)), table_lba, 64, 128, zlib.crc32(table))
        struct.pack_into('<I', h, 16, zlib.crc32(h[:92]))
        return h
    with path.open('wb') as f:
        f.truncate(sectors * 4096)
        for pos, data in [(4096, header(1, sectors - 1, 2)), (8192, table),
                          ((sectors - 3) * 4096, table),
                          ((sectors - 1) * 4096, header(sectors - 1, 1, sectors - 3))]:
            f.seek(pos)
            f.write(data)


class GPTTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        subprocess.run(['gcc', '-O2', '-Wall', '-Wextra', '-Werror', '-o',
                        '/tmp/piano-esp-test', str(ROOT / 'tools/inject/piano-esp.c')], check=True)

    def inspect(self, entries, mutate=None):
        with tempfile.TemporaryDirectory() as tmp:
            disk = pathlib.Path(tmp) / 'disk'
            fixture(disk, entries)
            if mutate:
                with disk.open('r+b') as f:
                    mutate(f)
            before = disk.read_bytes()
            result = subprocess.run(['/tmp/piano-esp-test', 'inspect', str(disk), '--offline'], capture_output=True, text=True)
            self.assertEqual(before, disk.read_bytes(), 'inspect modified disk')
            return result

    def test_no_esp(self):
        r = self.inspect([(1, b'X'*16, 6, 2042, 'userdata')])
        self.assertEqual((r.returncode, r.stdout), (0, '0\n'))

    def test_existing_esp(self):
        r = self.inspect([(1, b'X'*16, 6, 1000, 'userdata'), (2, ESP, 1001, 2042, 'sunuefi_esp')])
        self.assertEqual((r.returncode, r.stdout), (0, '2\n'))

    def test_mislabelled_esp(self):
        self.assertNotEqual(self.inspect([(1, b'X'*16, 6, 2042, 'esp')]).returncode, 0)

    def test_duplicate_esp(self):
        self.assertNotEqual(self.inspect([(1, ESP, 6, 1000, 'esp'), (2, ESP, 1001, 2042, 'other')]).returncode, 0)

    def test_overlap(self):
        self.assertNotEqual(self.inspect([(1, b'X'*16, 6, 1000, 'userdata'), (2, ESP, 900, 2042, 'esp')]).returncode, 0)

    def test_bad_checksum(self):
        def corrupt(f):
            f.seek(8192)
            f.write(b'!')
        self.assertNotEqual(self.inspect([(1, ESP, 6, 2042, 'esp')], corrupt).returncode, 0)

    def test_write_refuses_regular_file(self):
        r = subprocess.run(['/tmp/piano-esp-test', 'create', '/tmp/disk', '/tmp/backup', '/tmp/esp.img', '--execute'], capture_output=True)
        self.assertNotEqual(r.returncode, 0)
        self.assertIn(b'Writes only permitted', r.stderr)

    def test_fat_matches_native_ufs_sector(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = pathlib.Path(tmp)
            src = tmp / 'esp'
            src.mkdir()
            (src / 'probe.txt').write_bytes(b'Visor native sector test')
            image = tmp / 'esp.img'
            subprocess.run(['python3', str(ROOT / 'tools/esp/make-fat.py'),
                            str(src), str(image)], check=True, capture_output=True)
            data = image.read_bytes()
            sector = struct.unpack_from('<H', data, 11)[0]
            self.assertEqual(sector, 4096)
            self.assertGreaterEqual(data[13], 1)
            self.assertEqual(data[510:512], b'\x55\xaa')
            reserved = struct.unpack_from('<H', data, 14)[0]
            fat_sectors = struct.unpack_from('<H', data, 22)[0]
            root_entries = struct.unpack_from('<H', data, 17)[0]
            sectors = struct.unpack_from('<H', data, 19)[0] or struct.unpack_from('<I', data, 32)[0]
            clusters = (sectors - reserved - 2 * fat_sectors - root_entries * 32 // sector) // data[13]
            self.assertTrue(4085 <= clusters < 65525)
            self.assertEqual(len(data), sectors * sector)
            root_offset = (reserved + 2 * fat_sectors) * sector
            root = data[root_offset:root_offset + root_entries * 32]
            self.assertEqual(root[32:43], b'PROBE~1 TXT')

    def test_inject_without_embedded_esp_roundtrip(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = pathlib.Path(tmp)
            injector = tmp / 'inject'
            subprocess.run(['gcc', '-O2', '-Wall', '-Wextra', '-Werror', '-o',
                            str(injector), str(ROOT / 'tools/inject/piano-inject.c')], check=True)
            kernel = bytearray(4096)
            kernel[:2] = b'MZ'
            struct.pack_into('<I', kernel, 4, 0x14000010)
            struct.pack_into('<Q', kernel, 16, len(kernel))
            kernel[56:60] = b'ARMd'
            boot = bytearray(4096) + kernel
            boot[:8] = b'ANDROID!'
            struct.pack_into('<I', boot, 8, len(kernel))
            struct.pack_into('<I', boot, 20, 1580)
            struct.pack_into('<I', boot, 40, 3)
            original = tmp / 'boot.img'
            original.write_bytes(boot + bytes(262144 - len(boot)))
            selector = tmp / 'selector'
            selector.write_bytes(b'PIANO-UEFI-SEL1'.ljust(16, b'\0') + bytes(64))
            uefi = tmp / 'uefi'
            uefi.write_bytes(b'UEFI-TEST')
            patched = tmp / 'patched'
            subprocess.run([str(injector), str(original), str(selector), str(uefi),
                            '--no-esp', str(patched)], check=True, capture_output=True)
            data = patched.read_bytes()
            metadata = data.index(b'PIANO-UEFI-SEL1')
            self.assertEqual(struct.unpack_from('<QQ', data, metadata + 64), (0, 0))
            restored = tmp / 'restored'
            subprocess.run([str(injector), '--remove', str(patched), str(restored)], check=True, capture_output=True)
            restored_data = restored.read_bytes()
            self.assertEqual(restored_data[:len(boot)], boot)
            # Both install and uninstall need fresh parseable metadata, not a
            # stale footer or an image with no AVB container.
            import hashlib
            for image in (data, restored_data):
                self.assertEqual(len(image), 262144)
                footer = image[-64:]
                self.assertEqual(footer[:4], b'AVBf')
                image_size, offset, vbmeta_size = struct.unpack_from('>QQQ', footer, 12)
                self.assertEqual(vbmeta_size, 448)
                self.assertEqual(image[offset:offset+4], b'AVB0')
                desc = image[offset+256:offset+424]
                self.assertEqual(struct.unpack_from('>Q', desc, 16)[0], image_size)
                self.assertEqual(desc[132:136], b'boot')
                self.assertEqual(desc[136:168], hashlib.sha256(image[:image_size]).digest())
            # Boot properties/rollback feed Android KeyMint. Preserve their
            # descriptors byte-for-byte while replacing the content hash.
            key, value = b'com.android.build.boot.security_patch', b'2026-09-01'
            prop_len = (32 + len(key) + len(value) + 2 + 7) & ~7
            prop = (struct.pack('>QQQQ', 0, prop_len - 16, len(key), len(value)) +
                    key + b'\0' + value + b'\0').ljust(prop_len, b'\0')
            signed = bytearray(262144)
            signed[:len(boot)] = boot
            offset = len(boot)
            signed[offset:offset+4] = b'AVB0'
            struct.pack_into('>I', signed, offset+4, 1)
            struct.pack_into('>Q', signed, offset+20, (prop_len+63)&~63)
            struct.pack_into('>Q', signed, offset+104, prop_len)
            struct.pack_into('>Q', signed, offset+112, 1788220800)
            signed[offset+256:offset+256+prop_len] = prop
            signed[-64:-60] = b'AVBf'
            struct.pack_into('>I', signed, len(signed)-60, 1)
            struct.pack_into('>QQQ', signed, len(signed)-52, len(boot), offset, 256+((prop_len+63)&~63))
            original.write_bytes(signed)
            subprocess.run([str(injector), str(original), str(selector), str(uefi),
                            '--no-esp', str(patched)], check=True, capture_output=True)
            result = patched.read_bytes()
            new_offset = struct.unpack_from('>Q', result, len(result)-44)[0]
            self.assertEqual(struct.unpack_from('>Q', result, new_offset+112)[0], 1788220800)
            self.assertEqual(result[new_offset+256+168:new_offset+256+168+prop_len], prop)
            # Partition capacity is fixed, so reject insufficient room.
            original.write_bytes(boot)
            result = subprocess.run([str(injector), str(original), str(selector), str(uefi),
                                     '--no-esp', str(patched)], capture_output=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn(b'insufficient partition space', result.stderr)


class ReclaimTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        root = pathlib.Path(cls.tmp.name)
        helper = root / 'receipt.c'
        source = (ROOT / 'tools/inject/piano-esp.c').read_text().replace('int main(int argc, char **argv)', 'int piano_esp_main(int argc, char **argv)')
        source = source.replace('#include "piano-resize-f2fs.c"', '#include "' + str(ROOT / 'tools/inject/piano-resize-f2fs.c') + '"')
        helper.write_text(source + r"""
int main(int argc,char **argv) {
    require(argc==5,"test helper args");
    int fd=open(argv[1],O_RDONLY); struct gpt b,a;
    load_gpt(fd,&b,1); close(fd);
    fd=open(argv[2],O_RDONLY); load_gpt(fd,&a,1); close(fd);
    save_receipt(argv[3],&b,&a,262128,512);
    if(!strcmp(argv[4],"write")) {
        struct receipt r; read_receipt(argv[3],&r);
        fd=open(argv[2],O_RDWR); require(write_gpt(fd,&r.before),"test write failed");
        load_gpt(fd,&a,1); close(fd);
        require(!memcmp(&a,&r.before,sizeof(a)),"test readback differs");
    }
    return 0;
}
""")
        cls.helper = root / 'receipt'
        cls.tool = root / 'esp'
        for output, source in [(cls.helper, helper), (cls.tool, ROOT / 'tools/inject/piano-esp.c')]:
            subprocess.run(['gcc', '-O2', '-Wall', '-Wextra', '-Werror', '-o', str(output), str(source)], check=True)

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.tmp.name)
        self.before, self.after, self.receipt = [self.root / name for name in ('before', 'after', 'receipt')]
        self.original = [(1, b'X'*16, 6, 262138, 'userdata')]
        self.installed = [(1, b'X'*16, 6, 130565, 'userdata'), (2, ESP, 130566, 261637, 'esp')]
        fixture(self.before, self.original, 262144)
        fixture(self.after, self.installed, 262144)

    def tearDown(self):
        self.tmp.cleanup()

    def receipt_for(self, mode='read'):
        subprocess.run([str(self.helper), str(self.before), str(self.after), str(self.receipt), mode], check=True)

    def plan(self, disk=None):
        return subprocess.run([str(self.tool), 'reclaim-plan', str(disk or self.after), str(self.receipt), '--offline'], capture_output=True, text=True)

    def test_owned_partition_plan(self):
        self.receipt_for()
        r = self.plan()
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(r.stdout, 'reclaim-needed 1 1073696768\n')

    def test_already_reclaimed_is_resumable(self):
        self.receipt_for()
        r = self.plan(self.before)
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertTrue(r.stdout.startswith('grow-pending '))

    def test_missing_receipt_refused(self):
        self.assertNotEqual(self.plan().returncode, 0)

    def test_corrupt_receipt_refused(self):
        self.receipt_for()
        data = bytearray(self.receipt.read_bytes()); data[-20] ^= 1
        self.receipt.write_bytes(data)
        self.assertNotEqual(self.plan().returncode, 0)

    def test_truncated_receipt_refused(self):
        self.receipt_for()
        self.receipt.write_bytes(self.receipt.read_bytes()[:-1])
        self.assertNotEqual(self.plan().returncode, 0)

    def test_unrelated_partition_added_refused(self):
        self.receipt_for()
        fixture(self.after, self.installed + [(3, b'Y'*16, 261638, 262138, 'other')], 262144)
        self.assertNotEqual(self.plan().returncode, 0)

    def test_esp_guid_change_refused(self):
        self.receipt_for()
        # Recompute valid GPT checksums with the ESP in a different entry.
        fixture(self.after, [self.installed[0], (3, ESP, 130566, 261637, 'esp')], 262144)
        self.assertNotEqual(self.plan().returncode, 0)

    def test_not_adjacent_refused_even_with_valid_receipt_checksum(self):
        fixture(self.after, [self.installed[0], (2, ESP, 130567, 261638, 'esp')], 262144)
        self.receipt_for()
        self.assertNotEqual(self.plan().returncode, 0)

    def test_foreign_esp_name_refused(self):
        fixture(self.after, [self.installed[0], (2, ESP, 130566, 261637, 'foreign')], 262144)
        self.receipt_for()
        self.assertNotEqual(self.plan().returncode, 0)

    def test_preexisting_esp_receipt_refused(self):
        fixture(self.before, self.installed, 262144)
        self.receipt_for()
        self.assertNotEqual(self.plan().returncode, 0)

    def test_gpt_restore_preserves_userdata_content(self):
        marker = b'ENCRYPTED-USERDATA-SENTINEL'
        with self.after.open('r+b') as f:
            f.seek(32*4096); f.write(marker)
        self.receipt_for('write')
        r = self.plan()
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertTrue(r.stdout.startswith('grow-pending '))
        with self.after.open('rb') as f:
            f.seek(32*4096); self.assertEqual(f.read(len(marker)), marker)

    def test_production_reclaim_refuses_image_file(self):
        self.receipt_for()
        r = subprocess.run([str(self.tool), 'reclaim', str(self.after), str(self.receipt), str(self.root/'backup'), '--execute'], capture_output=True)
        self.assertNotEqual(r.returncode, 0)
        self.assertFalse((self.root/'backup').exists())

    def test_plan_never_writes_gpt(self):
        self.receipt_for()
        with self.after.open('rb') as f: before=f.read(16384)
        self.assertEqual(self.plan().returncode,0)
        with self.after.open('rb') as f: self.assertEqual(f.read(16384),before)


if __name__ == '__main__':
    unittest.main()
