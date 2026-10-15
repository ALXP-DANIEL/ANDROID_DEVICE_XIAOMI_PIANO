# Xiaomi Pad 8 Pro PianoParts (Android 17)

This shared tree is checked out at `device/xiaomi/piano-parts` from the
`pianopart` branch of `ALXP-DANIEL/android_device_xiaomi_piano`.
LineageOS and Evolution X device trees inherit
`pianoparts.mk`. The app keeps its original `PianoParts` module name,
`org.lineageos.pianoparts` identity, platform signing, privileges, and
`system_ext` installation path.

Source, resources, firmware, and tests are shared. The sync script runs
`python3 select-rom.py lineage` or `evox`, generating a single ignored
Android.bp with the corresponding manifest. The Lineage variant places
Xiaomi Settings near the top of connectivity; EvoX keeps its Evolution group.

The device trees continue to own hardware policies and Lineage SDK
key-handler overlays. No extra opt-in is required after the `pianopart`
checkout has been synced. This branch tracks the newest Android 17 Parts
code for all ROMs; Android 16 compilation requires separate validation.

Run the host checks from this tree's root using `bash tests/run-mipp-tests.sh`
and `python3 tests/check-devauth-policy.py`. The full Android build and
tablet hardware integration must be checked separately.
