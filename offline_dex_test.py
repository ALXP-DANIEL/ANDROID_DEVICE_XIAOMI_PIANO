#!/usr/bin/env python3
"""Boundary tests for the Notes offline DEX policy.

Set NOTES_STOCK_APK plus DEXLIB2_CLASSPATH for full artifact tests. The stock
fixture stays outside Git; each negative case must fail before producing output.
"""
import importlib.util
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import zipfile

spec = importlib.util.spec_from_file_location("offline_dex", Path(__file__).with_name("offline_dex.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class PolicyBoundaryTest(unittest.TestCase):
    def test_exact_inventory(self):
        module.validate_targets(dict.fromkeys(module.GATES, 1))

    def test_missing_definition(self):
        with self.assertRaises(ValueError):
            module.validate_targets({})

    def test_duplicate_definition(self):
        with self.assertRaises(ValueError):
            module.validate_targets(dict.fromkeys(module.GATES, 2))

    def test_wrong_prototype(self):
        found = dict.fromkeys(module.GATES, 1)
        found["Lv6/v5;->j(Landroid/content/Context;)I"] = found.pop("Lv6/v5;->j(Landroid/content/Context;)Z")
        with self.assertRaises(ValueError):
            module.validate_targets(found)

    @unittest.skipUnless(os.environ.get("NOTES_STOCK_APK") and os.environ.get("DEXLIB2_CLASSPATH"), "external pinned APK and dexlib2 required")
    def test_real_artifact_guards_and_preservation(self):
        source = os.environ["NOTES_STOCK_APK"]
        with tempfile.TemporaryDirectory(prefix="notes-policy-test-") as tmp:
            root = Path(tmp)
            output = root / "offline.apk"
            module.transform(source, output)
            with zipfile.ZipFile(source) as before, zipfile.ZipFile(output) as after:
                for name in before.namelist():
                    if name.startswith("META-INF/") or (name.startswith("classes") and name.endswith(".dex")):
                        continue
                    self.assertEqual(before.read(name), after.read(name), name)
                # The Java rewriter rereads all methods and checks every
                # non-target instruction, exception handler and debug item.
                self.assertEqual(len([n for n in before.namelist() if n.endswith(".dex")]),
                                 len([n for n in after.namelist() if n.endswith(".dex")]))
            bad = root / "bad.apk"
            with patch.dict(module.GATES, {"Lv6/v5;->missing()Z": False}):
                with self.assertRaises(subprocess.CalledProcessError):
                    module.transform(source, bad)
            self.assertFalse(bad.exists())
            original = module.GATES.copy()
            changed = {k.replace("j(Landroid/content/Context;)Z", "j(Landroid/content/Context;)I"): v for k, v in original.items()}
            with patch.dict(module.GATES, changed, clear=True):
                with self.assertRaises(subprocess.CalledProcessError):
                    module.transform(source, bad)
            self.assertFalse(bad.exists())
            duplicated = root / "duplicate.apk"
            with zipfile.ZipFile(source) as before, zipfile.ZipFile(duplicated, "w") as after:
                for info in before.infolist():
                    after.writestr(info, before.read(info.filename))
                after.writestr("classes999.dex", before.read("classes.dex"))
            with self.assertRaises(subprocess.CalledProcessError):
                module.transform(duplicated, bad)
            self.assertFalse(bad.exists())


if __name__ == "__main__":
    unittest.main()
