# Piano pen host checks

Run `bash tests/run-mipp-tests.sh` from the PianoParts tree. Requires a JDK
and Kotlin compiler; set `KOTLINC` to its executable if it is not on PATH.

The host checks use packet vectors recovered from HyperOS .44 and the piano
BluetoothExtension overlay (`TIME_STAMP=1745566508`). They exercise the real
MIPP session with controlled replies: dual IDs, delayed initialization until
ACK, malformed reply rejection, panel failure, hall changes during setup,
active-state setup, frequency changes and stale replies after disconnect.
The host Log stub is outside `src` and is not included in PianoParts.

These checks do not emulate Bluetooth, Binder, SELinux, firmware or pen input.
They are not a substitute for a full Android build or hardware testing.

Run `python3 tests/run-capture-compat-tests.py` with JDK 21 (`JAVA_HOME`) to
check the production capture bridge against Android 16's original/relocated
capture types and the newer asynchronous API. Covers secure rejection, error
status, request failure and buffer release when delivery is rejected. These
ABI fixtures do not exercise SurfaceFlinger, WindowManager permissions or UI.

Run `bash tests/run-pencilengine-platform-compile.sh android16-framework.jar
android17-framework.jar` with full platform jars to compile the actual
PencilEngine service and Binder transport against both releases. Public SDK
stubs omit required privileged APIs. This is a compilation check, not a ROM
resource-linking, permission or device-behavior test.
