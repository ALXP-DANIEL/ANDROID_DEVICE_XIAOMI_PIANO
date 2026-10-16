/* SPDX-License-Identifier: Apache-2.0 */
package org.lineageos.notes;

import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.Space;
import android.view.Menu;
import java.util.ArrayList;
import java.util.List;
import java.lang.reflect.Method;

/** UI policy for the exact pinned Notes APK; no device-capability spoofing. */
public final class OfflineUi {
    private static final String[] HIDDEN = {
        "pref_micloud", "pref_key_cloud_trash_bin", "pref_key_llm_register",
        "pref_key_agi_register", "pref_category_ai", "pref_ai_engine_source",
        "pref_smart_call_note", "pref_voice_memo", "pref_wearable_auto_note",
    };
    public static boolean hideAi(Object ignored) { return true; }
    public static void hidePreferences(Object fragment) {
        try {
            Method find = fragment.getClass().getMethod("y0", String.class);
            for (String key : HIDDEN) {
                Object preference = find.invoke(fragment, key);
                if (preference != null) preference.getClass().getMethod("H", boolean.class)
                        .invoke(preference, false);
            }
        } catch (ReflectiveOperationException | RuntimeException failure) {
            Log.e("PianoNotesOffline", "Pinned preference UI policy failed", failure);
        }
    }
    // IDs and action field ABI belong to the SHA-pinned .44 APK.
    public static void hideAiMenu(Object object) {
        ((Menu) object).removeItem(0x7f0a003d); // action_ai_assistant
    }
    public static void hideAiView(Object object) {
        View button=((View)object).findViewById(0x7f0a06fc); // pad_btnAskAi
        if(button!=null)button.setVisibility(View.GONE);
    }
    public static List<?> filterActions(Object object) {
        List<Object> result=new ArrayList<>();
        for(Object action:(List<?>)object) {
            try {
                if(action.getClass().getField("a").getInt(action)==0x7f0a06fc)continue;
            } catch(ReflectiveOperationException failure) {
                throw new IllegalStateException("Pinned toolbar action ABI changed",failure);
            }
            result.add(action);
        }
        return result;
    }
    /** btnAdd is the paperclip in the pinned homepage bottom bar. */
    public static void hideHomeImport(Object object) {
        View button=((View)object).findViewById(0x7f0a018b);
        if(button!=null) {
            button.setOnClickListener(null);
            button.setVisibility(View.GONE);
            // Stock reserves a fixed spacer and fixed pill width for three buttons.
            if(button.getParent() instanceof LinearLayout) {
                LinearLayout row=(LinearLayout)button.getParent();
                int next=row.indexOfChild(button)+1;
                if(next<row.getChildCount() && row.getChildAt(next) instanceof Space)
                    row.getChildAt(next).setVisibility(View.GONE);
                ViewGroup.LayoutParams rowParams=row.getLayoutParams();
                rowParams.width=ViewGroup.LayoutParams.WRAP_CONTENT;
                row.setLayoutParams(rowParams);
                if(row.getParent() instanceof View) {
                    View pill=(View)row.getParent();
                    ViewGroup.LayoutParams pillParams=pill.getLayoutParams();
                    pillParams.width=ViewGroup.LayoutParams.WRAP_CONTENT;
                    pill.setLayoutParams(pillParams);
                }
            }
        }
    }
    private OfflineUi() {}
}
