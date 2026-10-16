#!/usr/bin/env python3
"""Apply reviewed offline Notes capability gates using dexlib2, never raw DEX edits.

DEXLIB2_CLASSPATH must contain dexlib2 and its runtime dependencies. Upstream
staging validates the pinned stock APK before calling this transformation.
"""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile

# Exact .44 ABI, not names inferred from translated UI labels.
GATES = {
    "Lv6/v5;->j(Landroid/content/Context;)Z": False,
    "Lv6/v5;->k(Landroid/content/Context;)Z": False,
    "Lv6/v5;->l(Landroid/content/ContextWrapper;)Z": False,
    "Ll9/c;->h()Z": False,
    "Ll9/a;->a(Lg9/d;)Z": False,
}


def validate_targets(found, expected=GATES):
    if set(found) != set(expected):
        raise ValueError("missing or unexpected offline gate ABI")
    if any(count != 1 for count in found.values()):
        raise ValueError("duplicate offline gate definition")


def transform(source, destination, classpath=None, tool=None):
    classpath = classpath or os.environ.get("DEXLIB2_CLASSPATH")
    if not classpath and not tool:
        raise ValueError("DEXLIB2_CLASSPATH is required; raw DEX rewriting is forbidden")
    with tempfile.TemporaryDirectory(prefix="piano-offline-dex-") as tmp:
        root = Path(tmp)
        incoming, outgoing = root / "input", root / "output"
        incoming.mkdir()
        with zipfile.ZipFile(source) as archive:
            names = [n for n in archive.namelist() if n.startswith("classes") and n.endswith(".dex") and "/" not in n]
            if not names or len(names) != len(set(names)):
                raise ValueError("missing or duplicate DEX entries")
            for name in names:
                (incoming / name).write_bytes(archive.read(name))
            if tool:
                subprocess.run([tool, str(incoming), str(outgoing)] +
                               [key + "=" + str(value).lower() for key, value in GATES.items()], check=True)
            else:
                sources = []
                for name in ("OfflineDex.java", "ReplayableDexPool.java"):
                    helper = Path(__file__).with_name(name).read_text()
                    if os.environ.get("DEXLIB2_LEGACY_NAMESPACE") == "1":
                        helper = helper.replace("com.android.tools.smali.dexlib2", "org.jf.dexlib2")
                    source_file = root / name
                    source_file.write_text(helper)
                    sources.append(str(source_file))
                subprocess.run(["javac", "-d", str(root), "-cp", classpath] + sources, check=True)

                subprocess.run(["java", "-Xmx2g", "-cp", str(root) + os.pathsep + classpath,
                                "OfflineDex", str(incoming), str(outgoing)] +
                               [key + "=" + str(value).lower() for key, value in GATES.items()], check=True)
            with zipfile.ZipFile(destination, "w") as output:
                for info in archive.infolist():
                    upper = info.filename.upper()
                    if upper.startswith("META-INF/") and (upper.endswith((".SF", ".RSA", ".DSA", ".EC")) or upper == "META-INF/MANIFEST.MF"):
                        continue
                    payload = (outgoing / info.filename).read_bytes() if info.filename in names else archive.read(info.filename)
                    output.writestr(info, payload)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input")
    parser.add_argument("output")
    parser.add_argument("--classpath")
    args = parser.parse_args()
    transform(args.input, args.output, args.classpath)
