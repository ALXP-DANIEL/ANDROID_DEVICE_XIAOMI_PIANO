/* SPDX-License-Identifier: Apache-2.0 */
package miui.accounts;

import android.accounts.Account;
import android.content.Context;

/** App-local signed-out policy for the offline Notes port, not an account SDK. */
public final class ExtraAccountManager {
    public static final String XIAOMI_ACCOUNT_TYPE = "com.xiaomi";

    // Notes' local provider queries this even when creating a handwritten page.
    // No Xiaomi login, token or cloud identity is provided by this offline port.
    public static Account getXiaomiAccount(Context context) { return null; }

    private ExtraAccountManager() {}
}
