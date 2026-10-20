import android.os.*; import android.content.pm.ApplicationInfo; import android.content.pm.PackageManager; import org.lineageos.pianoparts.pencilengine.*;
public class PencilEngineBinderTest {
 static int checks; static boolean trusted=true,supported=true,throwSupport=false; static long lease; static int effects; static boolean accept=true;
 static class Token extends Binder { DeathRecipient death; boolean alive=true; public void linkToDeath(DeathRecipient d,int f){death=d;} public boolean unlinkToDeath(DeathRecipient d,int f){return true;} public boolean isBinderAlive(){return alive;} void die(){alive=false; death.binderDied();} }
 static PencilEngineBinder binder=new PencilEngineBinder(new PackageManager(){public int checkSignatures(int a,int b){return trusted?0:-1;} public String[] getPackagesForUid(int u){return new String[]{"com.example.notes"};}},new PencilEngineBinder.Backend(){ public boolean supports(int f){if(throwSupport)throw new SecurityException();return supported;} public boolean apply(PencilEngineRegistry.Caller c,long l,PencilEngineRegistry.Action a){effects++;lease=l;return accept;} });
 static Parcel data(){Parcel p=Parcel.obtain();p.writeInterfaceToken(PencilEngineProtocol.DESCRIPTOR);return p;}
 static void register(Token t,String pkg)throws Exception{Parcel p=data();p.writeStrongBinder(t);p.writeString(pkg);binder.transact(4,p,Parcel.obtain(),0);}
 static int trigger(int f,int v)throws Exception{Parcel p=data(),r=Parcel.obtain();p.writeInt(f);p.writeInt(v);binder.transact(3,p,r,0);return r.readInt();}
 static void check(boolean b){checks++;if(!b)throw new AssertionError(checks);}
 public static void main(String[] args)throws Exception{
 trusted=false;try{register(new Token(),"com.example.notes");throw new AssertionError();}catch(SecurityException ok){checks++;} trusted=true;
 try{register(new Token(),"com.attacker.notes");throw new AssertionError();}catch(SecurityException ok){checks++;}
 Token first=new Token();register(first,"com.example.notes");throwSupport=true;check(trigger(1,1)==-1);check(effects==0);throwSupport=false; supported=false;check(trigger(1,1)==-1);check(effects==0);supported=true;check(trigger(1,1)==0);check(effects==2);long old=lease;
 first.die();check(effects==4);check(trigger(1,1)==-1);
 Token second=new Token();register(second,"com.example.notes");check(trigger(1,1)==0);check(lease!=old);first.death.binderDied();check(trigger(1,0)==0);
 accept=false;check(trigger(1,1)==-1);accept=true;check(trigger(1,1)==-1);
 register(new Token(),"com.example.notes");check(trigger(99,0)==-1);
 check(trigger(0,1)==0);long pickerLease=lease;
 Binder.pid=43;register(new Token(),"com.example.notes");check(trigger(0,1)==-1);
 binder.cancelColorPick(42,2000,pickerLease-1);check(trigger(0,1)==-1);
 binder.cancelColorPick(42,2000,pickerLease);check(trigger(0,1)==0);
 binder.cancelColorPick(42,2000,pickerLease);int afterStaleCancel=effects;
 check(trigger(0,1)==0);check(effects==afterStaleCancel);
 binder.cancelColorPick(43,2000,lease);Binder.pid=42;
 check(trigger(0,1)==0);binder.cancelColorPick(42,2000,pickerLease);
 check(trigger(1,1)==0); int beforeClose=effects; binder.close();check(effects==beforeClose+2);
 check(trigger(1,1)==-1);binder.close();check(effects==beforeClose+2);
 try{register(new Token(),"com.example.notes");throw new AssertionError();}catch(IllegalStateException ok){checks++;}
 binder=new PencilEngineBinder(new PackageManager(){public int checkSignatures(int a,int b){return 0;}public String[] getPackagesForUid(int u){return new String[]{"com.example.notes"};}},new PencilEngineBinder.Backend(){public boolean supports(int f){return true;} public String registrationPackage(PencilEngineRegistry.Caller c){return "com.example.notes";}public boolean apply(PencilEngineRegistry.Caller c,long l,PencilEngineRegistry.Action a){return true;}});
 try{register(new Token(),null);throw new AssertionError();}catch(SecurityException ok){checks++;}
 Parcel legacy=data();legacy.writeStrongBinder(new Token());binder.transact(1,legacy,Parcel.obtain(),0);
 check(trigger(2,1)==0);check(trigger(2,0)==0);binder.close();
 for(boolean system:new boolean[]{true,false}){
  binder=new PencilEngineBinder(new PackageManager(){public int checkSignatures(int a,int b){return -1;}public String[] getPackagesForUid(int u){return new String[]{"com.miui.notes"};}public ApplicationInfo getApplicationInfo(String n,int f){ApplicationInfo i=new ApplicationInfo();i.flags=system?ApplicationInfo.FLAG_SYSTEM:0;return i;}},new PencilEngineBinder.Backend(){public boolean supports(int f){return true;}public boolean apply(PencilEngineRegistry.Caller c,long l,PencilEngineRegistry.Action a){return true;}});
  try{register(new Token(),"com.miui.notes");check(system);}catch(SecurityException rejected){check(!system);}
  binder.close();
 }
 System.out.println(checks+" Binder transport fixture checks passed; not Android runtime proof");
 }
}
