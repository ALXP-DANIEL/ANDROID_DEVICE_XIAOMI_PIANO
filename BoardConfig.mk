#
# TWRP 16 / Android 16 device configuration for the Xiaomi Pad 8 Pro (piano).
#
# Written for the native Android-16 base, not ported from the TWRP14 tree. The
# TWRP14 configuration stays frozen at device/xiaomi/piano in ~/android/twrp-14
# and is referenced only for hardware facts that were proven on the device.
#
# ALLOW_MISSING_DEPENDENCIES — enabled deliberately, with a compensating gate.
#
# The TWRP manifest is a reduced AOSP checkout: it ships projects whose Soong
# dependencies live in projects it omits, and Soong parses every Android.bp in
# the tree regardless of build target. Removing the unneeded consumers was
# tried first and diverged: 32 errors -> 20 -> 26, and removing
# packages/modules/Connectivity broke system/netd, which recovery links through
# libnetd_client. AOSP projects cross-reference too heavily for that approach
# to terminate.
#
# The risk this flag carries is real: a module our recovery actually needs
# could be silently skipped instead of failing the build. That risk is
# answered by a mandatory ELF closure audit of the produced ramdisk before any
# image is flashed - every binary and shared library is resolved and any
# unresolved DT_NEEDED fails the gate. See docs/twrp16-first-image-elf-audit.md.
#
# This flag does not affect this device tree's own dependencies, which resolve
# completely. BUILD_BROKEN_* is still NOT set.
ALLOW_MISSING_DEPENDENCIES := true

DEVICE_PATH := device/xiaomi/piano

# Architecture
TARGET_ARCH := arm64
TARGET_ARCH_VARIANT := armv8-a
TARGET_CPU_ABI := arm64-v8a
TARGET_CPU_ABI2 :=
TARGET_CPU_VARIANT := generic

# Bootloader / platform
PRODUCT_PLATFORM := sun
TARGET_BOOTLOADER_BOARD_NAME := $(PRODUCT_PLATFORM)
TARGET_NO_BOOTLOADER := true
TARGET_BOARD_PLATFORM := sun

# Recovery image geometry.
#
# Stock piano recovery is ramdisk-only: kernel_size is 0 and the active boot
# slot supplies the kernel. Header v4, 4096-byte pages, legacy LZ4 ramdisk.
BOARD_BOOT_HEADER_VERSION := 4
BOARD_KERNEL_PAGESIZE := 4096
# Ramdisk compression: legacy LZ4. Do not change this.
#
# Tested on hardware 2026-09-14: a gzip ramdisk BOOTLOOPS at the splash screen.
# The device never enumerates USB in any mode and has to be forced into
# fastboot by hand.
#
# The kernel config is not sufficient evidence here. /proc/config.gz reports
# CONFIG_RD_GZIP=y, CONFIG_RD_LZ4=y and CONFIG_RD_ZSTD=y, so the kernel can
# decompress all three - but the bootloader hands the image to the kernel and
# evidently requires the legacy LZ4 format that stock recovery ships. Every
# image that has ever booted on this device uses it.
#
# This also rules out zstd, which would otherwise have been very attractive:
#
#   lz4 legacy -12  28,905,980 bytes    (boots)
#   gzip -9         24,931,569 bytes    (BOOTLOOPS)
#   zstd -19        17,107,486 bytes    (untested, same risk, not worth it)
#
# The size budget must therefore be met by content, not compression.
BOARD_RAMDISK_USE_LZ4 := true

BOARD_EXCLUDE_KERNEL_FROM_RECOVERY_IMAGE := true
BOARD_RECOVERYIMAGE_PARTITION_SIZE := 104857600
BOARD_MKBOOTIMG_ARGS += --header_version $(BOARD_BOOT_HEADER_VERSION)
BOARD_MKBOOTIMG_ARGS += --pagesize $(BOARD_KERNEL_PAGESIZE)

# The recovery uses the active-slot ROM kernel, so no kernel is built or
# packaged. No prebuilt kernel is carried, unlike the reference trees.
TARGET_NO_KERNEL_OVERRIDE := true
# No kernel source: hardware/qcom/bootctrl only needs generated kernel
# headers to exist (see kernel-headers/Makefile).
TARGET_KERNEL_SOURCE := $(DEVICE_PATH)/kernel-headers

# RECOVERY RAMDISK SIZE BUDGET — hard constraint, see
# docs/twrp14-frozen-reference.md.
#
# The piano bootloader refuses to load a recovery whose compressed ramdisk
# exceeds a measured bound bracketed to 29,210,310 bytes (boots) and
# 29,228,841 bytes (fails). Over that, the device returns to fastboot and
# marks slot A unbootable without ever executing the ramdisk. Probable cause
# is a shared load region: stock vendor_boot declares a 25.556 MiB vendor
# ramdisk, and the Qualcomm loader checks the region against the sum of the
# recovery and vendor ramdisks.
#
# Budget every image against 29,000,000 bytes to keep margin. This is why no
# WLAN module, no prebuilt kernel and no stock HAL payload are packaged here.

# A/B
AB_OTA_UPDATER := true
AB_OTA_PARTITIONS += \
    boot \
    dtbo \
    init_boot \
    odm \
    product \
    recovery \
    system \
    system_dlkm \
    system_ext \
    vbmeta \
    vbmeta_system \
    vendor \
    vendor_boot \
    vendor_dlkm

# Dynamic partitions
BOARD_SUPER_PARTITION_SIZE := 13958643712
BOARD_SUPER_PARTITION_GROUPS := qti_dynamic_partitions
BOARD_QTI_DYNAMIC_PARTITIONS_SIZE := 13948157952
BOARD_QTI_DYNAMIC_PARTITIONS_PARTITION_LIST := \
    odm \
    product \
    system \
    system_dlkm \
    system_ext \
    vendor \
    vendor_dlkm

# Verified boot. AVB stays enabled; it is never disabled to make an image boot.
BOARD_AVB_ENABLE := true

# Filesystems
TARGET_USERIMAGES_USE_EXT4 := true
TARGET_USERIMAGES_USE_F2FS := true
BOARD_USES_METADATA_PARTITION := true

# Partition mount points
TARGET_COPY_OUT_VENDOR := vendor

# /firmware must exist in the ramdisk for the modem bind mount to land.
#
# init mounts the modem partition read-only at /vendor/firmware_mnt and binds it
# to /firmware, which is where Qualcomm's loader looks for GlobalPlatform TA
# images. Without this the bind silently does nothing - mount reports success
# against a missing directory - and Weaver's TA cannot be found:
#
#   GPMTEEC: [MTEEC.cpp:795 TEEC_MTEE_OpenSession] openSession() failed: 11
#
# /persist is deliberately NOT listed. The persist partition is mounted, but at
# /mnt/vendor/persist and read-only, which is where stock puts it; nothing needs
# a /persist root directory and creating one would invite something to mount it
# there by accident.
BOARD_ROOT_EXTRA_FOLDERS := firmware piano

# SELinux. Enforcing is a requirement, never relaxed to make a stage pass.
BOARD_VENDOR_SEPOLICY_DIRS += $(DEVICE_PATH)/sepolicy/vendor

# Recovery
TARGET_RECOVERY_FSTAB := $(DEVICE_PATH)/recovery.fstab
TARGET_RECOVERY_PIXEL_FORMAT := RGBX_8888

# Ramdisk prune hook.
#
# BOARD_RECOVERY_IMAGE_PREPARE is run by build/make/core/Makefile as the final
# step of the recovery ramdisk staging recipe, after every module has been
# installed and after the ramdisk-files manifests have been written. The script
# removes payload proven dead in this image and regenerates both manifests.
#
# This is the only lever left for size on this device: the bootloader's
# compressed-ramdisk bound is fixed, and compression cannot be changed because
# only legacy LZ4 boots. The ELF closure gate runs afterwards on the packed
# image and fails the build if anything pruned was actually needed.
# Assigned with '=' , not ':=' . TARGET_RECOVERY_ROOT_OUT is not defined yet
# when BoardConfig.mk is parsed; a simple assignment captures it empty and the
# script is invoked with no argument. Recursive assignment defers expansion to
# the point the recipe runs, where the variable is set.
BOARD_RECOVERY_IMAGE_PREPARE = bash $(DEVICE_PATH)/tools/piano16-ramdisk-prune.sh $(TARGET_RECOVERY_ROOT_OUT)

# Display / input, proven on hardware in the TWRP14 tree.
TW_THEME := portrait_hdpi

# Chinese and other non-Latin languages, with the CJK fallback font. Without
# this, Chinese file names and text show as empty boxes (issue #1).
TW_EXTRA_LANGUAGES := true
TW_DEFAULT_LANGUAGE := en

# Landscape, phone-sized. OrangeFox ships only a phone (portrait) theme. Drawn
# across the whole 3200x2136 panel it is stretched about 3x sideways, which
# breaks image elements such as the battery icon and the logs slider. Instead
# the theme is drawn into a 1202x2136 column in the middle of the screen, which
# keeps its default 9:16 shape (1080x1920 * 1.1125):
# width 3200 - 1998 = 1202, left edge at (3200 - 1202) / 2 = 999.
# Touch keeps the landscape mapping below; the GUI adds the same offset to
# every element, so taps line up.
TW_X_OFFSET := 999
TW_W_OFFSET := -1998
RECOVERY_TOUCHSCREEN_SWAP_XY := true
RECOVERY_TOUCHSCREEN_FLIP_Y := true

# Backlight.
#
# This panel's range is 0-4095 (/sys/class/backlight/panel0-backlight/
# max_brightness). TWRP assumes a maximum of 255 when TW_MAX_BRIGHTNESS is
# unset, so its "full brightness" landed at 255/4095 - about 6% - and the
# screen was barely readable. Observed in recovery.log as
# "Set_Brightness: Setting brightness control to 255".
TW_BRIGHTNESS_PATH := /sys/class/backlight/panel0-backlight/brightness
TW_MAX_BRIGHTNESS := 4095
TW_DEFAULT_BRIGHTNESS := 2048

# Device-specific Qualcomm USB ConfigFS setup replaces TWRP's default.
TW_EXCLUDE_DEFAULT_USB_INIT := true

# Stock vendor fstab contains a duplicate unsupported mifs /data entry.
TW_SKIP_ADDITIONAL_FSTAB := true

# Dynamic partition / fastbootd support
TW_INCLUDE_FASTBOOTD := true

# Size discipline. Both public SM8750 reference trees set this; it removes the
# stock-APEX loading integration. It is not a blanket "remove all APEX"
# switch, so the ELF closure is verified separately.
TW_EXCLUDE_APEX := true

# Bring-up logging
TARGET_USES_LOGD := true
TWRP_INCLUDE_LOGCAT := true

# Crypto.
#
# Enabled. This is the point of the migration: TWRP14 with TW_INCLUDE_CRYPTO
# returned to fastboot immediately, which is the failure that motivated moving
# to the Android-16 base. TWRP16 with crypto boots, runs, and reaches its
# decrypt UI, with SELinux Enforcing and the stock secure HALs registered.
#
# Proven on hardware at 31,265,294 bytes compressed - 2,054,984 above the bound
# the TWRP14 tree measured. That bound was recorded as a property of the
# bootloader; it is not one. Nothing is pruned to make this fit.
#
# The Phase 7S vold safety invariants are compiled in (see patches/). They are
# not yet exercised at runtime.
TW_INCLUDE_CRYPTO := true
TW_INCLUDE_CRYPTO_FBE := true
TW_INCLUDE_FBE := true
TW_USE_FSCRYPT_POLICY := 2
TW_INCLUDE_FBE_METADATA_DECRYPT := true

# OS version and security patch level.
#
# KeyMint binds every key to os_version, os_patchlevel, vendor_patchlevel and
# boot_patchlevel, and refuses a key whose bound values do not match what it
# reports. The recovery ships the AOSP defaults - security patch 2025-06-05,
# and no vendor patch level at all - against firmware reporting 2026-07-01 for
# system and 2026-02-01 for vendor.
#
# The first attempt aligned the recovery's values with the ROM's, read from the
# device's own build.prop at runtime. That removed -62 KEY_REQUIRES_UPGRADE, but
# decryption still failed, because the direction was wrong: KeyMint only ever
# upgrades a key FORWARD. Reporting 2025-06-05 against a key bound to 2026-07-01
# asks for a downgrade, which cannot be done, and the upgradeKey() that followed
# failed with -8.
#
# Setting the values far in the future instead makes every key look as though it
# needs a routine forward upgrade, which KeyMint performs, and the operation then
# proceeds. KEY_REQUIRES_UPGRADE is the normal path here, not a fault to avoid.
#
# Taken from daixu0502/twrp_device_xiaomi_sm8750_bixi, the Xiaomi MIX Flip 2
# tree for this same SoC and HyperOS generation, where decryption works.
#
# This does NOT modify key material. The upgraded blob is used for the operation
# and deliberately never written back - see the Phase 7S change to
# BeginKeystoreOp() in patches/system_vold/.
# Values are a MINIMAL step forward, not the far-future ones bixi uses.
#
# 2099-12-31 with version 99.87.36 was tried first, copied from that tree. It
# did move the failure: KeyMint asked for the forward upgrade as intended
# (-62 KEY_REQUIRES_UPGRADE), but QTI's upgrade_key then refused with -8 and
# keystore2 logged "Upgrade failed.". A TA that sanity-checks its inputs would
# reject a patch level 73 years ahead and an OS version of 99, so this tries the
# smallest step that is still forward of the key: the ROM's own OS version, and
# a patch level one quarter past the firmware's 2026-07-01.
#
# bixi's values are not a proven recipe - that tree does not claim decryption
# works, and it runs an Android 15 stock stack in a permissive domain, so its
# KeyMint TA is not necessarily ours.
# EXACT match to the firmware, stamped at build time.
#
# Three configurations have now been tried against this TEE:
#
#   recovery reports                     result
#   2025-06-05, no vendor patch level    -62, then upgrade_key -8
#   2026-10-01 / 2099-12-31 (forward)    -62, then upgrade_key -8
#   2026-07-01 with vendor empty         -8 at begin()
#
# So QTI's upgrade_key refuses on this TA whatever we ask of it, and a forward
# patch level only guarantees we enter that broken path. The far-future values
# from the bixi tree behave no differently from a one-quarter step, which also
# rules out the TA sanity-checking implausible inputs.
#
# The remaining option is to need no upgrade at all: report exactly what the key
# was bound to, so KeyMint accepts it as-is. These are the firmware's own values,
# read off the device - system 2026-07-01, vendor 2026-02-01 - and they must be
# stamped at build time, because KeyMint caches them when it starts. The earlier
# attempt applied them at runtime and was too late: reading the vendor value
# requires mapping /vendor, which is what starts the HAL chain in the first place.
#
# These are the fallback values only. /piano/bin/piano-prepdecrypt.sh now reads
# the installed ROM's own values at boot and applies them with resetprop before
# qseecomd and KeyMint start, so one image should be correct on any firmware.
# What is stamped here is what the recovery reports if that script fails.
#
# Keeping them current is therefore no longer required, but it is still the
# safe thing to do after an update: re-read /system/build.prop and
# /vendor/build.prop from the device. Current fallback values are OS4.0.0.42's.
# The standalone resetprop binary. libresetprop is already linked into the
# recovery by TW_INCLUDE_CRYPTO, but init cannot call into it; the prepdecrypt
# script needs the executable.
TW_INCLUDE_RESETPROP := true

# Battery gauge from sysfs rather than the health HAL. Recovery ships no health
# HAL, so the HAL path found nothing and reported a fake 100% whatever the real
# charge was. /sys/class/power_supply/battery carries the real capacity and,
# once piano-boot-adsp.sh has started the ADSP, the real charging status.
TW_USE_LEGACY_BATTERY_SERVICES := true

# Displayed version: 3.7.1_16-1(ALXP). TWRP appends this as "-<value>".
TW_DEVICE_VERSION := 1(ALXP)

PLATFORM_VERSION := 16
PLATFORM_VERSION_LAST_STABLE := $(PLATFORM_VERSION)
PLATFORM_SECURITY_PATCH := 2026-09-01
VENDOR_SECURITY_PATCH := 2026-02-01
BOOT_SECURITY_PATCH := 2026-02-01

# Qualcomm RTC / ATS time correction.
# Piano RTC ticks from the 1970 epoch; the real wall-clock offset is stored
# by stock Android in /data/vendor/time/ats_2 and consumed by TWRP.
TARGET_RECOVERY_QCOM_RTC_FIX := true

# Recovery AVB
# Produce a flash-ready 100 MiB recovery image directly from `m recoveryimage`.
BOARD_AVB_RECOVERY_KEY_PATH := device/xiaomi/piano/security/recovery_avb.pem
BOARD_AVB_RECOVERY_ALGORITHM := SHA256_RSA4096
BOARD_AVB_RECOVERY_ROLLBACK_INDEX := 1
BOARD_AVB_RECOVERY_ROLLBACK_INDEX_LOCATION := 0


# Flashlight. OrangeFox defaults to led:torch_0 + led:switch_0, but this
# device only has led:switch_2, so the torch never turned on.
OF_FL_PATH1 := /sys/class/leds/led:torch_0
OF_FL_PATH2 := /sys/class/leds/led:switch_2

# A/B device with a separate recovery partition. OrangeFox then reflashes
# itself to both recovery slots after a ROM/OTA install (tw_auto_reflashtwrp);
# otherwise the OTA leaves stock recovery in the new slot.
OF_AB_DEVICE_WITH_RECOVERY_PARTITION := 1

# No vibration motor on piano (both haptics devicetree nodes are disabled).
# Removes the vibration settings and the useless vibrate calls.
TW_NO_HAPTICS := true
