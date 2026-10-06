#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Extract proprietary files from an offline stock firmware dump."""
import hashlib
import sys
from pathlib import Path
ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'tools/extract-utils'))
from extract_utils.main import ExtractUtils, ExtractUtilsModule
from extract_utils.fixups_blob import blob_fixup
from extract_utils.fixups_lib import lib_fixups as default_lib_fixups


def stock_vendor_module(lib, partition, *args, **kwargs):
    return f"{lib}-vendor" if partition in ("vendor", "odm") else None


# Preserve stock vendor ABIs without replacing platform source modules.
lib_fixups = {**default_lib_fixups, ("libui", "libultrahdr", "libjpegencoder", "libjpegdecoder"): stock_vendor_module}

# V2 -> V3 frozen sensor API changes only add SensorType.MOISTURE_INTRUSION.
# Source sensorservice V1 imports V3; keep selected clients on that same ABI.
sensor_clients = ('odm/bin/hw/vendor.xiaomi.hw.touchfeature-service', 'odm/lib64/hw/displayfeature.default.so', 'odm/lib64/libadaptivehdr.so', 'odm/lib64/libcolortempmode.so', 'odm/lib64/libdither.so', 'odm/lib64/libflatmode.so', 'odm/lib64/libhistprocess.so', 'odm/lib64/libmiBrightness.so', 'odm/lib64/libmiSensorCtrl.so', 'odm/lib64/libpaperMode.so', 'odm/lib64/librhytheyecare.so', 'odm/lib64/libsdr2hdr.so', 'odm/lib64/libsre.so', 'odm/lib64/libtruetone.so', 'odm/lib64/libvideomode.so')
# These blobs were built against the Android 14 tinyxml2 ABI; the current
# libtinyxml2 crashes them (e.g. DisplayFeature in ParamManager::getParam).
tinyxml2_clients = tuple(
    [f'odm/lib64/camera/plugins/com.xiaomi.plugin.{p}.so' for p in (
        'anchor', 'offlineawbideal', 'offlineb2y', 'offlineformatconvertor',
        'offlinehdrraw2y', 'offlineheic', 'offlinei2y', 'offlinejpeg',
        'offlinemfnr', 'offlinemlawb', 'offlinetintlesshdr', 'offlinetintless',
        'offlineyuvreprocess', 'offlineyuvsplit')] +
    ['odm/lib64/libmiXmlParser.so',
     'vendor/bin/hw/vendor.qti.camera.provider-service_64',
     'vendor/bin/hw/vendor.xiaomi.hardware.miperf2-service',
     'vendor/lib64/libcamxcoreutils.so', 'vendor/lib64/libcamxods.so',
     'vendor/lib64/libmicamera_aidl_provider.so',
     'vendor/lib64/libmicamera_hal_core.so', 'vendor/lib64/libsimulation.so'])
blob_fixups = {
    tuple(c for c in sensor_clients if c != 'odm/lib64/hw/displayfeature.default.so'):
        blob_fixup().replace_needed(
            'android.hardware.sensors-V2-ndk.so', 'android.hardware.sensors-V3-ndk.so'),
    ('odm/lib64/hw/displayfeature.default.so',): blob_fixup()
        .replace_needed('android.hardware.sensors-V2-ndk.so', 'android.hardware.sensors-V3-ndk.so')
        .replace_needed('libtinyxml2.so', 'libtinyxml2-v34.so'),
    tinyxml2_clients: blob_fixup().replace_needed('libtinyxml2.so', 'libtinyxml2-v34.so'),
    # Stock HyperOS audio config carries MIUI framework extensions that the
    # Lineage framework rejects, dropping the whole primary module or engine
    # configuration. Strip only those parts.
    'odm/etc/audio/audio_module_config_primary.xml': blob_fixup()
        .regex_replace(r'(?s)        <!-- MIUI ADD: Audio_MultiRoute -->\n        <route type="mix" sink="proxy_out" sources="([^"]*),virtual_deep_buffer" />\n.*?<!-- END Audio_MultiRoute -->\n',
                       r'        <route type="mix" sink="proxy_out" sources="\1" />\n')
        .regex_replace(r'(?s)[ \t]*<!-- MIUI ADD: Audio_MultiRoute -->\n.*?<!-- END Audio_MultiRoute -->\n', ''),
    'odm/etc/audio_policy_engine_product_strategies_mi.xml': blob_fixup()
        .regex_replace(r' +<Attributes> <Usage value="AUDIO_USAGE_BLUETOOTH_SCO"/> </Attributes>\n', ''),
    # MIUI device categories are unknown to AOSP. The MIUI media index runs to
    # 150; AOSP stops at 100, so map the speaker curve onto 0-100 and keep the
    # stock maximum (-0.3 dB) at the top of the range.
    'odm/etc/audio_policy_engine_stream_volumes_mi.xml': blob_fixup()
        .regex_replace(r'[ \t]*<volume deviceCategory="DEVICE_CATEGORY_(?:USB|A2DP|HEADSET_CE|HEADSET_SPATIALIZER_CE)[A-Z0-9_]*" ref="[^"]*"/>\n', '')
        .regex_replace(r'(?s)[ \t]*<volume deviceCategory="DEVICE_CATEGORY_(?:USB|A2DP|HEADSET_CE|HEADSET_SPATIALIZER_CE)[A-Z0-9_]*">\n.*?</volume>\n', '')
        .regex_replace(r'<point>60,-2950</point>\n( +)<point>70,-2600</point>\n +<point>80,-2250</point>\n +<point>90,-1900</point>\n +<point>100,-1550</point>\n +<point>110,-1200</point>\n +<point>120,-850</point>\n +<point>130,-550</point>\n +<point>140,-250</point>\n +<point>150,-30</point>',
                       r'<point>59,-2950</point>\n\1<point>69,-2600</point>\n\1<point>79,-2250</point>\n\1<point>89,-1900</point>\n\1<point>99,-1550</point>\n\1<point>100,-30</point>'),
    # Stock libprocessgroup exports SetTaskProfiles wrappers Android 16 dropped.
    'vendor/lib64/libcameraopt.so': blob_fixup().add_needed('libprocessgroup_shim.so'),
    # Stock ships these with a malformed declaration ('<?xml=version').
    tuple(f'odm/etc/camera/{n}motiontuning.xml' for n in (
        '', 'enhance_', 'snsc_', 'snsc_bokeh_', 'snsc_enhance_', 'snsc_noface_')):
        blob_fixup().regex_replace(r'^<\?xml=version', '<?xml version'),
}
module = ExtractUtilsModule(
    'piano', 'xiaomi', check_elf=True, blob_fixups=blob_fixups, lib_fixups=lib_fixups,
    namespace_imports=['hardware/qcom-caf/sm8750',
                       'vendor/qcom/opensource/commonsys-intf/display',
                       'vendor/xiaomi/sm8750-common'],
)
if __name__ == '__main__':
    if '--regenerate_makefiles' not in sys.argv:
        if len(sys.argv) != 2 or not Path(sys.argv[1]).is_dir():
            raise SystemExit('Supply exactly one offline extracted directory; device extraction is disabled')
        lists = [Path(__file__).with_name('proprietary-files.txt')]
        lists.append(Path(__file__).parent.parent / 'sm8750-common/proprietary-files.txt')
        source = Path(sys.argv[1])
        for manifest in lists:
            for line in manifest.read_text().splitlines():
                if not line or line.startswith('#'):
                    continue
                name, expected, *_ = line.split('|')
                path = source / name.lstrip('-').split(';')[0].split(':')[0]
                if not path.is_file() or hashlib.sha1(path.read_bytes()).hexdigest() != expected:
                    raise SystemExit(f'Missing or mismatched pinned stock input: {name}')
    ExtractUtils.device_with_common(module, 'sm8750-common', module.vendor).run()
