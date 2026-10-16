#!/usr/bin/env python3
"""Integrate pinned KernelSU Next into piano's pinned GKI without weakening KMI."""
import hashlib
from pathlib import Path
import shutil
import subprocess
import sys

GKI = "6761494007e8e8eb0e7c0a06a8afcc925203f17a"
KSU = "4c706b5938665866ebe16944a481a7b91e3396af"  # v3.4.2 / 33355
URL = "https://github.com/KernelSU-Next/KernelSU-Next.git"


def git(path, *args):
    return subprocess.check_output(["git", "-C", str(path), *args], text=True).strip()


def digest_tree(path):
    result = hashlib.sha256()
    for file in sorted(path.rglob("*")):
        if file.is_symlink():
            raise RuntimeError(f"Unexpected source symlink: {file}")
        if file.is_file():
            result.update(str(file.relative_to(path)).encode() + b"\0")
            result.update(hashlib.sha256(file.read_bytes()).digest())
    return result.hexdigest()


def main():
    root = Path(sys.argv[1]).resolve()
    common = root / "common"
    if git(common, "rev-parse", "HEAD") != GKI:
        raise RuntimeError("KernelSU integration requires the pinned piano GKI")
    edits = {
        "drivers/Makefile": '\nobj-$(CONFIG_KSU) += kernelsu/\n',
        "drivers/Kconfig": '\nsource "drivers/kernelsu/Kconfig"\n',
        "arch/arm64/configs/gki_defconfig": "\nCONFIG_KSU=y\n",
    }
    changed = set(filter(None, git(common, "diff", "HEAD", "--name-only").splitlines()))
    untracked = git(common, "ls-files", "--others", "--exclude-standard").splitlines()
    if changed - edits.keys() or any(not p.startswith("drivers/kernelsu/") for p in untracked):
        raise RuntimeError("Refusing to modify unrelated kernel source changes")
    expected = {}
    for name, addition in edits.items():
        original = git(common, "show", f"HEAD:{name}") + "\n"
        expected[name] = original + addition
        if name.endswith("gki_defconfig"):
            if original.count("CONFIG_EXT4_FS=y\n") != 1:
                raise RuntimeError("Pinned GKI config layout changed")
            expected[name] = original.replace("CONFIG_EXT4_FS=y\n",
                "CONFIG_KSU=y\nCONFIG_EXT4_FS=y\n")
        if (common / name).read_text() not in (original, original + addition, expected[name]):
            raise RuntimeError(f"Unexpected kernel modification: {name}")

    source = root / "KernelSU-Next"
    if not source.exists():
        subprocess.run(["git", "clone", "--filter=blob:none", "--branch", "v3.4.2", URL, str(source)], check=True)
    if git(source, "remote", "get-url", "origin") != URL or git(source, "status", "--porcelain"):
        raise RuntimeError("Unexpected KernelSU source remote or local changes")
    subprocess.run(["git", "-C", str(source), "fetch", "origin", KSU], check=True)
    subprocess.run(["git", "-C", str(source), "checkout", "--detach", KSU], check=True)
    if git(source, "rev-parse", "HEAD") != KSU:
        raise RuntimeError("KernelSU source identity mismatch")

    # Kleaf's sandbox omits Git metadata. Pin the upstream fallback version too.
    staging = root / ".piano-ksunext-staging"
    if staging.exists():
        raise RuntimeError(f"Existing staging directory: {staging}")
    try:
        shutil.copytree(source / "kernel", staging)
        kbuild = staging / "Kbuild"
        text = kbuild.read_text()
        for before, after in (("KSU_VERSION_FALLBACK := 1", "KSU_VERSION_FALLBACK := 33355"),
                              ("KSU_VERSION_TAG_FALLBACK := v0.0.1", "KSU_VERSION_TAG_FALLBACK := v3.4.2")):
            if text.count(before) != 1:
                raise RuntimeError("Pinned KernelSU Kbuild layout changed")
            text = text.replace(before, after)
        kbuild.write_text(text)
        # Enable explicitly in gki_defconfig, not by a global Kconfig default.
        # This also keeps Kleaf's savedefconfig comparison meaningful.
        kconfig = staging / "Kconfig"
        text = kconfig.read_text()
        if text.count("\tdefault y\n") != 1:
            raise RuntimeError("Pinned KernelSU Kconfig layout changed")
        kconfig.write_text(text.replace("\tdefault y\n", "\tdefault n\n"))
        destination = common / "drivers/kernelsu"
        if destination.exists():
            if destination.is_symlink() or digest_tree(destination) != digest_tree(staging):
                raise RuntimeError("Existing KernelSU directory differs; preserving it")
        else:
            shutil.copytree(staging, destination)
        for name, content in expected.items():
            (common / name).write_text(content)
        print(f"KernelSU Next v3.4.2 built-in: {KSU}; source SHA256 {digest_tree(staging)}")
        print("KMI symbol lists, module signatures, manager signature and SELinux retained")
    finally:
        if staging.exists():
            shutil.rmtree(staging)


if __name__ == "__main__":
    main()
