#!/vendor/bin/sh
# SPDX-License-Identifier: Apache-2.0
# Do not authorize QSEE against an unmounted directory after mount_all failure.
while read -r source target filesystem options rest; do
    case "$source:$target:$filesystem" in
        /dev/block/*:/mnt/vendor/persist:ext4)
            case ",$options," in
                *,rw,*)
                    setprop vendor.piano.persist.ready 1
                    exit "$?"
                    ;;
            esac
            ;;
    esac
done < /proc/mounts
echo 'piano: persist is not mounted; qseecomd remains disabled' >&2
exit 1
