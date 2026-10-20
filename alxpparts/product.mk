# SPDX-License-Identifier: Apache-2.0
# sync-device.sh --alxpparts writes selected.mk; WITH_ALXP_PARTS=true also works.
-include device/xiaomi/piano-parts/alxpparts/selected.mk
ifeq ($(WITH_ALXP_PARTS),true)
PRODUCT_PACKAGES += ALXPParts
PRODUCT_COPY_FILES += \
    device/xiaomi/piano-parts/alxpparts/privapp-permissions-alxpparts.xml:$(TARGET_COPY_OUT_SYSTEM_EXT)/etc/permissions/privapp-permissions-alxpparts.xml
endif
