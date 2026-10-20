# Xiaomi Parts for Xiaomi Pad 8 Pro (piano)

The settings app for the tablet's Xiaomi features, shared by every ROM and
by Android 16 and 17.

Goes in `device/xiaomi/piano-parts`. The sync script on the
[main branch](../../tree/main) checks it out and runs
`select-rom.py lineage` or `evox`, which places the Xiaomi settings entry in
the right spot for that ROM.

## Features

- Xiaomi Pen: pairing, battery, pressure, button shortcuts, color picker
- Xiaomi keyboard: pairing and shortcuts
- Tap to wake (off, single tap, double tap)
- Reading mode, including rhythmic (time of day) mode
- Display and refresh rate settings
- PencilEngine service used by Xiaomi Notes for pen drawing modes

## Tests

```sh
bash tests/run-mipp-tests.sh
python3 tests/check-devauth-policy.py
```

These check the code on a computer. Pen and keyboard still need testing on
the tablet.
