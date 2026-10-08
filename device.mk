LOCAL_PATH := device/xiaomi/piano

$(call inherit-product, device/xiaomi/sm8750-common/common.mk)

PRODUCT_SHIPPING_API_LEVEL := 36

# The stock kernel uses 4 KB pages (6.6.118-...-4k); prebuilt compat libraries
# such as libtinyxml2-v34 are 4 KB aligned, so skip the 16 KB ELF check.
PRODUCT_CHECK_PREBUILT_MAX_PAGE_SIZE := false

PRODUCT_CHARACTERISTICS := tablet

# Offline extraction must generate both vendor trees before product evaluation.
$(call inherit-product, vendor/xiaomi/piano/piano-vendor.mk)
$(call inherit-product, device/xiaomi/piano/kernel.mk)
PRODUCT_SOONG_NAMESPACES += device/xiaomi/piano device/xiaomi/sm8750-common

PRODUCT_COPY_FILES += \
    device/xiaomi/piano/rootdir/etc/fstab.qcom:$(TARGET_COPY_OUT_VENDOR)/etc/fstab.qcom \
    device/xiaomi/piano/rootdir/etc/fstab.qcom:$(TARGET_COPY_OUT_VENDOR_RAMDISK)/first_stage_ramdisk/fstab.qcom

PRODUCT_COPY_FILES += \
    device/xiaomi/piano/rootdir/etc/init.piano.audio.rc:$(TARGET_COPY_OUT_ODM)/etc/init/init.piano.audio.rc \
    device/xiaomi/piano/rootdir/bin/piano-persist-check.sh:$(TARGET_COPY_OUT_VENDOR)/bin/piano-persist-check.sh \
    device/xiaomi/piano/rootdir/bin/piano-body-color.sh:$(TARGET_COPY_OUT_VENDOR)/bin/piano-body-color.sh \
    device/xiaomi/piano/rootdir/etc/init.piano.body-color.rc:$(TARGET_COPY_OUT_VENDOR)/etc/init/init.piano.body-color.rc \
    device/xiaomi/piano/rootdir/etc/init.piano.wallpaper.rc:$(TARGET_COPY_OUT_SYSTEM_EXT)/etc/init/init.piano.wallpaper.rc \
    device/xiaomi/piano/parts/privapp-permissions-pianoparts.xml:$(TARGET_COPY_OUT_SYSTEM_EXT)/etc/permissions/privapp-permissions-pianoparts.xml

# Global 304 vendor properties required by the selected providers.
PRODUCT_VENDOR_PROPERTIES += \
    ro.hardware.egl=adreno \
    vendor.gralloc.enable_snapalloc=1 \
    vendor.gatekeeper.disable_spu=true \
    vendor.gatekeeper.is_security_level_spu=0

# Global 304 vendor/build.prop: the panel is mounted rotated; the NVT touch
# grid is portrait.
PRODUCT_VENDOR_PROPERTIES += \
    debug.sf.ignore_hwc_physical_display_orientation=true \
    ro.surface_flinger.primary_display_orientation=ORIENTATION_270

# Stock touchfeature init is installed by the adapted common vendor list.

# Minimal tablet values traced to the pinned stock framework overlay.
DEVICE_PACKAGE_OVERLAYS += $(LOCAL_PATH)/overlay

# Show the boot animation in landscape, the way the tablet is normally held.
PRODUCT_PRODUCT_PROPERTIES += \
    ro.bootanim.set_orientation_logical_0=ORIENTATION_90

# Stock HyperOS ignores the palm while the pen writes. Use the platform's
# palm rejection model for the same, on top of stylus-over-touch blocking.
PRODUCT_SYSTEM_PROPERTIES += \
    persist.device_config.input_native_boot.palm_rejection_enabled=1

# Global 304 product/etc/build.prop.
PRODUCT_PRODUCT_PROPERTIES += ro.sf.lcd_density=440

# Composer VINTF (including the Xiaomi IMiHwcExtension) is installed by the
# common vendor list; the device manifest must not declare it again.

# Shims (hardware/lineage/compat)
PRODUCT_PACKAGES += \
    libprocessgroup_shim \
    PianoParts

# Lineage LiveDisplay reading mode and high touch polling rate,
# served with the stock Xiaomi display and touch features
PRODUCT_PACKAGES += \
    vendor.lineage.livedisplay-service.piano

# Core features (app widgets, device admin, home screen, input methods, ...)
PRODUCT_COPY_FILES += \
    frameworks/native/data/etc/tablet_core_hardware.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/tablet_core_hardware.xml

# Special keys of the keyboard cover
PRODUCT_COPY_FILES += \
    device/xiaomi/piano/configs/keylayout/Vendor_15d9_Product_00a3.kl:$(TARGET_COPY_OUT_VENDOR)/usr/keylayout/Vendor_15d9_Product_00a3.kl

# USB (UVC) webcams, like the stock external camera provider. Its config is
# the stock vendor/etc/external_camera_config.xml.
PRODUCT_PACKAGES += \
    android.hardware.camera.provider-V1-external-service

PRODUCT_COPY_FILES += \
    frameworks/native/data/etc/android.hardware.camera.external.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.camera.external.xml

# MiuiCamera (optional, from vendor/xiaomi/piano-miuicamera)
$(call inherit-product-if-exists, vendor/xiaomi/piano-miuicamera/miuicamera.mk)

# Keyboard cover: do not rotate the arrow keys with the display.
PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/configs/idc/Vendor_15d9_Product_00a3.idc:$(TARGET_COPY_OUT_VENDOR)/usr/idc/Vendor_15d9_Product_00a3.idc

# Older interface libraries that stock camera and libui blobs load. Their
# modules are built without dependency lists, so name them here.
PRODUCT_PACKAGES += \
    android.hardware.graphics.allocator-V1-ndk.vendor \
    android.hardware.graphics.common-V5-ndk.vendor \
    android.hardware.sensors-V2-ndk.vendor \
    vendor.qti.hardware.camera.offlinecamera-V2-ndk.vendor \
    vendor.qti.hardware.display.config-V2-ndk.vendor
