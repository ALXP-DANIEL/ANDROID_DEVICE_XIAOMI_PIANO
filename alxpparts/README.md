# ALXPParts (ALXP Features)

AirPods audio switching between the Pad and your Apple devices, as its own
app with its own **ALXP Features** entry in Evolution X settings, next to
Xiaomi features. GPL-3.0-or-later; trimmed from LibrePods (see UPSTREAM),
with the original copyright notices kept in the source.

Only the switching part of LibrePods is kept: the AACP control channel,
ownership messages, "moved to" requests and new device introductions. The
LibrePods app, its UI libraries, billing, Xposed, battery, noise control,
EQ and head tracking are not included.

## Build

Built from source with the ROM by Soong (`Android.bp`), like PianoParts.
It is installed only when the product sets `WITH_ALXP_PARTS := true`;
`sync-device.sh --alxpparts` does that and applies the Bluetooth patch in
`patches/packages_modules_Bluetooth`.

## Use

Settings > ALXP Features > turn on **Automatic switching**. That enables
the service, turns on the Apple Bluetooth identity and restarts Bluetooth.
Pair the AirPods with the Pad: playing media or taking a call on the Pad
moves them to it, and playing on an iPhone or Mac moves them back. If the
AirPods dropped the Pad, it reconnects them first.

Turning it off stops the service, clears the identity and restarts
Bluetooth.

## Tested

Pad and Mac switching both ways with AirPods Max. AirPods Max drop the
Pad when the Mac takes them, which the reconnect handles.
