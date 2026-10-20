#!/usr/bin/env python3
"""Validate the three recovery packages without accessing a tablet."""
import pathlib
import struct
import sys
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[2]


def verify(base):
    for mode in ("installer", "uninstall", "uninstall-clean"):
        path = pathlib.Path(str(base) + "-" + mode + ".zip")
        with zipfile.ZipFile(path) as archive:
            names = archive.namelist()
            assert len(names) == len(set(names)), "duplicate ZIP entry"
            assert archive.testzip() is None, "ZIP CRC failure"
            for name in names:
                assert not name.startswith("/") and ".." not in pathlib.PurePosixPath(name).parts
            update = "META-INF/com/google/android/update-binary"
            assert archive.read(update) == (ROOT / "tools/inject/update-binary").read_bytes()
            required = {update, "piano-inject"}
            if mode == "installer":
                required |= {"piano-esp", "selector.bin", "uefi.bin", "esp.img", "esp/EFI/BOOT/BOOTAA64.EFI"}
                assert "uninstall" not in names and "reclaim" not in names
                assert len(archive.read("selector.bin")) >= 80
            else:
                required.add("uninstall")
                assert "esp.img" not in names and "uefi.bin" not in names
                if mode == "uninstall-clean":
                    required |= {"piano-esp", "reclaim", "reclaim.sh"}
                    assert archive.read("reclaim.sh") == (ROOT / "tools/inject/reclaim.sh").read_bytes()
                else:
                    assert "reclaim" not in names and "reclaim.sh" not in names
            assert required.issubset(names), str(required - set(names))
            assert (archive.getinfo(update).external_attr >> 16) & 0o111, "installer is not executable"
            for name in ("piano-inject", "piano-esp"):
                if name not in required:
                    continue
                data = archive.read(name)
                assert data == (ROOT / "out" / name).read_bytes(), "stale native helper"
                assert data[:6] == b"\x7fELF\x02\x01" and struct.unpack_from("<H", data, 18)[0] == 183, "not ARM64 ELF"
                phoff = struct.unpack_from("<Q", data, 32)[0]
                entry, count = struct.unpack_from("<HH", data, 54)
                assert all(struct.unpack_from("<I", data, phoff + i * entry)[0] != 3 for i in range(count)), "dynamic interpreter required"
        print(path.name + ": verified")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("usage: verify-zips.py OUTPUT_BASE_WITHOUT_ZIP")
    verify(sys.argv[1])
