#
# SPDX-License-Identifier: Apache-2.0
#

PRODUCT_SOONG_NAMESPACES += \
    vendor/xiaomi/piano-miuicamera

PRODUCT_PACKAGES += \
    MiuiCamera \
    libhidltransport

PRODUCT_SYSTEM_PROPERTIES += \
    ro.miui.notch=0 \
    ro.product.mod_device=piano

# China OS4.0.0.44 values MiuiCamera reads to pick its tablet layout.
PRODUCT_SYSTEM_PROPERTIES += \
    ro.miui.build.region=cn \
    ro.miui.ui.version.code=816 \
    ro.miui.ui.version.name=V816

# Let the camera HAL expose its private and logical cameras to MiuiCamera.
PRODUCT_VENDOR_PROPERTIES += \
    persist.vendor.camera.privapp.list=com.android.camera,org.lineageos.aperture \
    vendor.camera.aux.packagelist=com.android.camera,org.lineageos.aperture
