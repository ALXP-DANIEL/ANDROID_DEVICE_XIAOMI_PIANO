/* SPDX-License-Identifier: Apache-2.0 */
package miui.os;

import android.os.SystemProperties;

/** Only the build fields referenced by pinned Notes 3.2.2.8. */
public final class Build extends android.os.Build {
    // Same property predicates as .44 miui.os.Build. DEVICE and TAGS are
    // inherited from Android and describe the running ROM, not a forged stock build.
    public static final boolean IS_TABLET =
            SystemProperties.get("ro.build.characteristics", "").contains("tablet");
    public static final boolean IS_INTERNATIONAL_BUILD =
            SystemProperties.get("ro.product.mod_device", "").contains("_global");
    public static final boolean IS_GLOBAL_BUILD =
            SystemProperties.get("ro.product.mod_device", "").endsWith("_global");

    private Build() {}
}
