#!/usr/bin/env python3
"""Exercise distributed original reconstruction and corruption rejection in isolation."""
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

root = Path(__file__).resolve().parent
with tempfile.TemporaryDirectory(prefix="piano-notes-selfcheck-") as directory:
    target = Path(directory)
    for name in ("stage-originals.py", "manifest.py", "strip_micloud.py", "Android.bp.in", "parts.json"):
        shutil.copyfile(root / name, target / name)
    (target / "parts").mkdir()
    for part in (root / "parts").iterdir():
        (target / "parts" / part.name).symlink_to(part)
    def run(*arguments):
        return subprocess.run([sys.executable, str(target / "stage-originals.py"), *arguments],
                              capture_output=True, text=True)
    result = run("--from-parts")
    if result.returncode:
        raise SystemExit(result.stderr)
    result = run("--check")
    if result.returncode:
        raise SystemExit(result.stderr)
    before = (target / "Android.bp").read_bytes()
    inventory = json.loads((target / "parts.json").read_text())
    inventory["MIUINotes.apk"][0]["sha256"] = "0" * 64
    (target / "parts.json").write_text(json.dumps(inventory))
    result = run("--from-parts")
    if result.returncode == 0 or "part size/SHA-256 mismatch" not in result.stderr:
        raise SystemExit("corrupted part checksum was not rejected")
    if (target / "Android.bp").read_bytes() != before:
        raise SystemExit("failed reconstruction changed build modules")
print("PASS: reconstruction, whole-APK identities/manifests, generated modules, corrupted-part rejection")
