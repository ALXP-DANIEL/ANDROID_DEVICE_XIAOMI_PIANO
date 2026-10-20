#!/system/bin/sh
# Root the slot an A/B ROM or OTA was just installed to, from the recovery
# still running in the old slot (like the Magisk app's "Install to inactive
# slot (after OTA)"). Patches the target slot's init_boot (or boot) with
# Magisk's own boot_patch.sh; the new slot's system does not need mounting.
# Usage: fox_magisk_other_slot.sh <Magisk.zip>
ZIP=$1
W=/tmp/fox_magisk_os
cur=$(getprop ro.boot.slot_suffix)
case "$cur" in _a) tgt=_b ;; _b) tgt=_a ;; *) echo "not an A/B device"; exit 1 ;; esac

part=init_boot$tgt
[ -e /dev/block/by-name/$part ] || part=boot$tgt
dev=/dev/block/by-name/$part
[ -e "$dev" ] || { echo "no $part"; exit 1; }

rm -rf $W && mkdir -p $W && cd $W || exit 1
unzip -oq "$ZIP" 'assets/*' 'lib/arm64-v8a/*' || { echo "cannot unzip $ZIP"; exit 1; }
for f in magiskboot magiskinit magisk init-ld; do
  [ -f lib/arm64-v8a/lib$f.so ] && cp lib/arm64-v8a/lib$f.so $f
done
cp assets/boot_patch.sh assets/util_functions.sh assets/stub.apk . 2>/dev/null
chmod 755 magiskboot magiskinit magisk init-ld boot_patch.sh 2>/dev/null

dd if=$dev of=boot.img bs=1M 2>/dev/null || { echo "cannot read $part"; exit 1; }
export KEEPVERITY=true KEEPFORCEENCRYPT=true RECOVERYMODE=false
sh ./boot_patch.sh boot.img || { echo "boot_patch.sh failed"; exit 1; }
[ -s new-boot.img ] || { echo "no patched image"; exit 1; }
dd if=new-boot.img of=$dev bs=1M 2>/dev/null && sync || { echo "cannot write $part"; exit 1; }
echo "Magisk installed to $part (the new slot)"
cd /; rm -rf $W
