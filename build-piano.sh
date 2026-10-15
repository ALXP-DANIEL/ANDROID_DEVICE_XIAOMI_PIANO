#!/usr/bin/env bash
# Build a selected piano ROM using the Git-manifest-managed source trees.
# The builder launcher tracks this script on `main`, not manual project Git refs.
set -eo pipefail

PIANO_GIT_URL=${PIANO_GIT_URL:-https://github.com/ALXP-DANIEL/android_device_xiaomi_piano.git}
PIANO_TOOLS_DIR=${PIANO_TOOLS_DIR:-$HOME/android/piano-shared/piano-tools}

usage() {
    cat <<'EOF'
Usage: bash build-piano.sh [--sync-only]

Automatically git-pulls the latest main build/sync scripts, refreshes all nine
ROM trees from the generated repo manifest, stages the selected Android version,
then builds an OTA unless --sync-only is supplied.

Defaults: PIANO_ANDROID=17, PIANO_ROM=evox, BUILD_VARIANT=userdebug.
Override ANDROID_ROOT, PIANO_ANDROID, PIANO_ROM, BUILD_TARGET, BUILD_JOBS,
PIANO_AVB_KEY_PATH, PIANO_BUILD_DIR, PIANO_MENU_DIR as needed.
EOF
}

SYNC_ONLY=false
while (($#)); do
    case "$1" in
        --sync-only) SYNC_ONLY=true; shift ;;
        -h|--help) usage; exit 0 ;;
        *) usage >&2; echo "Unsupported option: $1" >&2; exit 2 ;;
    esac
done

# The user's builder script itself can be an older standalone copy. Pull a
# normal Git main checkout first, then exec its latest copy. Do not overwrite
# that checkout if somebody has made local changes to its scripts.
if [[ "${PIANO_TOOLS_READY:-0}" != 1 ]]; then
    if [[ -e "$PIANO_TOOLS_DIR" ]]; then
        [[ -d "$PIANO_TOOLS_DIR/.git" ]] || { echo "Not a Git tools checkout: $PIANO_TOOLS_DIR" >&2; exit 1; }
        [[ "$(git -C "$PIANO_TOOLS_DIR" branch --show-current)" == main ]] || {
            echo "Tool checkout is not on main: $PIANO_TOOLS_DIR" >&2; exit 1;
        }
        [[ -z "$(git -C "$PIANO_TOOLS_DIR" status --porcelain)" ]] || {
            echo "Tool checkout has local changes; not pulling over them." >&2; exit 1;
        }
        git -C "$PIANO_TOOLS_DIR" pull --ff-only origin main
    else
        mkdir -p "$(dirname "$PIANO_TOOLS_DIR")"
        git clone --depth 1 --single-branch --branch main "$PIANO_GIT_URL" "$PIANO_TOOLS_DIR"
    fi
    if [[ "$SYNC_ONLY" == true ]]; then
        PIANO_TOOLS_READY=1 exec bash "$PIANO_TOOLS_DIR/build-piano.sh" --sync-only
    else
        PIANO_TOOLS_READY=1 exec bash "$PIANO_TOOLS_DIR/build-piano.sh"
    fi
fi

PIANO_ANDROID=${PIANO_ANDROID:-17}
PIANO_ROM=${PIANO_ROM:-evox}
BUILD_VARIANT=${BUILD_VARIANT:-userdebug}
BUILD_JOBS=${BUILD_JOBS:-2}
PIANO_AVB_KEY_PATH=${PIANO_AVB_KEY_PATH:-piano-local-keys/lineage-23.2-avb.pem}

case "$PIANO_ANDROID:$PIANO_ROM" in
    17:evox) default_root="$HOME/android/evox-17"; default_glob='EvolutionX-*-piano*.zip'; default_target=evolution ;;
    17:lineage) default_root="$HOME/android/lineage-24.0"; default_glob='lineage-*-piano.zip'; default_target=bacon ;;
    16:lineage) default_root="$HOME/android/lineage-23.2"; default_glob='lineage-*-piano.zip'; default_target=bacon ;;
    *) echo "Unsupported ROM/release: $PIANO_ROM / Android $PIANO_ANDROID" >&2; exit 2 ;;
esac
ANDROID_ROOT=${ANDROID_ROOT:-$default_root}
OTA_GLOB=${OTA_GLOB:-$default_glob}
BUILD_TARGET=${BUILD_TARGET:-$default_target}
PIANO_BUILD_DIR=${PIANO_BUILD_DIR:-$ANDROID_ROOT/out/piano-builds}
PIANO_BUILD_LOG=${PIANO_BUILD_LOG:-/tmp/piano-build.log}

cd "$ANDROID_ROOT"
[[ -d .repo && -d build ]] || { echo "Not an Android checkout: $ANDROID_ROOT" >&2; exit 1; }

exec 9>/tmp/piano-full-build.lock
flock -n 9 || { echo 'Another piano sync/build owns the lock.' >&2; exit 1; }
if pgrep -x soong_ui >/dev/null || pgrep -x ninja >/dev/null || pgrep -x delta_generator >/dev/null; then
    echo 'Another Android build is active; refusing to sync its sources.' >&2
    exit 1
fi

if [[ "$SYNC_ONLY" != true ]]; then
    [[ -s "$PIANO_AVB_KEY_PATH" ]] || {
        echo "Existing external AVB key missing: $PIANO_AVB_KEY_PATH" >&2; exit 1;
    }
fi

exec > >(tee "$PIANO_BUILD_LOG") 2>&1
kernel_backup=""
restore_kernel() {
    if [[ -n "$kernel_backup" && -f "$kernel_backup" ]]; then
        mv -f "$kernel_backup" device/xiaomi/piano-kernel/kernel
    fi
}
trap 'result=$?; restore_kernel; echo "PIANO_BUILD_EXIT=$result"' EXIT

echo "Updating Android $PIANO_ANDROID $PIANO_ROM sources from Git manifest..."
# repo sync advances all nine detached Git checkouts to their manifest branch
# tips; plain git pull would fail on their detached HEADs. The sync helper also
# validates clean tracked files and stages all Android-specific generated files.
bash "$PIANO_TOOLS_DIR/sync-device.sh" --android "$PIANO_ANDROID" --rom "$PIANO_ROM" --yes

if [[ "$SYNC_ONLY" == true ]]; then
    echo 'Piano Git manifest and all nine ROM trees are up to date; no build requested.'
    exit 0
fi

echo 'Applying ROM framework compatibility patches...'
bash device/xiaomi/piano/patches/apply-patches.sh

export PIANO_AVB_KEY_PATH
# Advisory limits: some Soong invocations use env -i and do not inherit these.
export SOONG_GOMEMLIMIT=${SOONG_GOMEMLIMIT:-14GiB}
export GOMAXPROCS=${GOMAXPROCS:-4}

# envsetup and breakfast may use commands that return nonzero internally.
set +eo pipefail
source build/envsetup.sh
breakfast piano "$BUILD_VARIANT"
breakfast_result=$?
set -eo pipefail
[[ "$breakfast_result" == 0 ]] || { echo 'ROM breakfast failed.' >&2; exit 1; }

marker=$(mktemp /tmp/piano-build-start.XXXXXX)

# Optional existing UEFI boot selector. Break the generated kernel hardlink
# before packing, so writing a temporary kernel cannot modify the pinned input
# under versions/android17 or versions/android16.
if [[ -n "${PIANO_MENU_DIR:-}" ]]; then
    kernel=device/xiaomi/piano-kernel/kernel
    kernel_backup="$kernel.plain"
    [[ ! -e "$kernel_backup" ]] || { echo "Existing kernel backup: $kernel_backup" >&2; exit 1; }
    cp -p "$kernel" "$kernel_backup"
    cp -p "$kernel" "$kernel.temporary"
    mv -f "$kernel.temporary" "$kernel"
    extras=()
    [[ ! -f "$PIANO_MENU_DIR/Mu-piano.img" ]] || extras+=(--uefi "$PIANO_MENU_DIR/Mu-piano.img")
    if [[ -f "$PIANO_MENU_DIR/linux/Image" ]]; then
        extras+=(--linux "$PIANO_MENU_DIR/linux/initramfs.cpio.gz"
                 --linux-image "$PIANO_MENU_DIR/linux/Image"
                 --linux-dtb "$PIANO_MENU_DIR/linux/menu.dtb")
    fi
    if [[ "$PIANO_ROM" == evox ]]; then
        label="Evolution X $(get_build_var EVO_VERSION_BASE 2>/dev/null)"
    else
        label="LineageOS $(get_build_var PRODUCT_VERSION_MAJOR).$(get_build_var PRODUCT_VERSION_MINOR)"
    fi
    python3 "$PIANO_MENU_DIR/pack.py" "$kernel_backup" "$PIANO_MENU_DIR/selector.bin" \
        "${extras[@]}" --android-label "$label - Android $PIANO_ANDROID" \
        --kernel-only -o "$kernel"
fi

echo "Building $PIANO_ROM Android $PIANO_ANDROID ($BUILD_VARIANT), target $BUILD_TARGET, jobs $BUILD_JOBS"
m -j"$BUILD_JOBS" "$BUILD_TARGET"

# Export only a ZIP created after this build began, not an earlier stale OTA.
otas=()
declare -A seen=()
while IFS=' ' read -r -d '' inode ota; do
    [[ -z "${seen[$inode]:-}" ]] || continue
    seen[$inode]=1
    otas+=("$ota")
done < <(find out/target/product/piano -maxdepth 1 -type f -name "$OTA_GLOB" \
    -newer "$marker" -printf '%i %p\0' | sort -z -r -k2)
[[ "${#otas[@]}" == 1 ]] || { echo "Expected one newly built OTA; found ${#otas[@]}." >&2; exit 1; }
artifact_dir="$PIANO_BUILD_DIR/$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -p "$artifact_dir"
destination="$artifact_dir/$(basename "${otas[0]}")"
[[ ! -e "$destination" ]] || { echo "Destination already exists: $destination" >&2; exit 1; }
cp "${otas[0]}" "$destination"
cmp "${otas[0]}" "$destination"
(cd "$artifact_dir" && sha256sum "$(basename "$destination")" > SHA256SUMS)
rm -f "$marker"
echo "OTA saved: $destination"
