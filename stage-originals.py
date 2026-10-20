#!/usr/bin/env python3
"""Reconstruct pinned original Notes and stage a MiCloud-independent APK."""
import argparse
import hashlib
import json
from pathlib import Path
import tempfile
import zipfile
from contextlib import ExitStack

from manifest import decode_manifest
from strip_micloud import remove_micloud_uses_library

ROOT = Path(__file__).resolve().parent
ANDROID = "{http://schemas.android.com/apk/res/android}"
STOCK_HASH = "81510beeda9b87c3120c8835dbaa3faf9fed780aec90b9317904ba897f114e8f"
APK = "MIUINotes.apk"
OLD_SIGNATURE = {"META-INF/CERT.SF", "META-INF/CERT.RSA", "META-INF/CERT.DSA",
                 "META-INF/CERT.EC", "META-INF/MANIFEST.MF"}


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def manifest(apk):
    with zipfile.ZipFile(apk) as archive:
        if archive.testzip() is not None or "classes.dex" not in archive.namelist():
            raise ValueError("invalid Notes APK contents")
        return decode_manifest(archive.read("AndroidManifest.xml"))


def libraries(root):
    return [e.get(ANDROID + "name") for e in root.find("application").findall("uses-library")]


def verify_source(path):
    if sha256(path) != STOCK_HASH:
        raise ValueError("stock Notes full-file SHA-256 mismatch")
    root = manifest(path)
    if root.get("package") != "com.miui.notes" or int(root.find("uses-sdk").get(ANDROID + "minSdkVersion")) != 29:
        raise ValueError("unexpected original Notes package or minSdkVersion")
    if libraries(root).count("micloud-sdk") != 1:
        raise ValueError("unexpected original Notes uses-library manifest")


def verify_built(path):
    root = manifest(path)
    if root.get("package") != "com.miui.notes" or "micloud-sdk" in libraries(root):
        raise ValueError("repackaged Notes still requires MiCloud SDK")
    if int(root.find("uses-sdk").get(ANDROID + "minSdkVersion")) != 29:
        raise ValueError("repackaged Notes changed minSdkVersion")
    with zipfile.ZipFile(path) as archive:
        if any(name.upper() in OLD_SIGNATURE for name in archive.namelist()):
            raise ValueError("repackaged Notes retains an invalid stock v1 signature")


def create_apk(source, destination):
    verify_source(source)
    with zipfile.ZipFile(source) as src, zipfile.ZipFile(destination, "w", allowZip64=True) as target:
        for entry in src.infolist():
            if entry.filename.upper() in OLD_SIGNATURE:
                continue
            data = src.read(entry)
            if entry.filename == "AndroidManifest.xml":
                data = remove_micloud_uses_library(data)
            target.writestr(entry, data)
    verify_built(destination)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("stock_dump", nargs="?", type=Path)
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--from-parts", action="store_true")
    args = parser.parse_args()
    if sum((args.stock_dump is not None, args.check, args.from_parts)) != 1:
        parser.error("choose exactly one source: stock_dump, --from-parts, or --check")
    if args.check:
        if (ROOT / "Android.bp").read_bytes() != (ROOT / "Android.bp.in").read_bytes():
            raise ValueError("staged Notes build module differs from pinned template")
        target = ROOT / "prebuilt" / APK
        verify_built(target)
        record = json.loads((ROOT / "staged-inputs.json").read_text())
        if record.get("repackaged_sha256") != sha256(target) or record.get("original_sha256") != STOCK_HASH:
            raise ValueError("staged Notes differs from pinned rebuild record")
        print(json.dumps(record, indent=2))
        return
    with ExitStack() as stack:
        if args.from_parts:
            tempdir = Path(stack.enter_context(tempfile.TemporaryDirectory(prefix="piano-notes-parts-")))
            original = tempdir / APK
            parts = json.loads((ROOT / "parts.json").read_text())
            if set(parts) != {APK}:
                raise ValueError("unexpected Notes parts inventory")
            with original.open("wb") as writer:
                for index, part in enumerate(parts[APK]):
                    expected = f"{APK}.part{index:02d}"
                    if part["file"] != expected or not 0 < part["size"] < 90 * 1024 * 1024:
                        raise ValueError("unexpected Notes segment")
                    payload = (ROOT / "parts" / expected).read_bytes()
                    if len(payload) != part["size"] or hashlib.sha256(payload).hexdigest() != part["sha256"]:
                        raise ValueError("part size/SHA-256 mismatch: " + expected)
                    writer.write(payload)
        else:
            original = args.stock_dump / "product/data-app/MIUINotes/MIUINotes.apk"
        targetdir = ROOT / "prebuilt"
        targetdir.mkdir(exist_ok=True)
        with tempfile.NamedTemporaryFile(dir=targetdir, delete=False) as temporary:
            tmp = Path(temporary.name)
        try:
            create_apk(original, tmp)
            tmp.replace(targetdir / APK)
        finally:
            tmp.unlink(missing_ok=True)
        record = {"original_sha256": STOCK_HASH,
                  "repackaged_sha256": sha256(targetdir / APK),
                  "package": "com.miui.notes", "min_sdk": 29,
                  "cloud_library_removed": True, "signing": "ROM platform key at build time"}
        (ROOT / "staged-inputs.json").write_text(json.dumps(record, indent=2) + "\n")
        (ROOT / "Android.bp").write_bytes((ROOT / "Android.bp.in").read_bytes())
        print(json.dumps(record, indent=2))


if __name__ == "__main__":
    main()
