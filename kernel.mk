# Deliberately preserve matched stock module metadata; do not run depmod
# against another kernel. Loading order comes from the stock modules.load files.
PRODUCT_COPY_FILES += \
    $(call find-copy-subdir-files,*,device/xiaomi/piano-kernel/vendor_ramdisk/,$(TARGET_COPY_OUT_VENDOR_RAMDISK)/lib/modules) \
    $(call find-copy-subdir-files,*,device/xiaomi/piano-kernel/vendor_dlkm/,$(TARGET_COPY_OUT_VENDOR_DLKM)/lib/modules)
