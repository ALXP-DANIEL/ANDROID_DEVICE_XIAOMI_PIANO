# pianoPkg

Mu-Silicium device package for the Xiaomi Pad 8 Pro (SM8750, "Pakala").

Based on Mu-Silicium's OnePlus 13 (`dodgePkg`) package. The memory and
configuration maps come from Project SunUEFI, under their BSD-2-Clause-Patent
headers.

## Build

```sh
tools/setup-deps.sh
docker build -f docker/Dockerfile -t piano-uefi-build .
docker run --rm -v "$PWD:/p" -w /p/deps/Mu-Silicium piano-uefi-build \
	python3 build_uefi.py -d piano -r RELEASE
```

`setup-deps.sh` clones Mu-Silicium at the tested commit, applies
`patches/mu-basecore.patch` and links in pianoPkg and the Qualcomm drivers.

`Binaries/piano` holds the device's own Qualcomm drivers, already extracted
and patched; they are proprietary, included as in Project Silicium's
Device-Binaries. To redo them from the stock firmware's `uefi.img`:

```sh
python3 tools/extract-qcom-dxe.py <uefi.img> deps/Mu-Silicium/Binaries/piano
bash tools/patch-qcom-dxe.sh       # needs Binaries/dodge for the reference patches
```

`pianoPkg/Firmware` holds the stock touch controller firmware (BOE and CSOT
panels), also proprietary.
