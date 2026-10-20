# Xiaomi Notes for Xiaomi Pad 8 Pro (piano)

Stock Xiaomi Notes 3.2.2.8 from HyperOS 4 China, made to work without a
Xiaomi account. Shared by every ROM and by Android 16 and 17.

Goes in `vendor/xiaomi/piano-notes`. The sync script on the
[main branch](../../tree/main) checks it out and rebuilds the APK from the
stock pieces in `parts/`.

## What changes from stock

- Works offline: no Xiaomi account, cloud sync or internet permission.
- AI features are hidden, since they need Xiaomi's servers.
- A small extra library replaces Xiaomi framework parts AOSP does not have.
- Signed with the ROM's normal app key, so it updates like any app.

Handwriting, drawing and local notes stay unchanged.

## Not available

Xiaomi account login, cloud sync and trash, AI text and AI recording.

## Checks

```sh
python3 selfcheck.py                 # rebuild from parts and verify
python3 stage-originals.py --check   # verify the staged APK
python3 tests/offline_manifest_test.py
```

A stock-signed Notes already on the tablet cannot update to this one
without clearing its data.
