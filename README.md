# Xiaomi Notes shared ROM package

The `notes` branch is synced to `vendor/xiaomi/piano-notes` for both
LineageOS and Evolution X on Android 16 or Android 17.

Its source input is Xiaomi Notes 3.2.2.8 from stock HyperOS 4 China
OS4.0.0.44.XPYCNXM, preserved as three SHA-256-pinned pieces under `parts/`.
The sync script automatically invokes:

```sh
python3 vendor/xiaomi/piano-notes/stage-originals.py --from-parts
```

This verifies the exact original APK, removes **only** the required
`micloud-sdk` binary AndroidManifest uses-library element, discards the
invalidated original v1 signing files, and writes an ignored, repackaged
`prebuilt/MIUINotes.apk`. The build then appends an app-local compatibility DEX
and pinned piano hardware metadata before `android_app_import` signs the result
with the ROM platform key. No MiCloud SDK is included. The package identity
remains `com.miui.notes` and the original minSdk remains 29.

This is an APK repackage, not a full source recompilation. The DEX code may
still reference Xiaomi cloud classes: removing the manifest dependency
does not automatically replace or eliminate those code paths. Android 16/17
build acceptance and runtime Notes features must be tested independently.
It also changes the app signature, so a stock-signed existing installation
cannot be upgraded in place without a compatible signature/data migration.

The compatibility DEX supplies `miui.os.Build` fields using actual Android
properties and a read-only `miui.util.FeatureParser` for the pinned `.44`
`piano.xml`. Original Notes DEX and resources remain byte-identical. It does
not claim the MIUI framework, cloud services, or licensed recognition exists.
Android 17 diagnostic APK testing reached the tablet list, text editor and
drawing toolbar after fixing the two observed missing-class crashes. Real pen
pressure/gestures and ROM-built Soong integration still require validation.

Run `python3 selfcheck.py` to verify reconstruction, manifest edits,
output integrity, and corrupted-segment rejection in a temporary directory.
Use `python3 stage-originals.py --check` for staged output validation.

REA comparison found that both HyperOS 4 China `.44` Notes 3.2.2.8 and
HyperOS 3 Global `.303` Notes 2.4.1.0 declare mandatory `micloud-sdk`.
The exact Global `.304` Notes APK remains unverified because its available
full OTA is incomplete and the incremental OTA changes the product partition.
