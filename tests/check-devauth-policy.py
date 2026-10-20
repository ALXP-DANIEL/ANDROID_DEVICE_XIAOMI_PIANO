#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Static trust/property wiring checks only. A real Android policy/neverallow build is still required."""
import hashlib
import os
from pathlib import Path
import xml.etree.ElementTree as ET

tree = Path(__file__).resolve()
candidate = tree.parents[2] / "piano"
workspace = tree.parents[4] / "device/xiaomi/piano"
root = Path(os.environ.get("PIANO_DEVICE_TREE", candidate if candidate.is_dir() else workspace))
private = root / "sepolicy/system_ext/private"
public = root / "sepolicy/system_ext/public"
policy = ET.parse(private / "mac_permissions.xml").getroot()
signers = policy.findall("signer")
assert len(signers) == 1
signer = signers[0]
assert hashlib.sha256(bytes.fromhex(signer.attrib["signature"])).hexdigest() == \
    "c9009d01ebf9f5d0302bc71b2fe9aa9a47a432bba17308a3111b75d7b2149025"
assert signer.find("seinfo") is None, "signer-wide trust would include unrelated Xiaomi packages"
packages = signer.findall("package")
assert len(packages) == 1 and packages[0].attrib["name"] == "com.xiaomi.devauth"
assert packages[0].find("seinfo").attrib["value"] == "piano_midevauth"
seapp = [line.split() for line in (private / "seapp_contexts").read_text().splitlines()
         if line and not line.startswith("#")]
assert len(seapp) == 1
selectors = dict(item.split("=", 1) for item in seapp[0])
assert selectors == {"user": "_app", "seinfo": "piano_midevauth", "name": "com.xiaomi.devauth",
                     "domain": "midevauth_app", "type": "app_data_file", "levelFrom": "all"}
assert "type midevauth_app, domain;" in (public / "midevauth_app.te").read_text()
board = (root / "BoardConfig.mk").read_text()
for name in ["PUBLIC", "PRIVATE"]:
    assert "SYSTEM_EXT_" + name + "_SEPOLICY_DIRS += $(DEVICE_PATH)/sepolicy/system_ext/" + name.lower() in board
context = (root / "sepolicy/vendor/property_contexts").read_text()
assert context.count("vendor.devauth.keyboard_connected ") == 1
assert "vendor.devauth.keyboard_connected u:object_r:vendor_security_devauth_keyboard_connected_prop:s0 exact bool" in context
assert "vendor_public_prop(vendor_security_devauth_keyboard_connected_prop)" in (root / "sepolicy/vendor/property.te").read_text()
rules = (root / "sepolicy/vendor/midevauth_app.te").read_text()
assert "set_prop(midevauth_app, vendor_security_devauth_keyboard_connected_prop)" in rules
assert "hal_client_domain(midevauth_app, hal_mtdservice)" in rules
for path in [private / "midevauth_app.te", public / "midevauth_app.te", root / "sepolicy/vendor/midevauth_app.te"]:
    source = "\n".join(line for line in path.read_text().splitlines() if not line.startswith("#"))
    assert "untrusted_app" not in source and "permissive" not in source
print("PASS static DevAuth signer/package/domain/property wiring; full Android policy build still required")
