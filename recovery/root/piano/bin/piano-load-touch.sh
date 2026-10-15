#!/system/bin/sh

MODULE_DIR=/piano/lib/modules

log_touch() {
    echo "piano-touch: $*" > /dev/kmsg 2>/dev/null || true
}

module_loaded() {
    grep -q "^$1 " /proc/modules
}

load_module() {
    module="$1"
    path="$MODULE_DIR/$module.ko"

    if module_loaded "$module"; then
        log_touch "$module already loaded"
        return 0
    fi

    if insmod "$path"; then
        log_touch "$module loaded"
        return 0
    fi

    log_touch "$module load failed"
    return 1
}

attempt=1
while [ "$attempt" -le 3 ]; do
    log_touch "H3 load attempt $attempt"
    load_module xiaomi_touch && load_module nt36532_touch && {
        log_touch "H3 touch stack ready"
        exit 0
    }
    attempt=$((attempt + 1))
    sleep 1
done

log_touch "H3 touch stack unavailable"
exit 1
