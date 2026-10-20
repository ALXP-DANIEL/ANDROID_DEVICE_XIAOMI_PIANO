# AOSP PencilEngine endpoint

Android 17 builds include `PianoPencilEngine`, a platform-signed app with the
explicit stock component `com.xiaomi.touchservice/.pencilengine.PencilEngineManagerService`.
The original Xiaomi TouchService APK is not included or impersonated by its signature.

Implemented: function 0 screen colour picker, the four stock Binder transactions,
and `onChange(0, sRGB colour-space ID, sampled ARGB)` callback. The overlay presents
a magnified 7 by 7 pixel grid and colour ring. It uses Android 17 ScreenCapture,
converts the screenshot to sRGB, and releases all captured pixels on teardown.
No screenshot is saved. Secure/protected capture fails rather than bypassing it.

Admission requires a platform-signed caller owning `com.miui.notes`, in the
foreground on the default display for the unlocked current user. Screen-off,
user switching, configuration changes, client death, Back/Escape, multitouch,
capture failure and timeout cancel without inventing a colour result. A stale
timer cannot cancel a later pick by the same registered listener. Client callback
delivery occurs after window/bitmap cleanup and off the UI thread.

Functions 1 (posture) and 2 (touch film) return unsupported. Their MIUI per-package
input dispatch and touch HAL ownership still need integration; merely acknowledging
these requests would not reproduce stock behaviour. Native brush/estimation/
recognition algorithms and licensing are separate from this Binder endpoint.

Evidence: decoded .44 TouchService `PencilEngineManagerService`, `ColorPickController`,
`ColorPickerWindow` and `FloatColorPicker` under the retained accessories analysis.
This is a reconstruction with AOSP controls and deliberate caller checks, not a
claim of pixel-identical UI or confirmed hardware/runtime parity.

Validation: production Java compiles against the retained Android 17 framework
jar; the Binder fixtures pass 26 checks. A full Soong app build, signature/permission
checks, real secure-window capture rejection, orientation/lifecycle exercises and
Notes callback testing remain required before claiming the feature works on-device.
