#!/usr/bin/env -S PYTHONPATH=../../../tools/extract-utils python3
#
# SPDX-FileCopyrightText: 2024 The LineageOS Project
# SPDX-License-Identifier: Apache-2.0
#

import hashlib
import sys
from pathlib import Path

import extract_utils.tools
from extract_utils.fixups_blob import (
    blob_fixup,
    blob_fixups_user_type,
)
from extract_utils.fixups_lib import (
    lib_fixup_remove,
    lib_fixups,
    lib_fixups_user_type,
)
from extract_utils.main import (
    ExtractUtils,
    ExtractUtilsModule,
)

namespace_imports = [
    'device/xiaomi/sm8750-common',
    'vendor/xiaomi/piano',
    'hardware/qcom-caf/wlan',
    'hardware/qcom-caf/sm8750',
    'hardware/xiaomi',
    'vendor/qcom/opensource/commonsys/display',
    'vendor/qcom/opensource/commonsys-intf/display',
    'vendor/qcom/opensource/dataservices',
]

def lib_fixup_vendor_suffix(lib: str, partition: str, *args, **kwargs):
    return f'{lib}-{partition}' if partition == 'vendor' else None

lib_fixups: lib_fixups_user_type = {
    **lib_fixups,
    (
        'vendor.qti.diaghal@1.0',
        'vendor.qti.diaghal-V1-ndk',
        'vendor.qti.ImsRtpService-V1-ndk',
        'android.hardware.bluetooth.audio-impl',
        'libbluetooth_audio_session_aidl',
    ): lib_fixup_vendor_suffix,
    (
        'libmialgo',
        'libaudioserviceexampleimpl',
    ): lib_fixup_remove,
}


blob_fixups: blob_fixups_user_type = {
    # Persist is mounted during fs; QSEE must not start in the earlier init phase.
    'vendor/etc/init/qseecomd.rc': blob_fixup()
        .regex_replace(r'on init\n    start vendor.qseecomd',
                       'on post-fs\n    start vendor.qseecomd')
        .regex_replace(r'    class core\n', '    class core\n    disabled\n'),
    # Haotian-specific HDR binary patches are excluded for China 307 piano.
    'odm/bin/hw/mfp-daemon': blob_fixup()
        .replace_needed(
            'android.hardware.biometrics.fingerprint-V5-ndk.so',
            'android.hardware.biometrics.fingerprint-V5-ndk_xiaomi.so'
        )
        .replace_needed(
            'android.hardware.sensors-V2-ndk.so',
            'android.hardware.sensors-V2-ndk_xiaomi.so'
        ),
    # host_init_verifier requires an explicit user; these stock services
    # already ran as root by default.
    'vendor/etc/init/audiohalservice_qti.rc': blob_fixup()
        .regex_replace(
            r'(service set_diag_state [^\n]*\n)',
            r'\1    user root\n'
        ),
    'vendor/etc/init/nicmd.rc': blob_fixup()
        .regex_replace(
            r'(service vendor\.nicmd [^\n]*\n)',
            r'\1    user root\n'
        ),
    'odm/etc/init/vendor.xiaomi.hw.touchfeature-service.rc': blob_fixup()
        .regex_replace(
            r'(service touch-kmsg-init-sh [^\n]*\n)',
            r'\1    user root\n'
        ),
    # idmanager's attestation check crashes on unlocked devices; it is still
    # started on demand through its AIDL interfaces.
    'odm/etc/init/vendor.xiaomi.hardware.idmanager.rc': blob_fixup()
        .regex_replace(
            r'    start vendor.idmanger\n',
            ''
        ),
    'odm/etc/init/init.mfp-daemon.aidl.rc': blob_fixup()
        .regex_replace(
            r'\n    seclabel u:r:vendor_mfp-daemon:s0',
            ''
        ),
    (
       'vendor/lib64/libwfdmmsrc_proprietary.so',
    ): blob_fixup()
        .replace_needed(
            'android.media.audio.common.types-V2-ndk.so',
            'android.media.audio.common.types-V3-ndk.so'
        ),
    'vendor/lib64/hw/libaudiocorehal.qti.so': blob_fixup()
        .replace_needed('android.hardware.audio.core.sounddose-V1-ndk.so', 'android.hardware.audio.core.sounddose-V2-ndk_xiaomi.so'),
    'vendor/lib64/libaudioserviceexampleimpl.so': blob_fixup()
        .add_needed('libaudioutils_shim.so')
        .replace_needed('android.hardware.audio.core.sounddose-V2-ndk.so', 'android.hardware.audio.core.sounddose-V2-ndk_xiaomi.so')
        .replace_needed('libaudio_aidl_conversion_common_ndk.so', 'libaudio_aidl_conversion_common_ndk_xiaomi.so'),
    (
        'vendor/lib64/soundfx/libbundleaidl.so',
        'vendor/lib64/soundfx/libdlbvolaidl.so',
        'vendor/lib64/soundfx/libhwdapaidl.so',
        'vendor/lib64/soundfx/liblvacfsprocessingaidl.so',
        'vendor/lib64/soundfx/libmiwndnsprocessingaidl.so',
        'vendor/lib64/soundfx/libozoaidl.so',
        'vendor/lib64/soundfx/libspatializeraidl.so',
        'vendor/lib64/soundfx/libswgamedapaidl.so',
        'vendor/lib64/soundfx/libswspatializeraidl.so',
        'vendor/lib64/libswspatializeraidl_ext.so',
    ): blob_fixup()
        .replace_needed(
            'libaudio_aidl_conversion_common_ndk.so',
            'libaudio_aidl_conversion_common_ndk_xiaomi.so'
        ),
     (
       'vendor/lib64/libsxrservice.so'
     ): blob_fixup()
        .replace_needed(
            'android.hardware.common-V2-ndk_platform.so',
            'android.hardware.common-V2-ndk.so'
        ),
    (
        'vendor/lib64/libqti-perfd.so',
    ): blob_fixup()
        .replace_needed('vendor.qti.hardware.display.config-V5-ndk.so', 'vendor.qti.hardware.display.config-V12-ndk.so'),
    (
        'vendor/lib64/soundfx/libquasar.so',
    ): blob_fixup()
        .replace_needed('libtinyxml2.so', 'libtinyxml2-v34.so'),
    (
        'vendor/lib64/libapengine.so',
    ): blob_fixup()
        .replace_needed('vendor.qti.hardware.display.config-V5-ndk.so', 'vendor.qti.hardware.display.config-V12-ndk.so')
        .replace_needed(
            'libtinyxml2.so',
            'libtinyxml2-v34.so'
        ),
     (
       'odm/bin/hw/vendor.xiaomi.hw.touchfeature-service',
       'odm/lib64/hw/displayfeature.default.so',
       'odm/lib64/libadaptivehdr.so',
       'odm/lib64/libcolortempmode.so',
       'odm/lib64/libdither.so',
       'odm/lib64/libflatmode.so',
       'odm/lib64/libhistprocess.so',
       'odm/lib64/libmiBrightness.so',
       'odm/lib64/libmiSensorCtrl.so',
       'odm/lib64/libpaperMode.so',
       'odm/lib64/librhytheyecare.so',
       'odm/lib64/libsdr2hdr.so',
       'odm/lib64/libsre.so',
       'odm/lib64/libtruetone.so',
       'odm/lib64/libvideomode.so',
       'vendor/lib64/libgnss.so',
     ): blob_fixup()
        .replace_needed(
            'android.hardware.sensors-V2-ndk.so',
            'android.hardware.sensors-V3-ndk.so'
        ),
     (
       'odm/lib64/hw/displayfeature.default.so',
     ): blob_fixup()
        .replace_needed(
            'android.hardware.sensors-V2-ndk.so',
            'android.hardware.sensors-V3-ndk.so'
        )
        .replace_needed(
            'libtinyxml2.so',
            'libtinyxml2-v34.so'
        ),
    (
       'vendor/bin/hw/vendor.qti.hardware.display.composer-service',
    ): blob_fixup()
        .replace_needed(
            'vendor.qti.hardware.display.composer3-V1-ndk.so',
            'vendor.qti.hardware.display.composer3-V3-ndk.so'
        )
        .replace_needed(
            'libtinyxml2.so',
            'libtinyxml2-v34.so'
        ),
    'vendor/bin/init.qti.display_boot.sh': blob_fixup()
        .regex_replace(
            r'\n        setprop debug\.sf\.enable_vrr_config 1\n        setprop vendor\.display\.enable_hal_self_refresh 1',
            '\n        # Set statically from vendor.prop; this domain cannot set debug_prop.\n        setprop vendor.display.enable_hal_self_refresh 1'
        ),
    (
       'vendor/lib64/libqcodec2_core.so',
    ): blob_fixup()
        .replace_needed(
            'android.hardware.graphics.common-V5-ndk.so',
            'android.hardware.graphics.common-V7-ndk.so'
        )
        .add_needed('libcodec2_shim.so'),
    (
       'vendor/lib64/libar-pal.so',
       'odm/lib64/libaudioroute_ext.so',
    ): blob_fixup()
        .add_needed('libaudioroute-xiaomi.so'),
    (
       'odm/lib64/libqc_hal.so',
       'odm/lib64/hw/fingerprint.qcom_us.so',
    ): blob_fixup()
        .replace_needed(
            'android.hardware.biometrics.fingerprint-V5-ndk.so',
            'android.hardware.biometrics.fingerprint-V4-ndk.so'
        )
        .replace_needed(
            'android.hardware.sensors-V2-ndk.so',
            'android.hardware.sensors-V3-ndk.so'
        ),
     (
       'odm/lib64/libmiXmlParser.so',
       'vendor/bin/hw/audiohalservice.qti',
       'vendor/bin/poweropt-service',
       'vendor/lib64/libaodoptfeature.so',
       'vendor/lib64/libaudiocloudctrl.so',
       'vendor/lib64/libcamerapoweroptfeature.so',
       'vendor/lib64/libgamepoweroptfeature.so',
       'vendor/lib64/liblearningmodule.so',
       'vendor/lib64/liboffscreenpoweroptfeature.so',
       'vendor/lib64/libpowercallback.so',
       'vendor/lib64/libpowercore.so',
       'vendor/lib64/libpsmoptfeature.so',
       'vendor/lib64/libsdmclient.so',
       'vendor/lib64/libstandbyfeature.so',
       'vendor/lib64/libvideooptfeature.so',
     ): blob_fixup()
        .replace_needed(
            'libtinyxml2.so',
            'libtinyxml2-v34.so'
        ),
    (
       'vendor/lib64/hw/libaudioeffecthal.qti.so',
    ): blob_fixup()
        .binary_regex_replace(b'libtinyxml2.so\\0', b'libtinyxmlQ.so\\0'),
    (
       'vendor/lib64/libtinyxmlQ.so',
    ): blob_fixup()
        .binary_regex_replace(b'libtinyxml2.so\\0', b'libtinyxmlQ.so\\0'),
    (
       'vendor/lib64/libVoiceSdk.so',
       'vendor/lib64/libcapiv2uvvendor.so',
       'vendor/lib64/liblistensoundmodel2vendor.so',
    ): blob_fixup()
        .replace_needed('libtensorflowlite_c.so', 'libtensorflowlite_c_vendor.so'),
}  # fmt: skip

module = ExtractUtilsModule(
    'sm8750-common',
    'xiaomi',
    blob_fixups=blob_fixups,
    lib_fixups=lib_fixups,
    namespace_imports=namespace_imports,
    check_elf=True,
)

if __name__ == '__main__':
    if len(sys.argv) != 2 or not Path(sys.argv[1]).is_dir():
        raise SystemExit('Supply exactly one offline Global 304 dump directory')
    source = Path(sys.argv[1])
    for line in Path(__file__).with_name('proprietary-files.txt').read_text().splitlines():
        if not line or line.startswith('#'):
            continue
        spec, expected, *_ = line.split('|')
        name = spec.lstrip('-').split(';')[0].split(':')[0]
        path = source / name
        if not path.is_file() or hashlib.sha1(path.read_bytes()).hexdigest() != expected:
            raise SystemExit(f'Missing or mismatched Global 304 input: {name}')
    utils = ExtractUtils.device(module)
    utils.run()
