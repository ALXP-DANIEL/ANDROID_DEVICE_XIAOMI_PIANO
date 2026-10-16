/* SPDX-License-Identifier: Apache-2.0 */
import java.util.*;
import org.lineageos.notes.OfflineUi;
import miui.accounts.ExtraAccountManager;
public final class OfflinePolicyTest {
    public static class Preference {
        public boolean visible=true;
        public void H(boolean value){visible=value;}
    }
    public static class Fragment {
        public final Map<String,Preference> preferences=new HashMap<>();
        public Preference y0(String key){return preferences.get(key);}
    }
    public static class Action {
        public final int a;
        Action(int id){a=id;}
    }
    public static void main(String[] args) {
        if(ExtraAccountManager.getXiaomiAccount(null)!=null)throw new AssertionError("offline account");
        if(!ExtraAccountManager.XIAOMI_ACCOUNT_TYPE.equals("com.xiaomi"))throw new AssertionError("account type");
        Fragment f=new Fragment();
        for(String key:new String[]{"pref_micloud","pref_category_ai","pref_key_cloud_trash_bin","pref_wearable_auto_note","pref_key_font_size","pref_local_recently_deleted"})
            f.preferences.put(key,new Preference());
        OfflineUi.hidePreferences(f);
        for(String key:new String[]{"pref_micloud","pref_category_ai","pref_key_cloud_trash_bin","pref_wearable_auto_note"})
            if(f.preferences.get(key).visible)throw new AssertionError(key);
        if(!f.preferences.get("pref_key_font_size").visible || !f.preferences.get("pref_local_recently_deleted").visible)
            throw new AssertionError("local preferences hidden");
        if(!OfflineUi.hideAi(f))throw new AssertionError("AI menu");
        Action ai=new Action(0x7f0a06fc),local=new Action(123);
        java.util.List<Action> original=java.util.Arrays.asList(ai,local);
        java.util.List<?> filtered=OfflineUi.filterActions(original);
        if(filtered.size()!=1 || filtered.get(0)!=local || original.size()!=2)throw new AssertionError("toolbar filtering");
        try{OfflineUi.filterActions(java.util.Arrays.asList(new Object()));throw new AssertionError("changed toolbar ABI accepted");}
        catch(IllegalStateException expected){}
        System.out.println("Offline account/UI policy checks passed; no Android UI/hardware proof");
    }
}
