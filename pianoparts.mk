# Latest PianoParts shared by LineageOS and Evolution X products.
ifeq ($(wildcard device/xiaomi/piano-parts/Android.bp),)
$(error Stage PianoParts with device/xiaomi/piano-parts/select-rom.py)
endif
PRODUCT_SOONG_NAMESPACES += device/xiaomi/piano-parts
PRODUCT_PACKAGES += PianoParts
# ScreenCapture's asynchronous API is available on the active Android 17 tree.
ifeq ($(PLATFORM_SDK_VERSION),37)
PRODUCT_PACKAGES += PianoPencilEngine
endif
PRODUCT_COPY_FILES += \
    device/xiaomi/piano-parts/privapp-permissions-pianoparts.xml:$(TARGET_COPY_OUT_SYSTEM_EXT)/etc/permissions/privapp-permissions-pianoparts.xml
