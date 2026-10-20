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

The compatibility DEX supplies an app-local signed-out `miui.accounts.ExtraAccountManager`
lookup for the offline port. It always returns no Xiaomi account; it does not
provide authentication or tokens. The handwriting editor calls this from the
local Notes provider, so leaving the class missing crashes page initialization.
The build also removes direct INTERNET permission and identified account/cloud
receivers, sync, cloud-settings backup and external AI service/provider components.
Five AI capability gates are false. AI navigation is hidden only at its specific
UI call sites, without pretending the tablet is low-end. The editor Ask AI menu item and
center/overflow toolbar action are removed separately. AI/cloud settings are
hidden after the pinned preference initialization completes. Native drawing and
local storage are preserved. Xiaomi account login/sync, cloud trash, AI text,
AI recording/LLM tools are unavailable in this offline port.

This is not a guarantee that every obsolete class or third-party SDK has been
physically removed. The port disables their reviewed entry points and direct
network access; embedded cloud/AI code remains in the APK. Physical handwriting,
all remaining menus and Android 16/17 ROM builds still need runtime validation.

It also supplies `miui.os.Build` fields using actual Android
properties and a read-only `miui.util.FeatureParser` for the pinned `.44`
`piano.xml`. Original resources and native libraries remain byte-identical. Reviewed AI capability
gates and five UI methods are rewritten by the Android smali DEX writer;
non-target method instruction graphs, references, exception handlers and debug
locations are checked after readback. String/goto widening and payload alignment
are normalized for that comparison, not arbitrary code changes. It does
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

## Offline policy validation

`python3 tests/offline_manifest_test.py` checks pinned component removal while
retaining the local provider and launcher. `tests/OfflinePolicyTest.java` checks
signed-out account lookup, hidden cloud/AI preferences and preserved local
preferences against minimal host fixtures.

`offline_dex_test.py` requires `NOTES_STOCK_APK` and `DEXLIB2_CLASSPATH` for
artifact tests. Host Ghidra dexlib2 2.5.2 uses `DEXLIB2_LEGACY_NAMESPACE=1`;
that option only adapts imports in a temporary test copy. Production compiles
`OfflineDex.java` against AOSP `smali-dexlib2`, without vendoring a tool JAR.
`selfcheck-compat.py <staged APK> <compat DEX JAR>` also needs that host test
classpath, or run the production genrule with its built host tool. It verifies
resources/native libraries, deterministic output and rejected duplicate payloads.

Source changes and host checks do not prove the blank handwriting editor has
recovered on the tablet. A same-key APK update and opening, writing, saving and
reopening a local handwritten note remain required. Do not clear existing Notes
data or claim physical pen pressure/gestures from synthetic input tests.

The homepage paperclip (`btnAdd`) is hidden in all three pinned list layouts.
The ordinary editor attachment and handwriting tools stay intact. Stock account
checks remain unchanged; this port does not expose homepage cloud import.

The DEX writer uses a local `ReplayableDexPool` adapter: AOSP google-smali
returns a single-use parameter-name iterable, while its debug writer reads that
iterable repeatedly. Materializing those names prevents malformed debug headers.
All method instruction graphs, handlers and debug positions are still verified
after serialization. Host regression runs must include the builder smali JAR;
the older Ghidra library alone does not exercise the AOSP iterator regression.
