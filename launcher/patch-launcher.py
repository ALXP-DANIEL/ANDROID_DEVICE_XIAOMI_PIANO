#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Build a Pixel Launcher with a translucent, auto-hiding gesture handle.

Runs from the top of the source tree (vendorsetup.sh calls it). Takes the
NexusLauncherRelease.apk that vendor/gms ships right now, injects
HandleFade into StashedHandleView and writes launcher/prebuilt/. The build
signs the result. If the APK layout changed and a hook point is missing,
the stock APK is copied unchanged so the build still works.
"""
import hashlib, os, re, shutil, subprocess, sys, tempfile, zipfile

TOP = os.getcwd()
HERE = os.path.dirname(os.path.abspath(__file__))
STOCK = "vendor/gms/system_ext/packages/privileged_apps/NexusLauncherRelease/NexusLauncherRelease.apk"
OUT_DIR = os.path.join(HERE, "prebuilt")
OUT_APK = os.path.join(OUT_DIR, "NexusLauncherPiano.apk")
STAMP = os.path.join(OUT_DIR, ".stamp")
HANDLE = "com/android/launcher3/taskbar/StashedHandleView"
HELPER = "Lorg/lineageos/piano/launcher/HandleFade;"
JAVA = os.path.join(HERE, "src/org/lineageos/piano/launcher/HandleFade.java")
SMALI = "prebuilts/extract-tools/common/smali/smali.jar"
BAKSMALI = "prebuilts/extract-tools/common/smali/baksmali.jar"
JDK = "prebuilts/jdk/jdk21/linux-x86/bin"
ANDROID_JAR = "prebuilts/sdk/current/public/android.jar"
D8 = "out/host/linux-x86/bin/d8"
HOOKED = ["setAlpha", "setTranslationY", "setScaleX", "setScaleY"]


def log(msg):
    print("piano launcher: " + msg)


def run(*cmd):
    # d8 needs the tree's JDK 21, not the host java.
    env = dict(os.environ, JAVA_HOME=os.path.join(TOP, os.path.dirname(JDK)),
               PATH=os.path.join(TOP, JDK) + os.pathsep + os.environ["PATH"])
    subprocess.run(cmd, check=True, stdout=subprocess.DEVNULL, env=env)


def digest(*paths):
    h = hashlib.sha256()
    for p in paths:
        with open(p, "rb") as f:
            h.update(f.read())
    return h.hexdigest()


def patch_smali(text):
    """Return patched smali, or raise if a hook point is missing."""
    ctor = re.search(
        r"(\.method public constructor <init>\(Landroid/content/Context;"
        r"Landroid/util/AttributeSet;II\)V.*?)(\n    return-void\n\.end method)",
        text, re.S)
    if not ctor:
        raise ValueError("4-argument constructor not found")
    text = (text[:ctor.end(1)]
            + "\n\n    invoke-static {p0}, " + HELPER + "->attach(Landroid/view/View;)V"
            + text[ctor.end(1):])
    for name in HOOKED:
        if re.search(r"\.method [^\n]*\b%s\(F\)V" % name, text):
            raise ValueError(name + " is already overridden")
        text += (
            "\n.method public %s(F)V\n"
            "    .registers 2\n\n"
            "    invoke-super {p0, p1}, Landroid/view/View;->%s(F)V\n\n"
            "    invoke-static {p0}, %s->poke(Landroid/view/View;)V\n\n"
            "    return-void\n"
            ".end method\n") % (name, name, HELPER)
    return text


def build(work):
    with zipfile.ZipFile(STOCK) as z:
        dexes = sorted(n for n in z.namelist() if re.fullmatch(r"classes\d*\.dex", n))
        owner = None
        for n in dexes:
            z.extract(n, work)
        for n in dexes:
            out = os.path.join(work, "smali-" + n)
            run(os.path.join(JDK, "java"), "-jar", BAKSMALI, "d",
                os.path.join(work, n), "-o", out)
            if os.path.exists(os.path.join(out, HANDLE + ".smali")):
                owner = n
                break
        if owner is None:
            raise ValueError("StashedHandleView not found")

    src = os.path.join(work, "smali-" + owner, HANDLE + ".smali")
    with open(src) as f:
        patched = patch_smali(f.read())
    with open(src, "w") as f:
        f.write(patched)
    run(os.path.join(JDK, "java"), "-jar", SMALI, "a",
        os.path.join(work, "smali-" + owner), "-o", os.path.join(work, owner))

    classes = os.path.join(work, "classes")
    run(os.path.join(JDK, "javac"), "--release", "17", "-classpath", ANDROID_JAR,
        "-d", classes, JAVA)
    helper_out = os.path.join(work, "helper")
    os.makedirs(helper_out)
    class_files = [os.path.join(dp, f) for dp, _, fs in os.walk(classes) for f in fs]
    run(D8, "--release", "--min-api", "34", "--lib", ANDROID_JAR,
        "--output", helper_out, *class_files)
    extra = "classes%d.dex" % (len(dexes) + 1)

    tmp = OUT_APK + ".tmp"
    with zipfile.ZipFile(STOCK) as zin, zipfile.ZipFile(tmp, "w") as zout:
        for info in zin.infolist():
            if info.filename.startswith("META-INF/") and re.search(
                    r"\.(SF|RSA|EC|DSA|MF)$", info.filename):
                continue
            if info.filename == owner:
                with open(os.path.join(work, owner), "rb") as f:
                    zout.writestr(info, f.read())
            else:
                zout.writestr(info, zin.read(info.filename))
        info = zipfile.ZipInfo(extra, date_time=(2008, 1, 1, 0, 0, 0))
        with open(os.path.join(helper_out, "classes.dex"), "rb") as f:
            zout.writestr(info, f.read())
    os.replace(tmp, OUT_APK)


def main():
    if not os.path.exists(STOCK):
        return 0
    if not os.path.exists(D8):
        # First build of a clean tree: d8 is built with the host tools.
        log("d8 not built yet, run lunch again after the first build")
    os.makedirs(OUT_DIR, exist_ok=True)
    key = digest(STOCK, JAVA, os.path.abspath(__file__))
    if os.path.exists(OUT_APK) and os.path.exists(STAMP):
        with open(STAMP) as f:
            if f.read() == key:
                return 0
    work = tempfile.mkdtemp(prefix="piano-launcher-")
    try:
        build(work)
        log("patched " + os.path.basename(STOCK))
    except Exception as e:  # keep the build working with the stock launcher
        log("patch failed (%s), shipping the stock launcher" % e)
        shutil.copyfile(STOCK, OUT_APK)
    finally:
        shutil.rmtree(work, ignore_errors=True)
    with open(STAMP, "w") as f:
        f.write(key)
    return 0


if __name__ == "__main__":
    sys.exit(main())
