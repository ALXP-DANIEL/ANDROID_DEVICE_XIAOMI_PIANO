#
# SPDX-FileCopyrightText: The LineageOS Project
# SPDX-License-Identifier: Apache-2.0
#

# Apply piano's source patches whenever build/envsetup.sh is sourced, so a
# plain breakfast and m build gets them. Applied patches are skipped.
(cd "$(gettop)" && bash device/xiaomi/piano/patches/apply-patches.sh) ||
    echo "piano: applying device/xiaomi/piano/patches failed, see the errors above" >&2

# Patch the Pixel Launcher gesture handle. Rebuilt only when vendor/gms
# ships a new launcher or the patch changes.
(cd "$(gettop)" && python3 device/xiaomi/piano/launcher/patch-launcher.py) ||
    echo "piano: patching Pixel Launcher failed, see the errors above" >&2
