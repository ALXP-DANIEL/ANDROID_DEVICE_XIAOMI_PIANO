#!/usr/bin/env python3
"""Exercise the production reflection bridge against both framework capture ABIs."""
from pathlib import Path
import os, shutil, subprocess, tempfile
ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "pencilengine-service/src/com/xiaomi/touchservice/pencilengine/DisplayCaptureCompat.java"
JAVA = Path(os.environ.get("JAVA_HOME", "/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home")) / "bin"
COMMON = {
"android/graphics/ColorSpace.java": "package android.graphics; public class ColorSpace {}",
"android/hardware/HardwareBuffer.java": "package android.hardware; public class HardwareBuffer { public boolean closed; public void close(){closed=true;} }",
"android/os/Handler.java": "package android.os; public class Handler { public static boolean accept=true; public boolean post(Runnable r){if(!accept)return false;r.run();return true;} }",
"android/os/OutcomeReceiver.java": "package android.os; public interface OutcomeReceiver<T,E extends Throwable>{void onResult(T r);void onError(E e);}",
"android/view/Display.java": "package android.view; public class Display { public static final int DEFAULT_DISPLAY=0; }",
"android/window/State.java": """package android.window; public class State {
 public static boolean secure, protectedFlag, bad, requestFailure;
 public static int status; public static android.hardware.HardwareBuffer buffer;
 public static void reset(){secure=false; protectedFlag=false; bad=false; requestFailure=false; status=0; buffer=new android.hardware.HardwareBuffer();}
}""",
}
RESULT = """public static class Result {
 public android.hardware.HardwareBuffer getHardwareBuffer(){return State.buffer;}
 public android.graphics.ColorSpace getColorSpace(){return new android.graphics.ColorSpace();}
 public boolean containsSecureLayers(){return State.secure;}
}"""
NEW = """package android.window; public class ScreenCapture {
 public static class ScreenCaptureParams {
  public static final int SECURE_CONTENT_POLICY_THROW_EXCEPTION=2, PROTECTED_CONTENT_POLICY_THROW_EXCEPTION=2;
  public static class Builder {
   public Builder(int display){if(display!=0)State.bad=true;}
   public Builder setSecureContentPolicy(int p){if(p!=2)State.bad=true;return this;}
   public Builder setProtectedContentPolicy(int p){if(p!=2)State.bad=true;return this;}
   public Builder setUseDisplayInstallationOrientation(boolean b){if(b)State.bad=true;return this;}
   public Builder setPreserveDisplayColors(boolean b){if(b)State.bad=true;return this;}
   public ScreenCaptureParams build(){return new ScreenCaptureParams();}
  }
 }
 public static void capture(ScreenCaptureParams p,java.util.concurrent.Executor e,android.os.OutcomeReceiver<Object,Exception> r){
  if(State.requestFailure)throw new IllegalStateException();
  if(State.secure||State.protectedFlag)r.onError(new SecurityException());else r.onResult(new Result());
 }
 @RESULT@
}""".replace("@RESULT@",RESULT)
LEGACY = """package android.window; public class @OWNER@ {
 public static class CaptureArgs {
  public static class Builder {
   public Builder(){}
   public Builder setCaptureSecureLayers(boolean b){if(b)State.bad=true;return this;}
   public Builder setAllowProtected(boolean b){if(b)State.bad=true;return this;}
   public CaptureArgs build(){return new CaptureArgs();}
  }
 }
 public static class ScreenCaptureListener {
  public final java.util.function.ObjIntConsumer<Object> callback;
  public ScreenCaptureListener(java.util.function.ObjIntConsumer<Object> c){callback=c;}
 }
 @RESULT@
}""".replace("@RESULT@",RESULT)
HARNESS = """package com.xiaomi.touchservice.pencilengine;
import android.window.State; import android.os.Handler;
public class CaptureTest {
 static int ok,fail;
 static void run(){ok=fail=0;DisplayCaptureCompat.capture(new Handler(), f->{ok++;f.buffer.close();},e->fail++);}
 static void check(boolean b){if(!b)throw new AssertionError();}
 public static void main(String[] args){
  State.reset();run();check(ok==1&&fail==0&&State.buffer.closed&&!State.bad);
  State.reset();State.secure=true;run();check(ok==0&&fail==1&&!State.bad);
  if(args[0].equals("legacy"))check(State.buffer.closed);
  State.reset();State.requestFailure=true;run();check(ok==0&&fail==1);
  State.reset();Handler.accept=false;run();check(ok==0&&State.buffer.closed);Handler.accept=true;
  if(args[0].equals("new")){State.reset();State.protectedFlag=true;run();check(ok==0&&fail==1);}
  else{State.reset();State.status=5;run();check(ok==0&&fail==1&&State.buffer.closed);}
  System.out.println("PASS "+args[0]+": success, secure rejection, request error, rejected dispatch, protected/status failure");
 }
}"""
for abi in ("new", "legacy", "relocated"):
 with tempfile.TemporaryDirectory() as tmp:
  root=Path(tmp); files=dict(COMMON)
  if abi=="new": files["android/window/ScreenCapture.java"]=NEW
  else:
   owner="ScreenCaptureInternal" if abi=="relocated" else "ScreenCapture"
   files[f"android/window/{owner}.java"]=LEGACY.replace("@OWNER@",owner)
   files["android/view/IWindowManager.java"]=("package android.view; public interface IWindowManager { void captureDisplay(int d,android.window.@O@.CaptureArgs a,android.window.@O@.ScreenCaptureListener l); }").replace("@O@",owner)
   files["android/view/WindowManagerGlobal.java"]=("""package android.view; public class WindowManagerGlobal {
    public static IWindowManager getWindowManagerService(){return (d,a,l)->{
     if(android.window.State.requestFailure)throw new IllegalStateException();
     if(d!=0)android.window.State.bad=true;
     l.callback.accept(new android.window.@O@.Result(),android.window.State.status);
    };}
   }""").replace("@O@",owner)
  files["com/xiaomi/touchservice/pencilengine/CaptureTest.java"]=HARNESS
  for name,text in files.items():
   path=root/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(text)
  target=root/"com/xiaomi/touchservice/pencilengine/DisplayCaptureCompat.java";shutil.copyfile(SOURCE,target)
  out=root/"classes";out.mkdir()
  subprocess.run([str(JAVA/"javac"),"-d",str(out),*map(str,root.rglob("*.java"))],check=True)
  subprocess.run([str(JAVA/"java"),"-cp",str(out),"com.xiaomi.touchservice.pencilengine.CaptureTest","new" if abi=="new" else "legacy"],check=True)
