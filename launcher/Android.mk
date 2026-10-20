#
# SPDX-License-Identifier: Apache-2.0
#

LOCAL_PATH := $(call my-dir)

# Pixel Launcher from vendor/gms with a translucent, auto-hiding gesture
# handle. patch-launcher.py writes the APK when envsetup.sh is sourced.
ifneq ($(wildcard $(LOCAL_PATH)/prebuilt/NexusLauncherPiano.apk),)
include $(CLEAR_VARS)
LOCAL_MODULE := NexusLauncherPiano
LOCAL_MODULE_TAGS := optional
LOCAL_SRC_FILES := prebuilt/NexusLauncherPiano.apk
LOCAL_CERTIFICATE := releasekey
LOCAL_MODULE_CLASS := APPS
LOCAL_SYSTEM_EXT_MODULE := true
LOCAL_OVERRIDES_PACKAGES := NexusLauncherRelease Launcher3 Launcher3QuickStep Trebuchet QuickSearchBox
LOCAL_PRIVILEGED_MODULE := true
LOCAL_MODULE_SUFFIX := $(COMMON_ANDROID_PACKAGE_SUFFIX)
LOCAL_REQUIRED_MODULES := privapp_whitelist_com.android.launcher3
LOCAL_OPTIONAL_USES_LIBRARIES := com.android.extensions.appfunctions androidx.window.extensions androidx.window.sidecar
include $(BUILD_PREBUILT)
endif
