# Xiaomi display additions

The stock composer and SDM libraries on piano are Qualcomm's display stack with
Xiaomi code added. This page lists what Xiaomi added, who triggers it, and
which parts are carried into the CAF source build.

## How this was read

Stock blobs come from HyperOS Global OS3.0.304. The source is
`hardware/qcom-caf/sm8750/display/{core,hal,intf}` (tag `DISPLAY.LA.5.0.r1-06900-pakala.0` plus Lineage changes).

Method: dynamic symbols (`llvm-nm -D -C`), strings, and a full Ghidra 12.1.4
decompile of the composer, `libsdmcore`, `libsdmdal`, `libsdmclient`,
`libsdedrm`, `libdfintf`, `libBlAlgointf`, `libclient2slpi.notifier` and the
message handler of `odm/lib64/hw/displayfeature.default.so`. Argument values at
call sites were checked in the disassembly, because Ghidra drops the fourth
argument of `handleDisplayRequest`. The mi_disp kernel driver from Xiaomi's
kernel source release was used for the sysfs side.

**Evidence** below means read directly from code or data. **Inference** means
a reasonable reading that was not confirmed on the device.

## Where the Xiaomi code is

| Binary | Xiaomi additions |
|---|---|
| `vendor.qti.hardware.display.composer-service` | `MiHwcExtensionAIDL` (registers `vendor.xiaomi.hardware.display.mihwcextension.IMiHwcExtension/default`), `executeSetLayerFlag`, MI layer flags (`MI_FULL_SCREEN_AOD_FLAG`, `MI_SUPER_WALLPAPER_AOD_FLAG`, `MI_FP_DETECT_STATE`), MAFR switch, `ReportMievent` |
| `libsdmclient.so` | `MiSDMDisplay` (Xiaomi config groups, DDIC mode, TE multiples, histogram, qsync timer), `ConcurrencyMgr::SetDisplayMafrState/MiSetPowerMode`, DisplayFeature messages in `SDMDisplay::BuildLayerStack` and `SDMColorModeStc`, `clientRGBNotifier` hook |
| `libsdmcore.so` | `MiDisplayBase` (display state to DisplayFeature, panel cell ID, Demura cloud data, fingerprint/LHBM events, pluggable fps drop, qsync timer, histogram) |
| `libsdmdal.so` | `MiHwDeviceDrm` (brightness_clone, BrightnessAlgo remap thread, MAFR timers, 30 Hz video AOD, Mi layer flags, QoS cache), fingerprint event registration |
| `libsdedrm.so` | `DRMConnector::ParseMiModeProperties/GetMiInfo`, connector properties `mi_mode_info`, `mi_mafr_info` |
| `libdfintf.so` (Xiaomi) | `DisplayFeatureIntf`: client of IDisplayFeature (AIDL, HIDL when `sys.displayfeature_hidl`) |
| `libBlAlgointf.so` (Xiaomi) | `BrightnessAlgo`: brightness to DBV remap |
| `odm/lib64/libclient2slpi.notifier.so` (Xiaomi) | `clientRGBNotifier`/`triggerCwbThread`: CWB frame trigger for `vendor.xiaomi.sensor.citsensorservice` |

`libsdm-disp-vndapis.so` (the `disp_api_*` functions) is a Qualcomm
prebuilt with no source in the CAF tree and no Xiaomi symbols; the common
vendor list already ships the stock copy. It talks to the composer over
IQService. The stock IQService command set was not compared with the source;
no Xiaomi strings point at extra commands. `displayfeature.default.so` links it
but imports nothing from it; it calls IQService directly.

## Who uses what

| Feature | Trigger (evidence) | Needed on Lineage | Decision |
|---|---|---|---|
| IMiHwcExtension AIDL (`setPanelBrightness`, `getPanelBrightness`, `setQsyncMode`, `execMiCommands`, `hwcExtensionSetFeature`, `notifyLayerFlagToHwc`, `notifyVirtualDisplayStatusToHwc`, `sendMVInfo`, `registerCallback`) | No vendor or odm binary references the interface except the composer and its NDK library. The client is MIUI SurfaceFlinger/framework | No | Skipped |
| MI layer flags, AOD flags, `MI_FP_DETECT_STATE`, `SetMiLayerFlags` | Set through IMiHwcExtension/`executeSetLayerFlag` from MIUI SF | No (and piano has no AOD or under-display fingerprint) | Skipped |
| Brightness through `brightness_clone` | Automatic: every `SetPanelBrightness` | Yes | Patch 0002 |
| DisplayFeature `NOTIFY_BRIGHTNESS_STATE` (5) | Automatic: `MiHwDeviceDrm::SetPanelBrightness` | Yes, kept DisplayFeature | Patch 0002 |
| DisplayFeature `NOTIFY_DISPLAY_STATE` (13) | Automatic: `DisplayBase::PostSetDisplayState` -> `MiDisplayBase::SetDisplayState` | Yes | Patch 0003 |
| DisplayFeature `NOTIFY_10BIT_STATE` (33), `C3D_LUT_STATE` (48) | Automatic: `SDMDisplay::BuildLayerStack`, built-in display | Yes | Patch 0004 |
| DisplayFeature `HDR_STATE` (10) | `BuildLayerStack` for HDR layers named `SurfaceView`, gated by brightness fields whose source is unknown; `SDMColorModeStc` sends 0 | Probably | Not ported (gate not understood) |
| BrightnessAlgo remap + `BrightnessT` thread | Only when `ro.vendor.display.hwc_set_backlight=1` | Not set on piano | Skipped |
| MAFR (timers, daily and panel-on limits, cloud app state, `mi_mafr_info`) | `vendor.display.support_mafr_feature`, MIUI app via IMiHwcExtension; `CheckMafrBlFpsStatus` only on `ro.product.name=haotian` | No | Skipped |
| Panel cell ID (`/sys/class/mi_display/disp-DSI-0/cell_id` vs `/mnt/vendor/persist/display/cell_id.txt`), Demura cloud data, `demuratn_user_ctrl` | Demura (OLED) | No, piano is LCD (`ro.vendor.display.type=lcd`, `*-lcd-video` panels) | Skipped |
| Fingerprint and local-HBM events (`MI_DISP_EVENT_LOCAL_HBM_VALUE`) | Under-display fingerprint | No | Skipped |
| 30 Hz video-mode AOD (`vendor.display.support_video_mode_30hz_aod`) | AOD | No | Skipped |
| TUI panel max brightness | Trusted UI | No | Skipped |
| RGB sensor CWB notifier | `vendor.display.enable_cwb_notify_sensor=1` | Not set on piano | Skipped |
| `MiSDMDisplay` config groups, DDIC mode, `ForbidFpsSwitch`, `SetTeMultiples`, qsync timer, histogram sampling | Read through `execMiCommands`/IMiHwcExtension by MIUI SF (inference from the interface; no vendor caller found) | No | Skipped |
| Built-in fps drop while a pluggable display is connected (`PlugDropFrame`) | Automatic in stock | Optional | Not ported; CAF keeps the panel rate |
| `ReportMievent` | MIUI telemetry | No | Skipped |

The `ro.vendor.display.*` properties in the stock vendor build.prop (idle fps,
smartpen VRR, touch idle, dynamic refresh rate) are not read by the composer,
SDM libraries or odm display libraries; only `bl_lut_calib.enable` is read, by
DisplayFeature. They are for MIUI SurfaceFlinger (inference).

## DisplayFeature messages

`DisplayFeatureIntf::handleDisplayRequest(display, feature, value)` calls
`IDisplayFeature::sendMessage((display << 16) | feature, value, "")`
(evidence, `libdfintf.so`). In the stock NDK library `sendMessage` is
transaction 3 (`FIRST_CALL_TRANSACTION + 2`), oneway, writing int32, int32,
String (evidence). Names come from the feature table in
`displayfeature.default.so` (`getFeatureIdName`).

| Id | Name | Stock caller | Value |
|---|---|---|---|
| 5 | `NOTIFY_BRIGHTNESS_STATE` | `MiHwDeviceDrm::SetPanelBrightness` | requested level |
| 10 | `HDR_STATE` | `SDMDisplay::BuildLayerStack`, `SDMColorModeStc::ApplyCurrentColorModeWithRenderIntent` | HDR type: 1 PQ, 2 HLG; 0 from STC |
| 13 | `NOTIFY_DISPLAY_STATE` | `MiDisplayBase::SetDisplayState` | `sdm::DisplayState` |
| 33 | `NOTIFY_10BIT_STATE` | `SDMDisplay::BuildLayerStack` | 1 while a 10-bit Display-P3 SDR layer is present |
| 48 | `C3D_LUT_STATE` | `SDMDisplay::BuildLayerStack` | 12 while an 8-bit Display-P3 SDR layer is present, else 1 |

The display index is 0 for the primary panel (evidence for `BuildLayerStack`:
`id_ != 0`; inference for the other two, which use a stock flag that is 0 on
the primary). DisplayFeature rejects an index at or above its display count.

## Brightness

Evidence from `libsdmdal.so` and the mi_disp driver:

| Step | Stock | CAF before 0002 |
|---|---|---|
| Range | `/sys/class/mi_display/disp-DSI-N/max_brightness_clone` | `/sys/class/backlight/panelN-backlight/max_brightness` |
| SDM writes | `.../disp-DSI-N/brightness_clone` | `.../panelN-backlight/brightness` |
| Kernel | `mi_dsi_panel_set_brightness_clone` clamps, stores, raises `MI_DISP_EVENT_BRIGHTNESS_CLONE`; does not touch the backlight | writes the backlight |
| Backlight | DisplayFeature (`MiBrightness`, `bl_lut_calib`, DC and thermal dimming) writes `/sys/class/backlight/panel0-backlight/brightness` and panel1 | n/a |

So on stock the calibrated curve in DisplayFeature sits between the framework
and the backlight. With CAF writing the backlight directly, that curve and
DisplayFeature's brightness-dependent effects are bypassed.

Patch 0002 uses the clone nodes when `max_brightness_clone` reads a positive
value and `brightness_clone` is writable, and keeps the CAF path if
`ro.vendor.display.hwc_set_backlight=1` (the stock switch for SDM writing the
backlight itself).

## Patches

In `device/xiaomi/piano/patches/hardware_qcom-caf_sm8750_display/`. Paths are relative to
`hardware/qcom-caf/sm8750/display`, which is not a git project, so
`apply-patches.sh` applies them there with `git apply` outside a repository.
All four apply in order to the current tree.

| Patch | Project | Change |
|---|---|---|
| 0001-add-xiaomi-displayfeature-notifier | core | `XiaomiDisplayFeatureNotify()` in libsdmutils: libbinder_ndk client for `sendMessage`, only while `init.svc.vendor.displayfeatureaidl-hal=running`; non-blocking `checkService` |
| 0002-use-xiaomi-brightness-clone | core | brightness_clone nodes as above; message 5 after each level |
| 0003-notify-displayfeature-display-state | core | message 13 from `DisplayBase::PostSetDisplayState` for built-in displays |
| 0004-notify-displayfeature-p3-content | core | messages 33 and 48 from `SDMDisplay::BuildLayerStack` |

No hal (composer) patch: nothing Xiaomi added to the composer is used on
Lineage.

## Open items

- Brightness depends on DisplayFeature running. If it stops, the backlight
  stops following the slider until it restarts. Check that `brightness_clone`
  and `panel0-backlight/brightness` both move, and that the framework range
  fits `max_brightness_clone`.
- `HDR_STATE` (10) is not ported. Stock sends it from `BuildLayerStack` for an
  HDR `SurfaceView` layer only when three brightness fields written by
  `MiDisplayBase::GetPanelBrightness` line up; those fields have no source
  equivalent, and the decompiler drops the value argument. Porting it needs a
  stock logcat of DisplayFeature during HDR playback first. Xiaomi's HDR-type
  colour-mode handling in `SDMColorModeStc` is skipped for the same reason.
- The notifier connects only to IDisplayFeature version 2 or later (stable
  AIDL `getInterfaceVersion`); `sendMessage` keeps its transaction code from
  that version on.
