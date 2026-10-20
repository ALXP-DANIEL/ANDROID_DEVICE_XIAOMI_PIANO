# Xiaomi Notes integration (Android 17, build default)

This is a **build-default integration of the original signed APK pair**, not a
claim that every stock Notes feature runs on AOSP. Android 17 piano products
require the separately staged `vendor/xiaomi/piano-notes` inputs and fail
closed if either APK or the required Soong extension is missing or altered.

## Stock inputs

From the preserved China HyperOS `OS4.0.0.44.XPYCNXM` dump, keep these
two original, signed APKs byte-for-byte:

| Stock relative path | Package | SHA-256 |
| --- | --- | --- |
| `product/data-app/MIUINotes/MIUINotes.apk` | `com.miui.notes` | `81510beeda9b87c3120c8835dbaa3faf9fed780aec90b9317904ba897f114e8f` |
| `system_ext/priv-app/RtMiCloudSDK/RtMiCloudSDK.apk` | `com.xiaomi.micloud.sdk` | `e45a698ec842bdc27bc62041141638d15ad20b599d17c43001fcc16b7d9336fd` |

Check both hashes before packaging. They share a Xiaomi signing certificate.
Notes has minimum API 29, but **RtMiCloudSDK has minimum API 37** and provides
the mandatory `micloud-sdk` dynamic shared *application* library declared by
the Notes manifest. Check the **target ROM's** `ro.build.version.sdk >= 37`;
stock HyperOS reporting 37 does not prove the custom ROM reports 37.
The EvoX image built on 2026-10-14 was separately reported to have API 37;
verify again for every tested build. Do not treat the Canvas/Etar
`sdk_version: "36.1"` build patches as a runtime SDK property.

## Required Android 17 build inputs

Prepare a **separate local** `vendor/xiaomi/piano-notes` directory with its own
`Android.bp`, both hash-verified APKs and a `notes.mk` containing:

```make
PRODUCT_PACKAGES += \
    MIUINotes \
    RtMiCloudSDK
```

Its Soong imports must retain the exact APK bytes (`android_app_import` with
`presigned: true`, `preprocessed: true` and
`dex_preopt: { enabled: false }`). Put Notes under `product/app` via
`product_specific: true`, and the SDK under `system_ext/priv-app` via
`system_ext_specific: true` and `privileged: true`, preserving its original
partition and class. Notes should declare `required: ["RtMiCloudSDK"]`
for installation ordering; that alone does *not* resolve its manifest
`<uses-library android:name="micloud-sdk">` dependency.

`device.mk` always inherits `vendor/xiaomi/piano-notes/notes.mk`
and exposes the external Soong namespace. There is **no opt-in flag**;
missing `notes.mk` or verified APKs is deliberately a build error. Do not add the APKs to
`proprietary-files.txt`: the existing extraction generator automatically
enables generated app modules in the default `piano-vendor.mk`.

## Soong shared-APK bridge (experimental)

The stock SDK exposes its library from an **APK manifest `<library>` tag**,
not a system Java JAR. Standard Soong `android_app_import` does not publish
`JavaInfoProvider` for such an imported APK, but Notes requires a genuine
`micloud-sdk` class-loader dependency. The separate local
`vendor/xiaomi/piano-notes` package carries a narrowly scoped opt-in Soong
provider patch publishing the **real original SDK APK** and installed path.
It supplies no fake JAR or replacement manifest.

The public Android 17 `android-17.0.0_r1` Go tests passed for this extension
on 2026-10-15 after fixing nested conditional compatibility class-loader
contexts; the target ROM builder branch, APK verification actions, installed
system image, and `micloud-sdk` registration on boot remain unverified.
The device patch script invokes the local `apply-soong.py` helper on every
Android 17 build without any flag. It refuses unknown/partially patched Soong
revisions without resetting or cleaning checkout contents. See the vendor
package README for exact tests.

**Do not** set `enforce_uses_libs: false`, relax global dexpreopt checks,
patch either signed manifest, or create a fake Java library. The proof still
requires building the opt-in APK pair and checking actual runtime registration.

## Runtime boundaries

Even with successful packaging and library registration, Xiaomi framework
classes used for backup, cloud, themes, accounts and freeform features remain
unresolved. The Notes app requests permissions that its original signature
does not receive from a Lineage/EvoX platform image. Keep existing SELinux
policy and privileged-permission allowlists unchanged. Notes must be tested
separately for installation, basic launch, local save, pen strokes, native
recognition, denied permissions and cloud behavior before any claim of parity.

The supporting offline audit is
`RND/analysis/accessories-44-20261014/stock-ui/notes-import.md` in the
working research tree. Nothing here changes the default ROM image.
