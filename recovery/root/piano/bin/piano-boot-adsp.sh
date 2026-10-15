#!/system/bin/sh
#
# Start the ADSP so the recovery can see USB and charge.
#
# On this SoC the charger, USB presence and Type-C are managed by firmware on
# the ADSP, reached over glink. Recovery never started it, so the charger stack
# stayed blind -
#   qcom_subpmic_update_usb_type_work: Failed to read usb_online rc=-1
# - /sys/class/power_supply/usb read present=0 while adb was working over that
# same cable, and the tablet discharged for as long as it stayed in TWRP.
#
# The kernel loads the ADSP image with its direct firmware loader, which only
# searches firmware_class.path. That path is /piano/firmware/o8, the touch
# firmware, and the ADSP blobs live on the modem partition at /firmware/image.
# Swapping the path would race the touch driver, which can request its firmware
# again whenever the panel wakes, so the blobs are brought into the touch
# directory instead.
#
# They are COPIED, not symlinked. The kernel reads firmware under its own root
# credentials, and the stock modem mount makes the blobs 0440 system:system, so
# reading them in place needs CAP_DAC_READ_SEARCH for the kernel domain - which
# AOSP forbids outright:
#   neverallow kernel self:global_capability_class_set { dac_override dac_read_search };
#   "Instead of adding dac_{read_search,override}, fix the unix permissions"
# Root-owned 0644 copies in the ramdisk are exactly that, and need no policy
# change: the kernel already reads the rootfs-labelled touch firmware beside
# them. They cost about 34 MB of RAM.

LOG_TAG="piano-boot-adsp"
FW_SRC=/firmware/image
FW_DST=/piano/firmware/o8

log_msg() {
    echo "${LOG_TAG}: $1"
    log -t "${LOG_TAG}" "$1" 2>/dev/null
}

if ! ls "${FW_SRC}"/adsp.mdt >/dev/null 2>&1; then
    log_msg "no ADSP image under ${FW_SRC}; leaving the ADSP off"
    exit 0
fi

# adsp* rather than adsp.*: the ADSP also loads its own device tree from
# adsp_dtb.mdt/.bNN, and without it the boot fails after the main image -
#   qcom_q6v5_pas: request_firmware failed for adsp_dtb.mdt: -2
for f in "${FW_SRC}"/adsp*; do
    name="$(basename "${f}")"
    [ -f "${FW_DST}/${name}" ] && continue
    if ! cp "${f}" "${FW_DST}/${name}"; then
        log_msg "cannot copy ${name}; leaving the ADSP off"
        exit 0
    fi
    chmod 0644 "${FW_DST}/${name}"
done

# Find the ADSP by name: the remoteproc index is assigned at probe time.
for r in /sys/class/remoteproc/remoteproc*; do
    [ "$(cat "${r}/name" 2>/dev/null)" = "3000000.remoteproc-adsp" ] || continue

    if [ "$(cat "${r}/state")" = "running" ]; then
        log_msg "ADSP already running"
        exit 0
    fi
    echo start > "${r}/state"

    i=0
    while [ "${i}" -lt 15 ]; do
        if [ "$(cat "${r}/state")" = "running" ]; then
            log_msg "ADSP running after ${i}s"
            exit 0
        fi
        sleep 1
        i=$((i + 1))
    done
    log_msg "ADSP did not start; state=$(cat "${r}/state")"
    exit 0
done

log_msg "no ADSP remoteproc found"
exit 0
