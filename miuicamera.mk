# Automatic Camera version selection for the current ROM's Android SDK.
ifeq ($(PLATFORM_SDK_VERSION),36)
_piano_camera_android := 16
else ifeq ($(PLATFORM_SDK_VERSION),37)
_piano_camera_android := 17
else
$(error Unsupported piano MiuiCamera Android SDK: $(PLATFORM_SDK_VERSION))
endif
_piano_camera_staged := $(shell python3 vendor/xiaomi/piano-miuicamera/select-camera.py $(_piano_camera_android) --check >/dev/null 2>&1 && echo yes)
ifneq ($(_piano_camera_staged),yes)
$(error Stage camera with python3 vendor/xiaomi/piano-miuicamera/select-camera.py $(_piano_camera_android))
endif
$(call inherit-product, vendor/xiaomi/piano-miuicamera/versions/android$(_piano_camera_android)/miuicamera.mk)
