# Optional Xiaomi Notes experiment (Android 17)

This is an **opt-in integration hook**, not a ready-to-ship Xiaomi Notes port.
The standard product does not include Xiaomi Notes or RtMiCloudSDK. The hook
becomes active only with `PIANO_EXPERIMENTAL_XIAOMI_NOTES=true` **and** a
separate local `vendor/xiaomi/piano-notes` package. Do not enable the flag
until the Soong issue below is resolved and validated.

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

## Opt-in build boundary

Once the missing Soong dependency representation has been solved, prepare a
**separate local** `vendor/xiaomi/piano-notes` directory with its own
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

Then set `PIANO_EXPERIMENTAL_XIAOMI_NOTES=true` during product evaluation.
`device.mk` explicitly inherits `vendor/xiaomi/piano-notes/notes.mk`
and exposes that external Soong namespace only with this flag. An enabled
flag without `notes.mk` is deliberately an error. Do not add the APKs to
`proprietary-files.txt`: the existing extraction generator automatically
enables generated app modules in the default `piano-vendor.mk`.

## Current Soong blocker

The stock SDK exposes its library from an **APK manifest `<library>` tag**,
not a system Java JAR. AOSP Soong's `android_app_import` performs
`<uses-library>` verification; its class-loader dependency handling expects
a Java library with `JavaInfoProvider`. A second `android_app_import` for
`RtMiCloudSDK` has not been shown to satisfy that model. A
`PRODUCT_PACKAGES` dependency or renaming the imported SDK module to
`micloud-sdk` does not establish correct class-loader verification.

**Do not** set `enforce_uses_libs: false`, relax global dexpreopt checks,
patch either signed manifest, or create a fake Java library to make the build
pass. Before supplying the final `Android.bp`, test a genuine representation
of this dynamic shared application library against the exact ROM's Soong and
verify the Notes manifest uses-library check completes without relaxation.
No Soong build has been run for this experimental path.

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
