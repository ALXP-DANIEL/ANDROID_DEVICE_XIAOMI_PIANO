DEVICE_PATH := device/xiaomi/piano

include device/xiaomi/sm8750-common/BoardConfigCommon.mk

# Boot
BOARD_BOOT_HEADER_VERSION := 4
BOARD_KERNEL_PAGESIZE := 4096

# Physical partition sizes
BOARD_BOOTIMAGE_PARTITION_SIZE := 100663296
BOARD_INIT_BOOT_IMAGE_PARTITION_SIZE := 8388608
BOARD_VENDOR_BOOTIMAGE_PARTITION_SIZE := 100663296
BOARD_RECOVERYIMAGE_PARTITION_SIZE := 104857600
BOARD_DTBOIMG_PARTITION_SIZE := 25165824

# Dynamic partitions
BOARD_SUPER_PARTITION_SIZE := 13958643712

BOARD_QTI_DYNAMIC_PARTITIONS_PARTITION_LIST := \
    odm \
    product \
    system \
    system_dlkm \
    system_ext \
    vendor \
    vendor_dlkm

BOARD_QTI_DYNAMIC_PARTITIONS_SIZE := 13948157952
BOARD_SUPER_PARTITION_GROUPS := qti_dynamic_partitions

# Metadata
BOARD_USES_METADATA_PARTITION := true

# Recovery
# Use a recovery-only fstab. Android's first-stage fstab stays unchanged:
# recovery must not silently reformat encrypted /data after a failed mount.
TARGET_RECOVERY_FSTAB := $(DEVICE_PATH)/recovery.fstab
TARGET_RECOVERY_PIXEL_FORMAT := RGBX_8888

# The recovery partition is a kernel-less boot image on piano. Keep the
# working stock header v4 / legacy LZ4 layout inherited from the common tree.
# recovery/root/system/etc/init/ overrides the stock-derived recovery init
# without changing vendor init on normal Android boots.

# Filesystems
TARGET_USERIMAGES_USE_F2FS := true

# Platform
TARGET_BOARD_PLATFORM := sun

BOARD_EXCLUDE_KERNEL_FROM_RECOVERY_IMAGE := true

# Read-only logical partitions use EROFS; persist still needs ext4 image support.
TARGET_USERIMAGES_USE_EXT4 := true
BOARD_EROFS_COMPRESSOR := lz4hc,9
BOARD_SYSTEMIMAGE_FILE_SYSTEM_TYPE := erofs
BOARD_SYSTEM_EXTIMAGE_FILE_SYSTEM_TYPE := erofs
BOARD_PRODUCTIMAGE_FILE_SYSTEM_TYPE := erofs
BOARD_VENDORIMAGE_FILE_SYSTEM_TYPE := erofs
BOARD_ODMIMAGE_FILE_SYSTEM_TYPE := erofs
BOARD_VENDOR_DLKMIMAGE_FILE_SYSTEM_TYPE := erofs
BOARD_SYSTEM_DLKMIMAGE_FILE_SYSTEM_TYPE := erofs
BOARD_USES_VENDOR_DLKMIMAGE := true
BOARD_USES_SYSTEM_DLKMIMAGE := true
TARGET_COPY_OUT_VENDOR := vendor
TARGET_COPY_OUT_ODM := odm
TARGET_COPY_OUT_PRODUCT := product
TARGET_COPY_OUT_SYSTEM_EXT := system_ext
TARGET_COPY_OUT_VENDOR_DLKM := vendor_dlkm
TARGET_COPY_OUT_SYSTEM_DLKM := system_dlkm
include $(DEVICE_PATH)/BoardConfigKernel.mk
include $(DEVICE_PATH)/BoardConfigAvb.mk

include device/qcom/sepolicy_vndr/SEPolicy.mk
BOARD_VENDOR_SEPOLICY_DIRS += $(DEVICE_PATH)/sepolicy/vendor
DEVICE_MANIFEST_FILE += $(DEVICE_PATH)/configs/vintf/manifest.xml

TARGET_FS_CONFIG_GEN := $(COMMON_PATH)/configs/config.fs

DEVICE_FRAMEWORK_COMPATIBILITY_MATRIX_FILE += $(DEVICE_PATH)/configs/vintf/framework_matrix.xml
