# Kernel prebuilts from device/xiaomi/piano-kernel, built from the alxp kernel.
KERNEL_PATH := device/xiaomi/piano-kernel

TARGET_FORCE_PREBUILT_KERNEL := true
TARGET_KERNEL_CONFIG := piano_defconfig
TARGET_KERNEL_SOURCE := $(KERNEL_PATH)/kernel-headers
TARGET_KERNEL_VERSION := 6.6
TARGET_PREBUILT_KERNEL := $(KERNEL_PATH)/Image

BOARD_KERNEL_IMAGE_NAME := Image
BOARD_KERNEL_BASE := 0x00000000
BOARD_KERNEL_CMDLINE := video=vfb:640x400,bpp=32,memsize=3072000 erofs.reserved_pages=64 lz4asm.support=1
BOARD_MKBOOTIMG_ARGS += --dtb_offset 0x01f00000
BOARD_INCLUDE_DTB_IN_BOOTIMG := true
BOARD_PREBUILT_DTBIMAGE_DIR := $(KERNEL_PATH)/dtb
BOARD_PREBUILT_DTBOIMAGE := $(KERNEL_PATH)/dtbo.img
BOARD_BOOTCONFIG := androidboot.hardware=qcom androidboot.memcg=1 \
    androidboot.usbcontroller=a600000.dwc3 androidboot.load_modules_parallel=true \
    androidboot.hypervisor.protected_vm.supported=0 androidboot.vendor.qspa=true
# Send fatal init errors to recovery instead of fastboot.
BOARD_BOOTCONFIG += androidboot.init_fatal_reboot_target=recovery

# Kernel modules
BUILD_BROKEN_ELF_PREBUILT_PRODUCT_COPY_FILES := true
BOARD_VENDOR_RAMDISK_FRAGMENTS := dlkm
BOARD_VENDOR_RAMDISK_FRAGMENT.dlkm.PREBUILT := $(KERNEL_PATH)/modules/vendor_boot/dlkm.cpio
BOARD_VENDOR_RAMDISK_FRAGMENT.dlkm.MKBOOTIMG_ARGS := --ramdisk_type DLKM

BOARD_VENDOR_KERNEL_MODULES_LOAD := $(strip $(shell cat $(KERNEL_PATH)/modules/vendor_dlkm/modules.load))
BOARD_VENDOR_KERNEL_MODULES := $(wildcard $(KERNEL_PATH)/modules/vendor_dlkm/*.ko)
BOARD_VENDOR_KERNEL_MODULES_BLOCKLIST_FILE := $(KERNEL_PATH)/modules/vendor_dlkm/modules.blocklist
BOARD_DO_NOT_STRIP_VENDOR_MODULES := true

BOARD_SYSTEM_KERNEL_MODULES_LOAD := $(strip $(shell cat $(KERNEL_PATH)/modules/system_dlkm/modules.load))
BOARD_SYSTEM_KERNEL_MODULES := $(wildcard $(KERNEL_PATH)/modules/system_dlkm/*.ko)
