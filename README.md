# Xiaomi Pad 8 Pro PianoParts (Android 16 and 17)

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
checkout has been synced. PianoParts and PianoPencilEngine are shared by
Android 16 (SDK 36) and Android 17 (SDK 37). The capture bridge handles the
older Android 16 WindowManager capture ABI and the newer asynchronous API.
Secure/protected content is never requested by the legacy path; newer APIs
reject it explicitly. Unsupported platform APIs cancel the picker.

PencilEngine and the pen Java/Kotlin sources compile against Android 16 and
17 framework jars. Resource/Settings fixtures are used for the Parts host
compile. These checks do not prove a complete Android 16 ROM or physical pen
operation. Native laser still requires matching ROM framework patches and
its current-boot readiness marker; installing this app alone cannot enable it.

Notes PencilEngine posture requests share mode 20036 with the optional drawing
app setting through one Parts controller. The service verifies the Notes UID,
active user, current boot and a fresh controller heartbeat. Requests expire
after five seconds without renewal. Binder cleanup, screen-off and user switch
remove active effects while Parts is running. Readiness is published only after
a HAL transaction succeeds; this is not proof of physical controller behavior.
If Parts itself dies after enabling a hardware mode, clearing that mode waits
for its persistent process to restart; a lease cannot write the HAL from a dead
process. This does not implement Xiaomi's native recognition algorithms.

Run the host checks from this tree's root using `bash tests/run-mipp-tests.sh`
and `python3 tests/check-devauth-policy.py`. The full Android build and
tablet hardware integration must be checked separately.
