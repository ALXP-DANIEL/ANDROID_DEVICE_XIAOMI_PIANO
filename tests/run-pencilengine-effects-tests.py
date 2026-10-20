#!/usr/bin/env python3
"""Run the production Effects lifecycle with deterministic Android boundary fixtures."""
import pathlib, subprocess, tempfile
root = pathlib.Path(__file__).resolve().parents[1]
fixtures = {
"android/Manifest.java": 'package android; public class Manifest { public static class permission {public static final String WRITE_SECURE_SETTINGS="write";} }',
"android/app/ActivityManager.java": 'package android.app; public class ActivityManager { public static int user; public static int getCurrentUser(){return user;} }',
"android/content/Context.java": 'package android.content; public class Context {public int user,permission; public android.content.pm.PackageManager pm=new android.content.pm.PackageManager(); public int getUserId(){return user;} public int checkSelfPermission(String p){return permission;} public Object getContentResolver(){return this;} public android.content.pm.PackageManager getPackageManager(){return pm;} }',
"android/content/pm/PackageManager.java": 'package android.content.pm; public class PackageManager {public static final int PERMISSION_GRANTED=0; public int uid=10001; public int getPackageUid(String p,int f)throws NameNotFoundException {if(uid<0)throw new NameNotFoundException();return uid;} public static class NameNotFoundException extends Exception{}}',
"android/os/Looper.java": 'package android.os; public class Looper {public static Looper getMainLooper(){return new Looper();}}',
"android/os/SystemClock.java": 'package android.os; public class SystemClock {public static long now=10000; public static long elapsedRealtime(){return now;}}',
"android/os/UserHandle.java": 'package android.os; public class UserHandle {public static int getUserId(int uid){return uid/100000;}}',
"android/os/Handler.java": 'package android.os; public class Handler {public static boolean accept=true; public static java.util.Set<Runnable> tasks=new java.util.HashSet<>();public Handler(Looper l){} public boolean postDelayed(Runnable r,long t){if(!accept)return false;tasks.add(r);return true;} public void removeCallbacks(Runnable r){tasks.remove(r);} public static void tick(){for(Runnable r:new java.util.HashSet<>(tasks)){tasks.remove(r);r.run();}}}',
"android/provider/Settings.java": 'package android.provider; public class Settings {public static java.util.Map<String,String> values=new java.util.HashMap<>();public static boolean writes=true;public static class Global {public static final String BOOT_COUNT="boot";public static int getInt(Object c,String k,int d){return Integer.parseInt(values.getOrDefault(k,""+d));}public static long getLong(Object c,String k,long d){return Long.parseLong(values.getOrDefault(k,""+d));}}public static class Secure {public static String getStringForUser(Object c,String k,int u){return values.get(u+":"+k);}public static boolean putStringForUser(Object c,String k,String v,int u){if(!writes)return false;if(v==null)values.remove(u+":"+k);else values.put(u+":"+k,v);return true;}}}',
"org/lineageos/pianoparts/pencilengine/PencilEngineBinder.java": 'package org.lineageos.pianoparts.pencilengine; public class PencilEngineBinder {public interface Backend {String registrationPackage(PencilEngineRegistry.Caller c);boolean supports(int f);boolean apply(PencilEngineRegistry.Caller c,long l,PencilEngineRegistry.Action a);}}',
"org/lineageos/pianoparts/pencilengine/PencilEngineRegistry.java": 'package org.lineageos.pianoparts.pencilengine;public class PencilEngineRegistry {public static class Caller {public final int uid;public Caller(int u){uid=u;}}public enum ActionType {SET_POSTURE_PACKAGE,SET_POSTURE_MODE,SET_TOUCH_FILM_PACKAGE}public static class Action {public ActionType type;public String packageName;public boolean enabled;public Action(ActionType t,String p,boolean e){type=t;packageName=p;enabled=e;}}}',
"com/xiaomi/touchservice/pencilengine/ScreenColorPicker.java": 'package com.xiaomi.touchservice.pencilengine;class ScreenColorPicker {boolean apply(org.lineageos.pianoparts.pencilengine.PencilEngineRegistry.Caller c,long l,org.lineageos.pianoparts.pencilengine.PencilEngineRegistry.Action a){return false;}}',
}
with tempfile.TemporaryDirectory() as tmp:
    out=pathlib.Path(tmp)
    for name, source in fixtures.items():
        dest=out/name;dest.parent.mkdir(parents=True,exist_ok=True);dest.write_text(source)
    sources=list(out.rglob("*.java"))+[root/"src/org/lineageos/pianoparts/pencilengine/PencilEngineProtocol.java",root/"pencilengine-service/src/com/xiaomi/touchservice/pencilengine/PencilEngineEffects.java",root/"src/org/lineageos/pianoparts/pencilengine/PencilEngineLease.java",root/"tests/PencilEngineEffectsTest.java"]
    subprocess.run(["javac","-d",str(out),*map(str,sources)],check=True)
    subprocess.run(["java","-cp",str(out),"com.xiaomi.touchservice.pencilengine.PencilEngineEffectsTest"],check=True)
