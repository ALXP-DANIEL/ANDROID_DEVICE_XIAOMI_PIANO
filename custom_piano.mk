#
# SPDX-FileCopyrightText: The LineageOS Project
# SPDX-License-Identifier: Apache-2.0
#

# Inherit from those products. Most specific first.
$(call inherit-product, $(SRC_TARGET_DIR)/product/core_64_bit_only.mk)
$(call inherit-product, $(SRC_TARGET_DIR)/product/full_base.mk)

# Inherit some common PixelOS stuff.
$(call inherit-product, vendor/custom/config/common_full_tablet_wifionly.mk)
TARGET_SCREEN_WIDTH := 3200

# Inherit from piano device
$(call inherit-product, device/xiaomi/piano/device.mk)

# Release keys, when present.
$(call inherit-product-if-exists, vendor/lineage-priv/keys/keys.mk)

PRODUCT_NAME := custom_piano
PRODUCT_DEVICE := piano
PRODUCT_MANUFACTURER := Xiaomi
PRODUCT_BRAND := Xiaomi
PRODUCT_MODEL := Xiaomi Pad 8 Pro

PRODUCT_SYSTEM_NAME := piano
PRODUCT_SYSTEM_DEVICE := piano

PRODUCT_BUILD_PROP_OVERRIDES += \
    DeviceName=$(PRODUCT_SYSTEM_DEVICE) \
    DeviceProduct=$(PRODUCT_SYSTEM_NAME)

PRODUCT_GMS_CLIENTID_BASE := android-xiaomi
