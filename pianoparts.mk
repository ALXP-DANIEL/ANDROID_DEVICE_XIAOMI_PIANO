# Latest PianoParts shared by LineageOS and Evolution X products.
ifeq ($(wildcard device/xiaomi/piano-parts/Android.bp),)
$(error Stage PianoParts with device/xiaomi/piano-parts/select-rom.py)
endif
PRODUCT_SOONG_NAMESPACES += device/xiaomi/piano-parts
PRODUCT_PACKAGES += PianoParts
# The capture bridge handles Android 16 and 17 platform API revisions.
ifneq ($(filter 36 37,$(PLATFORM_SDK_VERSION)),)
PRODUCT_PACKAGES += PianoPencilEngine
endif
PRODUCT_COPY_FILES += \
    device/xiaomi/piano-parts/privapp-permissions-pianoparts.xml:$(TARGET_COPY_OUT_SYSTEM_EXT)/etc/permissions/privapp-permissions-pianoparts.xml

# ALXPParts is excluded unless explicitly selected by the product.
$(call inherit-product, device/xiaomi/piano-parts/alxpparts/product.mk)
