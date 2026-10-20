#!/vendor/bin/sh
# SPDX-License-Identifier: Apache-2.0
# Publish the body colour code (BU, DG, TA, ...) from persist, which stock
# uses to pick the matching default wallpaper (see init.piano.wallpaper.rc).
color=$(cat /mnt/vendor/persist/Wallpaper/color.bin 2>/dev/null)
case "$color" in
    [A-Z][A-Z])
        setprop ro.vendor.piano.body_color "$color"
        ;;
esac
