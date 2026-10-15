# P81C firmware host checks

Run from the device/xiaomi/piano-parts checkout:

```sh
rtk proxy javac -d /tmp/piano-suota-tests src/org/lineageos/pianoparts/stylus/firmware/FirmwareImage.java src/org/lineageos/pianoparts/stylus/firmware/SuotaV1.java tests/firmware/SuotaV1Test.java
rtk proxy java -cp /tmp/piano-suota-tests org.lineageos.pianoparts.stylus.firmware.SuotaV1Test
```

These checks exercise exact model/profile/digest guards, XOR checksum, transfer chunk/block sizing, notification-before-write callback ordering, final revision verification, error/cancel/timeout. They do not flash firmware or validate Android Bluetooth behavior.

Integration: FirmwareImage.validate requires independently trusted model/profile/revision/full SHA-256 metadata; user-provided metadata alone does not establish publisher authenticity. SuotaGatt owns no connection: suspend normal pen traffic and forward callbacks from one exclusive, connected, identity-checked P81C20609 session. prepare reads patch size, negotiates MTU and subscribes to SUOTA status; isPrepared indicates readiness. Only an explicit subsequent start writes firmware. Reconnect and read revision after VERIFY_REVISION; only verifyRevision(target) can report COMPLETE. Abort on disconnect/error/timeout and restore the normal pen controller.

No verified matching firmware payload has been obtained. The Settings flash entry point requires an independently verified ROM-bundled update; without that package it stays disabled. There is no automatic or arbitrary app-supplied firmware download. PID12929, other pen generations, legacy DFU and SUOTA V2 remain unsupported here.
# MIPP V2 protocol core

Run `bash tests/run-mipp-v2-tests.sh` with JDK 21. Exercises frame/CRC vectors,
all seven negotiated block sizes, padding/final marker, packet bounds,
query/start/requested-block/end transitions, early callbacks, bounded retries,
rejection, cancellation and revision verification. No GATT or firmware writes.

`MippV2Protocol` and `MippV2Session` are not wired into the updater. Its existing
trusted-ROM image gate is unchanged. Production integration still needs a
verified model-bound firmware image, MTU-aware transport, timeout/reconnect
ownership and hardware validation. Byte-level encoder checks against retained
stock encoder sources are recorded in the workspace audit; those sources are
not copied into this repository.
