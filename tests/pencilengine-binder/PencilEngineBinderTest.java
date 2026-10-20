import android.os.*; import android.content.pm.PackageManager; import org.lineageos.pianoparts.pencilengine.*;
public class PencilEngineBinderTest {
 static int checks; static boolean trusted=true,supported=true; static long lease; static int effects; static boolean accept=true;
 static class Token extends Binder { DeathRecipient death; boolean alive=true; public void linkToDeath(DeathRecipient d,int f){death=d;} public boolean unlinkToDeath(DeathRecipient d,int f){return true;} public boolean isBinderAlive(){return alive;} void die(){alive=false; death.binderDied();} }
 static PencilEngineBinder binder=new PencilEngineBinder(new PackageManager(){public int checkSignatures(int a,int b){return trusted?0:-1;} public String[] getPackagesForUid(int u){return new String[]{"com.example.notes"};}},new PencilEngineBinder.Backend(){ public boolean supports(int f){return supported;} public boolean apply(PencilEngineRegistry.Caller c,long l,PencilEngineRegistry.Action a){effects++;lease=l;return accept;} });
 static Parcel data(){Parcel p=Parcel.obtain();p.writeInterfaceToken(PencilEngineProtocol.DESCRIPTOR);return p;}
 static void register(Token t,String pkg)throws Exception{Parcel p=data();p.writeStrongBinder(t);p.writeString(pkg);binder.transact(4,p,Parcel.obtain(),0);}
 static int trigger(int f,int v)throws Exception{Parcel p=data(),r=Parcel.obtain();p.writeInt(f);p.writeInt(v);binder.transact(3,p,r,0);return r.readInt();}
 static void check(boolean b){checks++;if(!b)throw new AssertionError(checks);}
 public static void main(String[] args)throws Exception{
 trusted=false;try{register(new Token(),"com.example.notes");throw new AssertionError();}catch(SecurityException ok){checks++;} trusted=true;
 try{register(new Token(),"com.attacker.notes");throw new AssertionError();}catch(SecurityException ok){checks++;}
 Token first=new Token();register(first,"com.example.notes"); supported=false;check(trigger(1,1)==-1);check(effects==0);supported=true;check(trigger(1,1)==0);check(effects==2);long old=lease;
 first.die();check(effects==4);check(trigger(1,1)==-1);
 Token second=new Token();register(second,"com.example.notes");check(trigger(1,1)==0);check(lease!=old);first.death.binderDied();check(trigger(1,0)==0);
 accept=false;check(trigger(1,1)==-1);accept=true;check(trigger(1,1)==-1);
 register(new Token(),"com.example.notes");check(trigger(99,0)==-1);
 check(trigger(1,1)==0); int beforeClose=effects; binder.close();check(effects==beforeClose+2);
 check(trigger(1,1)==-1);binder.close();check(effects==beforeClose+2);
 try{register(new Token(),"com.example.notes");throw new AssertionError();}catch(IllegalStateException ok){checks++;}
 System.out.println(checks+" Binder transport fixture checks passed; not Android runtime proof");
 }
}
