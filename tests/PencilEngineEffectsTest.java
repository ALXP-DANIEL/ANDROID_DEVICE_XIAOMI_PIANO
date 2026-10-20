/* SPDX-License-Identifier: Apache-2.0 */
package com.xiaomi.touchservice.pencilengine;
import android.content.Context;
import android.app.ActivityManager;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.Settings;
import org.lineageos.pianoparts.pencilengine.*;
import static org.lineageos.pianoparts.pencilengine.PencilEngineRegistry.ActionType.*;
public final class PencilEngineEffectsTest {
    static int checks;
    static Context context;
    static PencilEngineEffects effects;
    static PencilEngineRegistry.Caller notes=new PencilEngineRegistry.Caller(10001);
    static void check(boolean value){checks++;if(!value)throw new AssertionError("check "+checks);}
    static void reset(){
        if(effects!=null)effects.close();
        Settings.values.clear();Settings.writes=true;Handler.tasks.clear();Handler.accept=true;
        ActivityManager.user=0;SystemClock.now=10000;context=new Context();
        Settings.values.put("boot","7");Settings.values.put(PencilEngineLease.TOUCH_FILM_READY,"7");
        Settings.values.put(PencilEngineLease.POSTURE_READY,"7");heartbeat();
        effects=new PencilEngineEffects(context,new ScreenColorPicker());
    }
    static void heartbeat(){Settings.values.put(PencilEngineLease.POSTURE_HEARTBEAT,""+SystemClock.now);}
    static boolean apply(PencilEngineRegistry.ActionType t,boolean on){return effects.apply(notes,1,new PencilEngineRegistry.Action(t,t==SET_POSTURE_MODE?null:"com.miui.notes",on));}
    static String lease(String key){return Settings.Secure.getStringForUser(context,key,0);}
    static void posture(){check(apply(SET_POSTURE_PACKAGE,true));check(apply(SET_POSTURE_MODE,true));}
    public static void main(String[] args){
        reset();check(effects.supports(1));Settings.values.remove(PencilEngineLease.POSTURE_READY);check(!effects.supports(1));
        reset();Settings.values.put(PencilEngineLease.POSTURE_READY,"6");check(!effects.supports(1));
        reset();Settings.values.put(PencilEngineLease.POSTURE_HEARTBEAT,"7000");check(!effects.supports(1));
        reset();Settings.values.put(PencilEngineLease.POSTURE_HEARTBEAT,"10001");check(!effects.supports(1));
        reset();ActivityManager.user=1;check(!effects.supports(1));check(!apply(SET_POSTURE_PACKAGE,true));check(effects.registrationPackage(notes)==null);
        reset();context.pm.uid=10002;check(!apply(SET_POSTURE_PACKAGE,true));check(effects.registrationPackage(notes)==null);
        reset();context.user=1;check(!apply(SET_POSTURE_PACKAGE,true));
        reset();context.permission=-1;check(!effects.supports(1));
        reset();check(!apply(SET_POSTURE_MODE,true));posture();check(apply(SET_TOUCH_FILM_PACKAGE,true));
        String old=lease(PencilEngineLease.POSTURE);SystemClock.now+=1500;heartbeat();Handler.tick();
        check(!old.equals(lease(PencilEngineLease.POSTURE)));check(lease(PencilEngineLease.TOUCH_FILM)!=null);
        check(apply(SET_TOUCH_FILM_PACKAGE,false));check(lease(PencilEngineLease.TOUCH_FILM)==null);check(Handler.tasks.size()==1);
        SystemClock.now+=1500;heartbeat();Handler.tick();check(lease(PencilEngineLease.POSTURE)!=null);
        check(apply(SET_POSTURE_PACKAGE,false));check(apply(SET_POSTURE_MODE,false));check(Handler.tasks.isEmpty());check(lease(PencilEngineLease.POSTURE)==null);
        reset();posture();effects.close();check(lease(PencilEngineLease.POSTURE)==null);check(Handler.tasks.isEmpty());check(!effects.supports(1));
        reset();posture();Settings.writes=false;SystemClock.now+=1000;heartbeat();Handler.tick();check(!effects.supports(1));check(Handler.tasks.isEmpty());
        reset();Settings.writes=false;check(apply(SET_POSTURE_PACKAGE,true));check(!apply(SET_POSTURE_MODE,true));check(!effects.supports(1));
        reset();Handler.accept=false;check(apply(SET_POSTURE_PACKAGE,true));check(!apply(SET_POSTURE_MODE,true));check(lease(PencilEngineLease.POSTURE)==null);check(!effects.supports(1));
        reset();posture();check(apply(SET_TOUCH_FILM_PACKAGE,true));Handler.accept=false;check(!apply(SET_TOUCH_FILM_PACKAGE,false));check(lease(PencilEngineLease.POSTURE)==null);check(!effects.supports(1));
        reset();posture();context.pm.uid=10002;Handler.tick();check(lease(PencilEngineLease.POSTURE)==null);check(Handler.tasks.isEmpty());
        reset();posture();ActivityManager.user=1;Handler.tick();check(lease(PencilEngineLease.POSTURE)==null);check(Handler.tasks.isEmpty());
        reset();posture();SystemClock.now+=2500;Handler.tick();check(lease(PencilEngineLease.POSTURE)==null);check(!effects.supports(1));
        reset();check(apply(SET_TOUCH_FILM_PACKAGE,true));posture();check(apply(SET_POSTURE_PACKAGE,false));check(apply(SET_POSTURE_MODE,false));check(Handler.tasks.size()==1);check(lease(PencilEngineLease.TOUCH_FILM)!=null);
        effects.close();check(lease(PencilEngineLease.TOUCH_FILM)==null);
        System.out.println(checks+" production Effects lifecycle checks passed");
    }
}
