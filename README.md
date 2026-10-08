# MiuiCamera for Xiaomi Pad 8 Pro

MiuiCamera package for the Xiaomi Pad 8 Pro.

Path:

```text
vendor/xiaomi/piano-miuicamera
```

HyperOS 4 China OS4.0.0.44.XPYCNXM MiuiCamera 6.6.000570.6 for Android 17.

Build and device validation are pending.

See the [main branch](../../tree/main) for the other shared trees.

## Rotation

The stock tablet app handles rotation itself but keeps its preview stream
from the first orientation. `tools/patch-camera-rotation.py` edits typed
values in the binary manifest of the stock China OS4.0.0.44 APK (SHA256
`581490afa8d2102a0b976e69ffbf571c440e10d14a696753f875eff363b7e00a`) and
copies every other entry unchanged: the application becomes resizable,
portrait activities use fullSensor, and the camera activities are recreated
on orientation and screen-size changes.
