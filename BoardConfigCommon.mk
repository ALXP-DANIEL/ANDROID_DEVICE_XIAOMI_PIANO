# Adapted from xiaomi-haotian-devs; original Apache-2.0 notices retained.
COMMON_PATH := device/xiaomi/sm8750-common

# Frozen vendor / board API level
BOARD_SHIPPING_API_LEVEL := 202404

# A/B
AB_OTA_UPDATER := true

AB_OTA_PARTITIONS := \
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

# Architecture
TARGET_ARCH := arm64
TARGET_ARCH_VARIANT := armv8-a-branchprot
TARGET_CPU_ABI := arm64-v8a
TARGET_CPU_ABI2 :=
TARGET_CPU_VARIANT := generic
TARGET_CPU_VARIANT_RUNTIME := oryon

# Boot image format
BOARD_BOOT_HEADER_VERSION := 4
BOARD_MKBOOTIMG_ARGS += --header_version $(BOARD_BOOT_HEADER_VERSION)

BOARD_INIT_BOOT_HEADER_VERSION := 4
BOARD_MKBOOTIMG_INIT_ARGS += --header_version $(BOARD_INIT_BOOT_HEADER_VERSION)

BOARD_KERNEL_PAGESIZE := 4096
BOARD_RAMDISK_USE_LZ4 := true
BOARD_USES_GENERIC_KERNEL_IMAGE := true

# Bootloader / platform
TARGET_NO_BOOTLOADER := true
BOARD_USES_QCOM_HARDWARE := true
TARGET_BOARD_PLATFORM := sun

# Audio
BOARD_SUPPORTS_OPENSOURCE_STHAL := true


# Filesystem capabilities and vendor AIDs from the upstream common tree.
TARGET_FS_CONFIG_GEN := $(COMMON_PATH)/configs/config.fs

# Properties
TARGET_ODM_PROP += $(COMMON_PATH)/configs/properties/odm.prop
TARGET_PRODUCT_PROP += $(COMMON_PATH)/configs/properties/product.prop
TARGET_SYSTEM_PROP += $(COMMON_PATH)/configs/properties/system.prop
TARGET_SYSTEM_EXT_PROP += $(COMMON_PATH)/configs/properties/system_ext.prop
TARGET_VENDOR_PROP += $(COMMON_PATH)/configs/properties/vendor.prop

# Common vendor policy
BOARD_VENDOR_SEPOLICY_DIRS += $(COMMON_PATH)/sepolicy/vendor
# WiFi
BOARD_WLAN_DEVICE := qcwcn
BOARD_HOSTAPD_DRIVER := NL80211
BOARD_HOSTAPD_PRIVATE_LIB := lib_driver_cmd_$(BOARD_WLAN_DEVICE)
BOARD_WPA_SUPPLICANT_DRIVER := NL80211
BOARD_WPA_SUPPLICANT_PRIVATE_LIB := lib_driver_cmd_$(BOARD_WLAN_DEVICE)
CONFIG_IEEE80211AX := true
WIFI_DRIVER_DEFAULT := qca_cld3
WIFI_DRIVER_STATE_CTRL_PARAM := "/dev/wlan"
WIFI_DRIVER_STATE_OFF := "OFF"
WIFI_DRIVER_STATE_ON := "ON"
WIFI_HIDL_FEATURE_DUAL_INTERFACE := true
WIFI_HIDL_UNIFIED_SUPPLICANT_SERVICE_RC_ENTRY := true
WPA_SUPPLICANT_VERSION := VER_0_8_X


include vendor/xiaomi/sm8750-common/BoardConfigVendor.mk

# VINTF: stock services whose fragments are not shipped as blobs.
DEVICE_MANIFEST_FILE += $(COMMON_PATH)/configs/vintf/manifest_xiaomi.xml

# VINTF: framework compatibility matrices for the stock Xiaomi and QTI HALs
# (manifests come from the stock vendor fragments).
DEVICE_FRAMEWORK_COMPATIBILITY_MATRIX_FILE += \
    $(COMMON_PATH)/configs/vintf/compatibility_matrix.xiaomi.xml \
    hardware/qcom-caf/common/vendor_framework_compatibility_matrix.xml
