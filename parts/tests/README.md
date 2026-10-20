# Piano pen host checks

Run `bash parts/tests/run-mipp-tests.sh` from the device tree. Requires a JDK
and Kotlin compiler; set `KOTLINC` to its executable if it is not on PATH.

The host checks use packet vectors recovered from HyperOS .44 and the piano
BluetoothExtension overlay (`TIME_STAMP=1745566508`). They exercise the real
MIPP session with controlled replies: dual IDs, delayed initialization until
ACK, malformed reply rejection, panel failure, hall changes during setup,
active-state setup, frequency changes and stale replies after disconnect.
The host Log stub is outside `src` and is not included in PianoParts.

These checks do not emulate Bluetooth, Binder, SELinux, firmware or pen input.
They are not a substitute for a full Android build or hardware testing.
