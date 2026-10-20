#!/sbin/sh
# SPDX-License-Identifier: BSD-2-Clause-Patent
# Sourced by update-binary; two explicit recovery invocations, no automatic reboot.
RECEIPT=/data/adb/piano-uefi/esp-reclaim.receipt
RECLAIM_MAP=
RECLAIM_OPTIONS=
RECLAIM_UNMOUNTED=0

reclaim_restore() {
    if [ "$RECLAIM_UNMOUNTED" = 1 ]; then
        if awk '$2 == "/data" {found=1} END {exit !found}' /proc/mounts; then
            RECLAIM_UNMOUNTED=0
            return 0
        fi
        mount -t f2fs -o "$RECLAIM_OPTIONS" "$RECLAIM_MAP" /data || {
            ui_print "Data remount failed; stay in recovery. Do not format data."
            return 1
        }
        RECLAIM_UNMOUNTED=0
    fi
}

reclaim_prepare() {
    [ -f "$RECEIPT" ] || abort "No fresh-install ownership receipt. Existing/legacy ESPs cannot be reclaimed automatically."
    cp "$RECEIPT" "$TMP/reclaim.receipt" || abort "Cannot stage receipt in recovery RAM"
    "$TMP/piano-esp" reclaim-plan /dev/block/sda "$TMP/reclaim.receipt" > "$TMP/reclaim-plan" 2> "$TMP/log" || abort "$(cat "$TMP/log")"
    read reclaim_state reclaim_index reclaim_bytes < "$TMP/reclaim-plan"
    FSCK=$(command -v fsck.f2fs) || abort "Recovery lacks fsck.f2fs"
    RESIZE=$(command -v resize.f2fs) || abort "Recovery lacks resize.f2fs"
    case "$FSCK:$RESIZE" in *'/data/'*|*'/sdcard/'*) abort "F2FS tools must remain available after unmounting data" ;; esac
    if [ "$reclaim_state" = grow-pending ]; then
        RECLAIM_MAP=$(awk '$2 == "/data" && $3 == "f2fs" {print $1; exit}' /proc/mounts)
        RECLAIM_OPTIONS=$(awk '$2 == "/data" && $3 == "f2fs" {print $4; exit}' /proc/mounts)
        [ -n "$RECLAIM_MAP" ] || abort "Decrypt and mount data in recovery, then run this ZIP again"
        # No receipt, ZIP, log or executable is read from data while unmounted.
        sync
        trap reclaim_restore 0
        RECLAIM_UNMOUNTED=1
        awk '$2 ~ /^\/data(\/|$)/ || $2 ~ /^\/sdcard(\/|$)/ {print $2}' /proc/mounts | sort -r > "$TMP/data-mounts"
        while IFS= read -r point; do
            umount "$point" || abort "Cannot unmount $point. Disable MTP and retry in recovery; no lazy unmount is permitted."
        done < "$TMP/data-mounts"
        "$TMP/piano-esp" grow-check /dev/block/sda "$TMP/reclaim.receipt" "$RECLAIM_MAP" > "$TMP/grow-sectors" 2> "$TMP/log" || abort "$(cat "$TMP/log")"
        target=$(cat "$TMP/grow-sectors")
        case "$target" in ''|*[!0-9]*) abort "Invalid saved filesystem target" ;; esac
        ui_print "Checking and growing the existing F2FS filesystem; no format."
        "$FSCK" -a "$RECLAIM_MAP" > "$TMP/fsck.log" 2>&1 || abort "F2FS check failed; larger GPT retained. Recovery log: $TMP/fsck.log"
        if "$TMP/piano-esp" grow-complete /dev/block/sda "$TMP/reclaim.receipt" "$RECLAIM_MAP" > /dev/null 2> "$TMP/already-grown.log"; then
            echo "Filesystem already covers the saved original capacity; no resize repeated." > "$TMP/resize.log"
        else
            "$RESIZE" -t "$target" "$RECLAIM_MAP" > "$TMP/resize.log" 2>&1 || abort "F2FS growth failed; larger GPT retained. Recovery log: $TMP/resize.log"
        fi
        "$TMP/piano-esp" grow-complete /dev/block/sda "$TMP/reclaim.receipt" "$RECLAIM_MAP" > "$TMP/grow-complete" 2> "$TMP/log" || abort "$(cat "$TMP/log")"
        reclaim_restore || abort "Cannot remount expanded data"
        trap - 0
        mkdir -p "$BACKUP_DIR" || abort "Cannot save completion logs"
        cp "$TMP/fsck.log" "$TMP/resize.log" "$TMP/grow-complete" "$BACKUP_DIR/" || abort "Cannot save completion logs"
        sync
        ui_print "ESP removed; original F2FS capacity verified and data remounted."
        ui_print "Uninstall/reclaim complete. You may reboot Android."
        exit 0
    fi
    [ "$reclaim_state" = reclaim-needed ] || abort "Unknown reclaim state"
    # This ZIP deletes the entire installer-created ESP, including added files.
    # Preserve the complete volume before changing boot or GPT.
    idx=$("$TMP/piano-esp" inspect /dev/block/sda) || abort "Cannot inspect owned ESP"
    [ "$idx" -gt 0 ] || abort "Owned ESP missing"
    mkdir -p "$BACKUP_DIR" || abort "Backup storage unavailable"
    ui_print "Backing up the entire owned ESP before reclaiming it."
    dd if="/dev/block/sda$idx" of="$BACKUP_DIR/esp-before-reclaim.img" bs=1M conv=fsync 2> "$TMP/log" || abort "ESP backup failed: $(cat "$TMP/log")"
    [ "$(wc -c < "$BACKUP_DIR/esp-before-reclaim.img")" = 536870912 ] || abort "Incomplete ESP backup"
    cmp "/dev/block/sda$idx" "$BACKUP_DIR/esp-before-reclaim.img" > /dev/null 2>&1 || abort "ESP backup readback differs"
    cp "$TMP/reclaim.receipt" "$BACKUP_DIR/esp-reclaim.receipt" || abort "Cannot back up ownership receipt"
    sync
}

reclaim_apply() {
    "$TMP/piano-esp" reclaim /dev/block/sda "$TMP/reclaim.receipt" "$BACKUP_DIR/gpt-before-reclaim.bin" --execute > "$TMP/reclaim-result" 2> "$TMP/log" || abort "$(cat "$TMP/log")"
    cp "$TMP/reclaim-result" "$BACKUP_DIR/" || abort "Cannot persist pending result"
    sync
    ui_print "Boot injection removed; ESP space added to userdata partition."
    ui_print "NOT FINISHED: reboot RECOVERY, decrypt/mount data, and flash this same reclaim ZIP again."
    ui_print "The second run grows F2FS. Do not format data. Backups: $BACKUP_DIR"
}
