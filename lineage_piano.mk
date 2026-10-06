$(call inherit-product, $(SRC_TARGET_DIR)/product/core_64_bit_only.mk)
$(call inherit-product, $(SRC_TARGET_DIR)/product/full_base.mk)

$(call inherit-product, device/xiaomi/piano/device.mk)

# AudioFX crashes on start (null preference key on Android 16); the stock
# sound stack is Dolby.
TARGET_EXCLUDES_AUDIOFX := true

$(call inherit-product, vendor/lineage/config/common_full_tablet_wifionly.mk)

PRODUCT_DEVICE := piano
PRODUCT_NAME := lineage_piano
PRODUCT_BRAND := Xiaomi
PRODUCT_MODEL := Xiaomi Pad 8 Pro
PRODUCT_MANUFACTURER := Xiaomi
