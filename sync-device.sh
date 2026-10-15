#!/usr/bin/env bash

set -eo pipefail

# ============================================================
# Xiaomi Pad 8 Pro (piano) Device Tree Sync
# ============================================================
#
# ROM:
#   - LineageOS
#   - Evolution X
#
# Recovery:
#   - TWRP
#   - OrangeFox
#
# This script syncs the device, hardware, kernel and Xiaomi app trees.
# It does NOT download the ROM or recovery source itself.
#
# Run this script from the root of your Android source checkout.
# ============================================================

GITHUB_USER="ALXP-DANIEL"
REPO_NAME="android_device_xiaomi_piano"
REPO_URL="https://github.com/${GITHUB_USER}/${REPO_NAME}.git"
XIAOMI_HARDWARE_URL="https://github.com/LineageOS/android_hardware_xiaomi.git"

TARGET=""
ROM=""
RECOVERY=""

DEVICE_BRANCH=""
# The Android release selects the matching pinned stock inputs automatically.
ANDROID=""
LINEAGE_BRANCH=""

MANIFEST_DIR=".repo/local_manifests"
MANIFEST_FILE="${MANIFEST_DIR}/piano.xml"
REQUESTED_ANDROID=""
REQUESTED_ROM=""
REQUESTED_RECOVERY=""
ASSUME_YES=false

usage() {
    cat <<'EOF'
Usage: bash sync-device.sh [--android 16|17 --rom lineage|evox | --recovery twrp|ofox] [--yes]

Without arguments, choose the release interactively. Re-running always refreshes
the generated Git manifest and all selected branches using repo sync (the
repo-managed equivalent of git pull), then re-stages Android-specific files.
--yes skips only the setup confirmation; it never forces dirty Git checkouts.
EOF
}

while (($#)); do
    case "$1" in
        --android|--rom|--recovery)
            option="$1"
            (($# >= 2)) || { usage >&2; exit 2; }
            case "$option" in
                --android) REQUESTED_ANDROID="$2" ;;
                --rom) REQUESTED_ROM="$2" ;;
                --recovery) REQUESTED_RECOVERY="$2" ;;
            esac
            shift 2
            ;;
        -y|--yes) ASSUME_YES=true; shift ;;
        -h|--help) usage; exit 0 ;;
        *) usage >&2; echo "Unknown argument: $1" >&2; exit 2 ;;
    esac
done

if [[ -n "$REQUESTED_RECOVERY" && ( -n "$REQUESTED_ANDROID" || -n "$REQUESTED_ROM" ) ]]; then
    echo "Use either --recovery or --android/--rom." >&2
    exit 2
fi
if [[ -n "$REQUESTED_ROM" && -z "$REQUESTED_ANDROID" ]]; then
    echo "--rom requires --android." >&2
    exit 2
fi

# ============================================================
# Helpers
# ============================================================

die() {
    echo
    echo "ERROR: $*" >&2
    exit 1
}

header() {
    echo
    echo "============================================================"
    echo "$1"
    echo "============================================================"
}

check_android_source() {
    [[ -d ".repo" ]] || die "No .repo directory found."
    [[ -d "build" ]] || die "No build directory found."

    # The checkout's bundled repo works even when `repo` is not in SSH PATH.
    if ! command -v repo >/dev/null 2>&1 && [[ -x .repo/repo/repo ]]; then
        PATH="$(pwd -P)/.repo/repo:$PATH"
        export PATH
    fi
    command -v repo >/dev/null 2>&1 || die "repo command not found (expected .repo/repo/repo)."

    echo "Android source checkout detected:"
    echo "  $(pwd -P)"
}

check_branch() {
    local branch="$1"
    local remote_url="${2:-$REPO_URL}"

    [[ "$branch" =~ ^[a-zA-Z0-9._/-]+$ ]] || die "Invalid branch name: $branch"
    printf "Checking %-24s ... " "$branch"

    if git ls-remote \
        --exit-code \
        --heads \
        "$remote_url" \
        "refs/heads/$branch" >/dev/null 2>&1; then

        echo "OK"
    else
        echo "MISSING"
        die "Branch '$branch' does not exist in $remote_url"
    fi
}

confirm() {
    if [[ "$ASSUME_YES" == true ]]; then return; fi
    local answer

    read -r -p "Continue? [Y/n]: " answer

    case "${answer:-y}" in
        y|Y|yes|YES)
            ;;
        *)
            echo "Cancelled."
            exit 0
            ;;
    esac
}

# Updating the manifest is idempotent. Preserve an older, different manifest
# before replacing it atomically; never prompt each time a builder pulls.
install_manifest() {
    mkdir -p "$MANIFEST_DIR"
    [[ ! -L "$MANIFEST_FILE" ]] || die "Refusing symlink manifest: $MANIFEST_FILE"
    local candidate
    candidate=$(mktemp "${MANIFEST_DIR}/.piano.xml.XXXXXXXX")
    cat > "$candidate"
    if [[ -f "$MANIFEST_FILE" ]] && cmp -s "$candidate" "$MANIFEST_FILE"; then
        rm -f "$candidate"
        echo "Manifest already current: $MANIFEST_FILE"
        return
    fi
    if [[ -e "$MANIFEST_FILE" ]]; then
        local backup="${MANIFEST_FILE}.backup.$(date -u +%Y%m%dT%H%M%SZ).$$"
        cp -p "$MANIFEST_FILE" "$backup"
        echo "Saved previous manifest: $backup"
    fi
    mv -f "$candidate" "$MANIFEST_FILE"
    echo "Updated Git manifest: $MANIFEST_FILE"
}

check_worktrees_clean() {
    local path
    for path in "$@"; do
        [[ -e "$path/.git" ]] || continue
        [[ -z "$(git -C "$path" status --porcelain --untracked-files=no)" ]] || \
            die "Uncommitted tracked changes in $path; refusing to sync over local edits."
    done
}

# ============================================================
# Start
# ============================================================

header "Xiaomi Pad 8 Pro (piano) Tree Sync"

check_android_source

echo
echo "Choose Android version or recovery:"
echo
echo "  1) Android 17 ROM (active)"
echo "  2) Android 16 ROM (maintenance)"
echo "  3) Recovery (TWRP / OrangeFox)"
echo

if [[ -n "$REQUESTED_RECOVERY" ]]; then
    choice=3
elif [[ -n "$REQUESTED_ANDROID" ]]; then
    case "$REQUESTED_ANDROID" in
        17) choice=1 ;;
        16) choice=2 ;;
        *) die "Unsupported Android release: $REQUESTED_ANDROID" ;;
    esac
else
    read -r -p "Choose [1]: " choice
fi

case "${choice:-1}" in
    1)
        TARGET="rom"
        ANDROID=17
        LINEAGE_BRANCH="lineage-24.0"
        ;;
    2)
        TARGET="rom"
        ANDROID=16
        LINEAGE_BRANCH="lineage-23.2"
        ;;
    3)
        TARGET="recovery"
        ;;
    *)
        die "Invalid selection."
        ;;
esac

COMMON_BRANCH="device-common"
VENDOR_BRANCH="vendor"
VENDOR_COMMON_BRANCH="vendor-common"
KERNEL_BRANCH="kernel"

TWRP_BRANCH="twrp"
OFOX_BRANCH="ofox"

# ============================================================
# ROM
# ============================================================

if [[ "$TARGET" == "rom" ]]; then

    echo
    echo "Available Android $ANDROID ROMs:"
    echo
    echo "  1) LineageOS ${LINEAGE_BRANCH#lineage-}"
    if [[ "$ANDROID" == 17 ]]; then
        echo "  2) Evolution X 12.3"
    fi
    echo

    if [[ -n "$REQUESTED_ROM" ]]; then
        case "$REQUESTED_ROM" in
            lineage) choice=1 ;;
            evox) choice=2 ;;
            *) die "Unsupported ROM: $REQUESTED_ROM" ;;
        esac
    else
        read -r -p "Choose [1]: " choice
    fi

    case "${choice:-1}" in
        1)
            ROM="lineage"
            DEVICE_BRANCH="$LINEAGE_BRANCH"
            ROM_NAME="LineageOS ${LINEAGE_BRANCH#lineage-}"
            ;;

        2)
            [[ "$ANDROID" == 17 ]] || die "Evolution X is not available for Android $ANDROID."
            ROM="evox"
            ROM_NAME="Evolution X 12.3"
            DEVICE_BRANCH="evox-12.3"
            ;;

        *)
            die "Invalid ROM selection."
            ;;
    esac

    # No package-by-package opt-in: the chosen Android release determines all
    # four hardware source variants; all three shared Xiaomi apps and the
    # upstream Xiaomi hardware namespace are included.
    paths=(
        device/xiaomi/piano
        device/xiaomi/sm8750-common
        vendor/xiaomi/piano
        vendor/xiaomi/sm8750-common
        device/xiaomi/piano-kernel
        device/xiaomi/piano-parts
        vendor/xiaomi/piano-miuicamera
        vendor/xiaomi/piano-notes
        hardware/xiaomi
    )
    branches=("$DEVICE_BRANCH" "$COMMON_BRANCH" "$VENDOR_BRANCH" "$VENDOR_COMMON_BRANCH" "$KERNEL_BRANCH" pianopart camera notes "$LINEAGE_BRANCH")
    selected=("${!paths[@]}")

    header "ROM Setup"

    echo "ROM:"
    echo "  $ROM_NAME"
    echo
    echo "Repository:"
    echo "  $REPO_URL"
    echo
    echo "Trees:"
    for index in "${selected[@]}"; do
        echo "  ${paths[$index]} -> ${branches[$index]}"
    done
    echo

    confirm

    header "Checking Branches"

    for index in "${selected[@]}"; do
        if [[ "${paths[$index]}" == hardware/xiaomi ]]; then
            check_branch "${branches[$index]}" "$XIAOMI_HARDWARE_URL"
        else
            check_branch "${branches[$index]}"
        fi
    done

    sync_paths=()
    for index in "${selected[@]}"; do sync_paths+=("${paths[$index]}"); done
    check_worktrees_clean "${sync_paths[@]}"

    header "Creating Local Manifest"

    {
        echo '<?xml version="1.0" encoding="UTF-8"?>'
        echo '<manifest>'
        printf '  <remote name="piano" fetch="https://github.com/%s" />\n' "$GITHUB_USER"
        echo '  <remote name="piano-lineage" fetch="https://github.com/LineageOS" />'
        for index in "${selected[@]}"; do
            if [[ "${paths[$index]}" == hardware/xiaomi ]]; then
                printf '  <project name="android_hardware_xiaomi" path="hardware/xiaomi" remote="piano-lineage" revision="%s" />\n' "${branches[$index]}"
            else
                printf '  <project name="%s" path="%s" remote="piano" revision="%s" />\n' "$REPO_NAME" "${paths[$index]}" "${branches[$index]}"
            fi
        done
        echo '</manifest>'
    } | install_manifest

    header "Syncing ROM Trees"

    repo sync "${sync_paths[@]}" --no-clone-bundle --no-tags -j"${SYNC_JOBS:-4}"

    header "Preparing Android $ANDROID Hardware and Xiaomi Apps"
    python3 device/xiaomi/sm8750-common/select-version.py "$ANDROID"
    python3 vendor/xiaomi/piano/select-version.py "$ANDROID"
    python3 vendor/xiaomi/sm8750-common/select-version.py "$ANDROID"
    python3 device/xiaomi/piano-kernel/select-version.py "$ANDROID"
    python3 device/xiaomi/piano-parts/select-rom.py "$ROM"
    python3 vendor/xiaomi/piano-miuicamera/select-camera.py "$ANDROID"
    python3 vendor/xiaomi/piano-notes/stage-originals.py --from-parts

    header "ROM Tree Sync Complete"

    echo "ROM:"
    echo "  $ROM_NAME"
    echo
    echo "Synced:"
    for index in "${selected[@]}"; do echo "  ${paths[$index]} (${branches[$index]})"; done
fi

# ============================================================
# Recovery
# ============================================================

if [[ "$TARGET" == "recovery" ]]; then

    echo
    echo "Select Recovery:"
    echo
    echo "  1) TWRP"
    echo "  2) OrangeFox"
    echo

    if [[ -n "$REQUESTED_RECOVERY" ]]; then
        case "$REQUESTED_RECOVERY" in
            twrp) choice=1 ;;
            ofox) choice=2 ;;
            *) die "Unsupported recovery: $REQUESTED_RECOVERY" ;;
        esac
    else
        read -r -p "Choose [1]: " choice
    fi

    case "${choice:-1}" in
        1)
            RECOVERY="twrp"
            RECOVERY_NAME="TWRP"
            DEVICE_BRANCH="$TWRP_BRANCH"
            ;;

        2)
            RECOVERY="ofox"
            RECOVERY_NAME="OrangeFox"
            DEVICE_BRANCH="$OFOX_BRANCH"
            ;;

        *)
            die "Invalid recovery selection."
            ;;
    esac

    header "Recovery Setup"

    echo "Recovery:"
    echo "  $RECOVERY_NAME"
    echo
    echo "Repository:"
    echo "  $REPO_URL"
    echo
    echo "Tree:"
    echo "  device/xiaomi/piano -> $DEVICE_BRANCH"
    echo

    confirm

    header "Checking Branch"

    check_branch "$DEVICE_BRANCH"
    check_worktrees_clean device/xiaomi/piano

    header "Creating Local Manifest"

    install_manifest <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<manifest>

    <remote
        name="piano"
        fetch="https://github.com/${GITHUB_USER}"
    />

    <!-- Recovery Device Tree -->
    <project
        name="${REPO_NAME}"
        path="device/xiaomi/piano"
        remote="piano"
        revision="${DEVICE_BRANCH}"
    />

</manifest>
EOF

    header "Syncing Recovery Tree"

    repo sync \
        device/xiaomi/piano \
        --no-clone-bundle \
        --no-tags \
        -j"${SYNC_JOBS:-4}"

    header "Recovery Tree Sync Complete"

    echo "Recovery:"
    echo "  $RECOVERY_NAME"
    echo
    echo "Synced:"
    echo "  device/xiaomi/piano"
    echo
    echo "Branch:"
    echo "  $DEVICE_BRANCH"
fi
