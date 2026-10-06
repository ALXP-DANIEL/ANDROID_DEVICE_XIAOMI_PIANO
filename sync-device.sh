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
# This script only syncs device/vendor/kernel trees.
# It does NOT download the ROM or recovery source itself.
#
# Run this script from the root of your Android source checkout.
# ============================================================

GITHUB_USER="ALXP-DANIEL"
REPO_NAME="android_device_xiaomi_piano"
REPO_URL="https://github.com/${GITHUB_USER}/${REPO_NAME}.git"

TARGET=""
ROM=""
RECOVERY=""

DEVICE_BRANCH=""
COMMON_BRANCH="device-common-16"
VENDOR_BRANCH="vendor-16"
VENDOR_COMMON_BRANCH="vendor-common-16"
KERNEL_BRANCH="kernel-16"
CAMERA_BRANCH="miuicamera-16"

TWRP_BRANCH="twrp-16"
OFOX_BRANCH="ofox-16"

MANIFEST_DIR=".repo/local_manifests"
MANIFEST_FILE="${MANIFEST_DIR}/piano.xml"

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

    echo "Android source checkout detected:"
    echo "  $(pwd -P)"
}

check_branch() {
    local branch="$1"

    [[ "$branch" =~ ^[a-zA-Z0-9._/-]+$ ]] || die "Invalid branch name: $branch"
    printf "Checking %-24s ... " "$branch"

    if git ls-remote \
        --exit-code \
        --heads \
        "$REPO_URL" \
        "refs/heads/$branch" >/dev/null 2>&1; then

        echo "OK"
    else
        echo "MISSING"
        die "Branch '$branch' does not exist in $REPO_URL"
    fi
}

confirm() {
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

# ============================================================
# Start
# ============================================================

header "Xiaomi Pad 8 Pro (piano) Tree Sync"

check_android_source

echo
echo "What do you want to sync?"
echo
echo "  1) ROM"
echo "  2) Recovery"
echo

read -r -p "Choose [1]: " choice

case "${choice:-1}" in
    1)
        TARGET="rom"
        ;;
    2)
        TARGET="recovery"
        ;;
    *)
        die "Invalid selection."
        ;;
esac

# ============================================================
# ROM
# ============================================================

if [[ "$TARGET" == "rom" ]]; then

    echo
    echo "Select ROM:"
    echo
    echo "  1) LineageOS 23.2"
    echo "  2) Evolution X"
    echo

    read -r -p "Choose [1]: " choice

    case "${choice:-1}" in
        1)
            ROM="lineage"
            DEVICE_BRANCH="lineage-23.2"
            ROM_NAME="LineageOS 23.2"
            ;;

        2)
            ROM="evox"
            ROM_NAME="Evolution X"

            echo
            read -r -p "Evolution X device branch: " DEVICE_BRANCH

            [[ -n "$DEVICE_BRANCH" ]] \
                || die "Evolution X device branch cannot be empty."
            ;;

        *)
            die "Invalid ROM selection."
            ;;
    esac

    paths=(device/xiaomi/piano device/xiaomi/sm8750-common vendor/xiaomi/piano vendor/xiaomi/sm8750-common device/xiaomi/piano-kernel vendor/xiaomi/piano-miuicamera)
    branches=("$DEVICE_BRANCH" "$COMMON_BRANCH" "$VENDOR_BRANCH" "$VENDOR_COMMON_BRANCH" "$KERNEL_BRANCH" "$CAMERA_BRANCH")
    echo "Trees: 1=device 2=common 3=vendor 4=vendor-common 5=kernel 6=camera"
    read -r -p "Choose numbers separated by spaces, or all [all]: " selection
    selected=()
    if [[ "${selection:-all}" == all ]]; then
        selected=(0 1 2 3 4 5)
    else
        for number in $selection; do
            [[ "$number" =~ ^[1-6]$ ]] || die "Invalid tree: $number"
            index=$((number - 1))
            for old in "${selected[@]}"; do
                [[ "$old" != "$index" ]] || die "Duplicate tree: $number"
            done
            selected+=("$index")
        done
    fi
    [[ ${#selected[@]} -gt 0 ]] || die "Select at least one tree."
    for index in "${selected[@]}"; do
        read -r -p "${paths[$index]} branch [${branches[$index]}]: " branch
        branches[$index]=${branch:-${branches[$index]}}
    done

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

    for index in "${selected[@]}"; do check_branch "${branches[$index]}"; done

    header "Creating Local Manifest"

    mkdir -p "$MANIFEST_DIR"

    if [[ -e "$MANIFEST_FILE" ]]; then
        echo "Existing manifest found:"
        echo "  $MANIFEST_FILE"
        echo
        read -r -p "Replace it? [y/N]: " answer

        case "${answer:-n}" in
            y|Y|yes|YES)
                backup="${MANIFEST_FILE}.backup.$(date -u +%Y%m%dT%H%M%SZ).$$"
                cp -p "$MANIFEST_FILE" "$backup"
                echo "Saved previous manifest: $backup"
                ;;
            *)
                die "Manifest already exists."
                ;;
        esac
    fi

    {
        echo '<?xml version="1.0" encoding="UTF-8"?>'
        echo '<manifest>'
        printf '  <remote name="piano" fetch="https://github.com/%s" />\n' "$GITHUB_USER"
        for index in "${selected[@]}"; do
            printf '  <project name="%s" path="%s" remote="piano" revision="%s" />\n' "$REPO_NAME" "${paths[$index]}" "${branches[$index]}"
        done
        echo '</manifest>'
    } > "$MANIFEST_FILE"

    echo "Created:"
    echo "  $MANIFEST_FILE"

    header "Syncing ROM Trees"

    sync_paths=()
    for index in "${selected[@]}"; do sync_paths+=("${paths[$index]}"); done
    repo sync "${sync_paths[@]}" --no-clone-bundle --no-tags -j"${SYNC_JOBS:-4}"

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
    echo "  1) TWRP 16"
    echo "  2) OrangeFox 16"
    echo

    read -r -p "Choose [1]: " choice

    case "${choice:-1}" in
        1)
            RECOVERY="twrp"
            RECOVERY_NAME="TWRP 16"
            DEVICE_BRANCH="$TWRP_BRANCH"
            ;;

        2)
            RECOVERY="ofox"
            RECOVERY_NAME="OrangeFox 16"
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

    header "Creating Local Manifest"

    mkdir -p "$MANIFEST_DIR"

    if [[ -e "$MANIFEST_FILE" ]]; then
        echo "Existing manifest found:"
        echo "  $MANIFEST_FILE"
        echo
        read -r -p "Replace it? [y/N]: " answer

        case "${answer:-n}" in
            y|Y|yes|YES)
                backup="${MANIFEST_FILE}.backup.$(date -u +%Y%m%dT%H%M%SZ).$$"
                cp -p "$MANIFEST_FILE" "$backup"
                echo "Saved previous manifest: $backup"
                ;;
            *)
                die "Manifest already exists."
                ;;
        esac
    fi

    cat > "$MANIFEST_FILE" <<EOF
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

    echo "Created:"
    echo "  $MANIFEST_FILE"

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