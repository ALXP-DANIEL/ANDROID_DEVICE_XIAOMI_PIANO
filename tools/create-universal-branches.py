#!/usr/bin/env python3
"""One-time, local-only creation of version-aware piano hardware branches.

No source branch is modified or deleted. Existing Git objects are reused
directly rather than copying multiple gigabytes of blobs into a worktree.
Publishing and testing remain separate explicit operations.
"""

import os
from pathlib import Path
import subprocess
import tempfile

HERE = Path(__file__).resolve().parent
GIT_ROOT = HERE.parent
PAIRS = ("device-common", "vendor", "vendor-common", "kernel")


def git(*args, env=None, input=None):
    result = subprocess.run(["git", "-C", str(GIT_ROOT), *args],
                            env=env, text=True, input=input, capture_output=True)
    if result.returncode:
        raise RuntimeError(f"git {' '.join(args)}: {result.stderr}")
    return result.stdout.strip()


def build(prefix):
    branch = f"refs/heads/{prefix}"
    if subprocess.run(["git", "-C", str(GIT_ROOT), "show-ref", "--verify", "--quiet", branch]).returncode == 0:
        raise RuntimeError(f"local branch already exists: {prefix}")
    sha = {version: git("rev-parse", f"origin/{prefix}-{version}") for version in (16, 17)}
    with tempfile.TemporaryDirectory(prefix="piano-branch-index-") as temp:
        index = str(Path(temp) / "index")
        env = {**os.environ, "GIT_INDEX_FILE": index}
        git("read-tree", "--empty", env=env)
        for version, commit in sha.items():
            git("read-tree", f"--prefix=versions/android{version}/", commit, env=env)

        # Inactive variant Android.bp files must not be discovered by Soong,
        # or both variants define the same module names. Rename them in Git,
        # then materialize only the selected ones back to their normal names.
        original = git("ls-files", "-s", env=env).splitlines()
        moved = 0
        for line in original:
            metadata, path = line.split("\t", 1)
            if Path(path).name not in ("Android.bp", "Android.mk"):
                continue
            mode, blob, _stage = metadata.split()
            git("update-index", "--force-remove", "--", path, env=env)
            git("update-index", "--add", "--cacheinfo", f"{mode},{blob},{path}.in", env=env)
            moved += 1

        files = {
            ".gitignore": "/*\n!/versions/\n!/README.md\n!/select-version.py\n",
            "README.md": (
                f"# Xiaomi Pad 8 Pro unified {prefix} tree\n\n"
                "One branch supports both Android 16 and Android 17. **Do not mix**\n"
                "firmware, kernel modules, library imports or vendor binaries.\n\n"
                "The versioned directories preserve the original files from:\n\n"
                f"- Android 16: `{prefix}-16` at `{sha[16][:12]}`\n"
                f"- Android 17: `{prefix}-17` at `{sha[17][:12]}`\n\n"
                "`sync-device.sh` runs `python3 select-version.py 16` or `17`\n"
                "after syncing. The selector stages the matching files at the\n"
                "original build paths and checks for collisions. The generated\n"
                "root files are ignored by Git; sources under `versions/` are\n"
                "immutable. Android.bp/Android.mk are stored as `.in` to avoid\n"
                "duplicate Soong/Make modules in the inactive version.\n\n"
                "A complete ROM build and device smoke tests remain necessary.\n"
            ),
        }
        selector = (HERE / "select-android-tree.py").read_bytes()
        for name, data in {**files, "select-version.py": selector}.items():
            with tempfile.NamedTemporaryFile(dir=temp, delete=False) as file:
                file.write(data.encode() if isinstance(data, str) else data)
            blob = git("hash-object", "-w", file.name)
            git("update-index", "--add", "--cacheinfo", f"100644,{blob},{name}", env=env)
        tree = git("write-tree", env=env)
        parent = git("rev-parse", "origin/main")
        commit = git("-c", "user.name=MUHAMMAD ALIF DANIEL BIN MOHD HAIRUL HEZZELIN",
                     "-c", "user.email=alifdaniel.personalspace@gmail.com",
                     "commit-tree", tree, "-p", parent, "-m",
                     f"piano: Combine Android 16 and 17 {prefix} trees")
        git("update-ref", branch, commit)
        print(f"{prefix}: created {commit[:10]} with {moved} renamed build declarations, sources preserved")


if __name__ == "__main__":
    for name in PAIRS:
        build(name)
