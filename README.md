# MiuiCamera for Xiaomi Pad 8 Pro

MiuiCamera package for the Xiaomi Pad 8 Pro.

Path:

```text
vendor/xiaomi/piano-miuicamera
```

Reusable with compatible Android 16 ROMs.

See the [main branch](../../tree/main) for the other shared trees.

## Rotation

The stock tablet app handles rotation itself but keeps its preview stream
from the first orientation. `tools/patch-camera-rotation.py` edits typed
values in the binary manifest of the stock Global 303 APK (SHA256
`810459635b5dff72495566356f65bb551ce03daf75820660f082425bb78e19c5`) and
copies every other entry unchanged: the application becomes resizable,
portrait activities use fullSensor, and the camera activities are recreated
on orientation and screen-size changes.
