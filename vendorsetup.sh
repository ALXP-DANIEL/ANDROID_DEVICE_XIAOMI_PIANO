# OrangeFox build options. OrangeFox reads these from the environment when
# build/envsetup.sh sources this file.

# AromaFM ships only a 32-bit ARM binary. SM8750 runs 64-bit code only, so it
# fails with "Exec format error" (updater error 255). Leave it out.
export FOX_DELETE_AROMAFM=1

# Maintainer name shown on the About page (and in the startup log line).
export OF_MAINTAINER=ALXP
# Version string: R12.0_1. Raise the patch number for each release.
export FOX_MAINTAINER_PATCH_VERSION=1
