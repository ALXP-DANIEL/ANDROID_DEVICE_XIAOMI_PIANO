# Shared Notes app, staged from the reviewed HyperOS 4 APK.
# Manifest's mandatory micloud-sdk reference is removed from the rebuilt APK.
ifeq ($(wildcard vendor/xiaomi/piano-notes/Android.bp),)
$(error Stage Xiaomi Notes with vendor/xiaomi/piano-notes/stage-originals.py --from-parts)
endif
_piano_notes_ok := $(shell python3 vendor/xiaomi/piano-notes/stage-originals.py --check >/dev/null 2>&1 && echo verified)
ifneq ($(_piano_notes_ok),verified)
$(error Xiaomi Notes staged APK failed manifest/integrity verification)
endif
PRODUCT_PACKAGES += MIUINotes
