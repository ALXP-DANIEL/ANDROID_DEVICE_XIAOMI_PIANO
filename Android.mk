LOCAL_PATH := $(call my-dir)

# Stock HyperOS 3 Qualcomm TEE daemon, packaged unmodified as
# /vendor/bin/piano-qseecomd.
#
# SHA-256 dc1ccdd0a32891f0f38048383499e8849e071840ef5bb210eefa1b0e3b2c369f
#
# ELF dependency checking is disabled for this module because it links against
# the stock vendor runtime, which is mounted read-only at /vendor/piano-stock
# at runtime rather than packaged into the ramdisk.
include $(CLEAR_VARS)
LOCAL_MODULE := piano_qseecomd
LOCAL_MODULE_CLASS := EXECUTABLES
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_STEM := piano-qseecomd
LOCAL_SRC_FILES := prebuilt/qseecomd
LOCAL_MODULE_PATH := $(TARGET_RECOVERY_ROOT_OUT)/vendor/bin
LOCAL_MULTILIB := 64
LOCAL_STRIP_MODULE := false
LOCAL_CHECK_ELF_FILES := false
include $(BUILD_PREBUILT)

# Stock HyperOS 3 KeyMint HAL service, packaged unmodified as
# /vendor/bin/hw/piano-keymint. One process hosts IKeyMintDevice,
# IRemotelyProvisionedComponent, ISecureClock and ISharedSecret.
#
# SHA-256 bc3c6361a7d3e67385d1001f56891e1b49094ae9243530e05dc9a2c7c6acbd91
include $(CLEAR_VARS)
LOCAL_MODULE := piano_keymint
LOCAL_MODULE_CLASS := EXECUTABLES
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_STEM := piano-keymint
LOCAL_SRC_FILES := prebuilt/keymint-service-qti
LOCAL_MODULE_PATH := $(TARGET_RECOVERY_ROOT_OUT)/vendor/bin/hw
LOCAL_MULTILIB := 64
LOCAL_STRIP_MODULE := false
LOCAL_CHECK_ELF_FILES := false
include $(BUILD_PREBUILT)

# Stock HyperOS 3 Gatekeeper HAL.
# SHA-256 c66d485ff028a8ca7185f302e11d9ee846ffcc71b3cf9ce3bd9b0f7cf868728b
include $(CLEAR_VARS)
LOCAL_MODULE := piano_gatekeeper
LOCAL_MODULE_CLASS := EXECUTABLES
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_STEM := piano-gatekeeper
LOCAL_SRC_FILES := prebuilt/gatekeeper-service-qti
LOCAL_MODULE_PATH := $(TARGET_RECOVERY_ROOT_OUT)/vendor/bin/hw
LOCAL_MULTILIB := 64
LOCAL_STRIP_MODULE := false
LOCAL_CHECK_ELF_FILES := false
include $(BUILD_PREBUILT)

# Stock HyperOS 3 / Android 16 Xiaomi Weaver HAL.
# SHA-256 7b8974bef8e944f3cfc52659924e8d9e9beef1ae29dc43c2780c83e410af659c
include $(CLEAR_VARS)
LOCAL_MODULE := piano_weaver_hos3
LOCAL_MODULE_CLASS := EXECUTABLES
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_STEM := piano-weaver-hos3
LOCAL_SRC_FILES := prebuilt/weaver-service-hos3
LOCAL_MODULE_PATH := $(TARGET_RECOVERY_ROOT_OUT)/vendor/bin/hw
LOCAL_MULTILIB := 64
LOCAL_STRIP_MODULE := false
LOCAL_CHECK_ELF_FILES := false
include $(BUILD_PREBUILT)

# Stock HyperOS 4 / Android 17 Xiaomi Weaver HAL.
# SHA-256 1702d223b56421c6c652e58f5b30451b39a625ce6479212d7882696cc43934d7
include $(CLEAR_VARS)
LOCAL_MODULE := piano_weaver_hos4
LOCAL_MODULE_CLASS := EXECUTABLES
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_STEM := piano-weaver-hos4
LOCAL_SRC_FILES := prebuilt/weaver-service-hos4
LOCAL_MODULE_PATH := $(TARGET_RECOVERY_ROOT_OUT)/vendor/bin/hw
LOCAL_MULTILIB := 64
LOCAL_STRIP_MODULE := false
LOCAL_CHECK_ELF_FILES := false
include $(BUILD_PREBUILT)

# Stock HyperOS 3 Qualcomm GlobalPlatform trusted-application loader, packaged
# unmodified as /vendor/bin/piano-ssgtzd.
#
# SHA-256 d52f5ef5fd7dc40b9fa622f97b430552cb223771dd15f258be54bb871372ac1e
#
# Weaver is a GP TA rather than a QSEE listener, so qseecomd alone cannot reach
# it. This daemon loads the TA from the modem partition, which init mounts
# read-only at /vendor/firmware_mnt. Without it TEEC_OpenSession fails and TWRP
# never gets as far as checking the credential.
#
# It is packaged into the ramdisk rather than run from the stock vendor mount so
# that it carries a label this policy defines. Files on the mounted stock
# partition keep stock's own xattrs, including types such as vendor_ssgtzd_exec
# which do not exist here, and no domain transition would happen.
#
# ELF dependency checking is disabled for the same reason as the other four: it
# links the stock vendor runtime, resolved from /vendor/piano-stock/lib64.
include $(CLEAR_VARS)
LOCAL_MODULE := piano_ssgtzd
LOCAL_MODULE_CLASS := EXECUTABLES
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_STEM := piano-ssgtzd
LOCAL_SRC_FILES := prebuilt/ssgtzd
LOCAL_MODULE_PATH := $(TARGET_RECOVERY_ROOT_OUT)/vendor/bin
LOCAL_MULTILIB := 64
LOCAL_STRIP_MODULE := false
LOCAL_CHECK_ELF_FILES := false
include $(BUILD_PREBUILT)

# Stock HyperOS 3 HLOS Mink daemon, packaged unmodified as
# /vendor/bin/piano-minkdaemon.
#
# SHA-256 e8396f14435f7300847e74e05b326b3ebe4e328c33c9589573b602b5ec279efd
#
# This is the HLOS Mink opener for the GlobalPlatform transport used by
# libmi_weaver. Without its HLOSMINKD interface, libGPMTEEC_vendor cannot
# open the TA session and Weaver fails before credential verification.
#
# Found by way of MissMyTime/TWRP-Xiaomi, whose nezha notes record the same
# failure - metadata decryption succeeding, then "failed while querying Weaver
# key size" - and identify the HLOS Mink opener as the missing piece. Nothing in
# this device's own logs names it.
#
# ELF dependency checking is disabled for the same reason as the others: it
# links the stock vendor runtime, resolved from /vendor/piano-stock/lib64.
include $(CLEAR_VARS)
LOCAL_MODULE := piano_minkdaemon
LOCAL_MODULE_CLASS := EXECUTABLES
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_STEM := piano-minkdaemon
LOCAL_SRC_FILES := prebuilt/hlosminkdaemon
LOCAL_MODULE_PATH := $(TARGET_RECOVERY_ROOT_OUT)/vendor/bin
LOCAL_MULTILIB := 64
LOCAL_STRIP_MODULE := false
LOCAL_CHECK_ELF_FILES := false
include $(BUILD_PREBUILT)

# Stock HyperOS QWES daemon.
#
# Recovery packages it under a piano-specific name so the recovery-owned
# vendor tree does not depend on mounting stock /vendor over /vendor.
include $(CLEAR_VARS)
LOCAL_MODULE := piano_qwesd
LOCAL_MODULE_CLASS := EXECUTABLES
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_STEM := piano-qwesd
LOCAL_SRC_FILES := prebuilt/qwesd
LOCAL_MODULE_PATH := $(TARGET_RECOVERY_ROOT_OUT)/vendor/bin
LOCAL_MULTILIB := 64
LOCAL_STRIP_MODULE := false
LOCAL_CHECK_ELF_FILES := false
include $(BUILD_PREBUILT)


# Qualcomm QWES daemon seccomp policy.
# qwesd aborts immediately without this exact base policy.
include $(CLEAR_VARS)
LOCAL_MODULE := piano_qwesd_seccomp
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_STEM := qwesd@2.0.policy
LOCAL_SRC_FILES := prebuilt/qwesd@2.0.policy
LOCAL_MODULE_PATH := $(TARGET_RECOVERY_ROOT_OUT)/vendor/etc/seccomp_policy
include $(BUILD_PREBUILT)
