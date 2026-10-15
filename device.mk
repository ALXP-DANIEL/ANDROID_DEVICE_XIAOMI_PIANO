#
# TWRP 16 / Android 16 product configuration for the Xiaomi Pad 8 Pro (piano).
#
# First image is deliberately minimal: boot, display, touch, USB/ADB, logging,
# SELinux Enforcing. No crypto, no stock secure-world HAL payload, no Android
# version compatibility libraries. Each of those is added later only when an
# exact, demonstrated dependency justifies it.

DEVICE_PATH := device/xiaomi/piano

$(call inherit-product, $(SRC_TARGET_DIR)/product/base.mk)
$(call inherit-product, $(SRC_TARGET_DIR)/product/core_64_bit_only.mk)
$(call inherit-product, $(SRC_TARGET_DIR)/product/emulated_storage.mk)
$(call inherit-product, vendor/twrp/config/common.mk)
$(call inherit-product, $(SRC_TARGET_DIR)/product/virtual_ab_ota/launch.mk)

PRODUCT_USE_DYNAMIC_PARTITIONS := true

PRODUCT_SOONG_NAMESPACES += \
    $(DEVICE_PATH)

# NOTE: the entire recovery/root/ tree is installed into the recovery ramdisk
# automatically. build/make/core/Makefile picks up
# $(TARGET_DEVICE_DIR)/recovery/root when TARGET_RECOVERY_DEVICE_DIRS is unset,
# and copies it over the staged ramdisk after Soong's own installs.
#
# Do NOT add PRODUCT_COPY_FILES entries for anything under recovery/root/.
# Doing so defines a second rule for the same output path and ckati fails with
# "overriding commands for target .../recovery/root/init.recovery.usb.rc,
# previously defined at out/soong/installs-twrp_piano.mk".
#
# The automatic copy happens after the Soong install, so a device file
# deliberately overrides TWRP's default of the same name. That is how this
# device replaces the stock init.recovery.usb.rc, whose legacy
# /sys/class/android_usb writes do not apply to this ConfigFS device.
#
# Installed automatically from recovery/root/:
#   init.recovery.qcom.rc          Qualcomm platform init and touch bring-up
#   init.recovery.usb.rc           Qualcomm USB ConfigFS initialisation
#   vendor/etc/ueventd.rc          recovery ueventd rules
#   piano/lib/modules/*.ko         H3 xiaomi_touch, nt36532_touch
#   vendor/odm/**                  complete touch stack: binary, libraries,
#                                  firmware, THP configs and TFLite models
#
# The touch stack must stay complete. libtouchreport.so dlopen()s
# libtouchreport%d_sensor.so, which requires libtensorflowlite_touch_c.so, so a
# DT_NEEDED closure does not reveal the real dependency set and the stack must
# not be trimmed for size on that basis.

# VINTF kernel requirements.
#
# Defaults to true for any product with PRODUCT_SHIPPING_API_LEVEL >= 29, which
# makes the build demand out/target/product/piano/kernel to extract a version
# and config from:
#   FAILED: ninja: 'out/target/product/piano/kernel', needed by
#     '.../check_vintf_all_intermediates/kernel_version.txt'
#
# This product builds no kernel and ships none. piano's recovery is ramdisk-only
# (kernel_size = 0) and runs on the active boot slot's ROM kernel, so there is
# no kernel image whose VINTF compatibility could meaningfully be checked here,
# and no OTA is produced from this tree.
#
# Setting this false states that fact rather than working around it. The
# reference trees instead carry a 34 MB prebuilt kernel purely to satisfy this
# check; that blob is not packaged into their recovery image either, and it
# would consume most of piano's recovery ramdisk budget.
PRODUCT_OTA_ENFORCE_VINTF_KERNEL_REQUIREMENTS := false

# Phase 4: stock Qualcomm TEE daemon (disabled in init, started manually).
PRODUCT_PACKAGES += \
    piano_minkdaemon \
    piano_ssgtzd \
    piano_qseecomd

# Phase 5: stock KeyMint HAL (disabled in init, started manually).
PRODUCT_PACKAGES += \
    piano_keymint

# Phases 6 and 7: stock Gatekeeper and Weaver HALs (disabled, started manually).
PRODUCT_PACKAGES += \
    piano_gatekeeper \
    piano_weaver_hos3 \
    piano_weaver_hos4

PRODUCT_SOONG_NAMESPACES += \
    hardware/qcom/bootctrl

PRODUCT_PACKAGES += \
    android.hardware.boot-service.qti.recovery


# Stock Qualcomm QWES daemon used by the Weaver secure path.
PRODUCT_PACKAGES += \
    piano_qwesd \
    piano_qwesd_seccomp
