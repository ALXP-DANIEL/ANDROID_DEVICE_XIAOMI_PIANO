# Existing private AVB key kept outside the source repositories.
# Pass a source-relative symlink to the external key via PIANO_AVB_KEY_PATH.
# Soong fsgen rejects absolute paths; no public AOSP fallback key.
ifeq ($(strip $(PIANO_AVB_KEY_PATH)),)
$(error Set PIANO_AVB_KEY_PATH to your external piano Lineage RSA4096 signing key)
endif
BOARD_AVB_ENABLE := true
BOARD_AVB_KEY_PATH := $(PIANO_AVB_KEY_PATH)
BOARD_AVB_ALGORITHM := SHA256_RSA4096
BOARD_AVB_ROLLBACK_INDEX := 0
# Keep AVB verification and dm-verity enabled.
BOARD_AVB_MAKE_VBMETA_IMAGE_ARGS += --flags 0
BOARD_MOVE_GSI_AVB_KEYS_TO_VENDOR_BOOT := true
BOARD_AVB_BOOT_KEY_PATH := $(PIANO_AVB_KEY_PATH)
BOARD_AVB_BOOT_ALGORITHM := SHA256_RSA4096
BOARD_AVB_BOOT_ROLLBACK_INDEX := 1788220800
BOARD_AVB_BOOT_ROLLBACK_INDEX_LOCATION := 3
BOARD_AVB_RECOVERY_KEY_PATH := $(PIANO_AVB_KEY_PATH)
BOARD_AVB_RECOVERY_ALGORITHM := SHA256_RSA4096
BOARD_AVB_RECOVERY_ROLLBACK_INDEX := 1
BOARD_AVB_RECOVERY_ROLLBACK_INDEX_LOCATION := 1
BOARD_AVB_VBMETA_SYSTEM := system system_ext product
BOARD_AVB_VBMETA_SYSTEM_KEY_PATH := $(PIANO_AVB_KEY_PATH)
BOARD_AVB_VBMETA_SYSTEM_ALGORITHM := SHA256_RSA4096
BOARD_AVB_VBMETA_SYSTEM_ROLLBACK_INDEX := 1788220800
BOARD_AVB_VBMETA_SYSTEM_ROLLBACK_INDEX_LOCATION := 2
# system_dlkm intentionally remains directly covered by top-level vbmeta.
BOOT_SECURITY_PATCH := 2026-09-01
VENDOR_SECURITY_PATCH := 2026-09-01
# The bootloader also verifies pvmfw and countrycode through vbmeta. Without
# their descriptors libavb reports invalid metadata and the slot falls back.
# Take the hash descriptors from the stock OS4.0.0.44 images (they carry AVB footers).
BOARD_AVB_MAKE_VBMETA_IMAGE_ARGS += \
    --include_descriptors_from_image $(PIANO_KERNEL_PATH)/avb/pvmfw.img \
    --include_descriptors_from_image $(PIANO_KERNEL_PATH)/avb/countrycode.img
