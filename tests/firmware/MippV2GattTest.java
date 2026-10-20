/* SPDX-License-Identifier: Apache-2.0 */
import android.bluetooth.*;
import android.os.Handler;
import java.security.MessageDigest;
import org.lineageos.pianoparts.stylus.firmware.*;

public final class MippV2GattTest {
    static int checks;
    static void check(boolean ok){checks++;if(!ok)throw new AssertionError("Check "+checks);}
    static final class Run implements PenFirmwareGatt.Listener {
        final BluetoothGatt g=new BluetoothGatt(); final Handler h=new Handler();
        final MippV2Gatt updater; PenFirmwareGatt.State state; int events,progress;
        Run() throws Exception {
            byte[] raw=new byte[17];
            String hash=java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));
            FirmwareImage image=FirmwareImage.validate(new FirmwareImage.Manifest(34,20867,FirmwareImage.PROFILE_V2,"1.2.3",hash),raw,34,20867);
            g.service.chars.put(MippV2Protocol.WRITE,new BluetoothGattCharacteristic());
            g.service.chars.put(MippV2Protocol.NOTIFY,new BluetoothGattCharacteristic());
            updater=new MippV2Gatt(g,h,image,this);
        }
        public void progress(int p){progress=p;}
        public void finished(PenFirmwareGatt.State s,String why){state=s;events++;}
        void prepare(){updater.prepare(34,20867,90,true);updater.onDescriptorWrite(MippV2Protocol.NOTIFY,0);}
        void sent(int op){check(MippV2Protocol.parse(g.writes.remove(0)).opcode==op);updater.onCharacteristicWrite(MippV2Protocol.WRITE,0);}
        void reply(int op,int...data){updater.onNotification(MippV2Protocol.NOTIFY,replyBytes(op,data));}
        void begin(int size){prepare();check(updater.isPrepared()&&g.writes.isEmpty());updater.start();check(g.writes.isEmpty());h.runDelay(1000);sent(1);reply(1,1,0,0,1,size);check(g.writes.isEmpty());h.runDelay(100);sent(2);reply(2,0);}
    }
    static byte[] replyBytes(int op,int...payload){
        byte[] b=new byte[payload.length+6];b[0]=(byte)165;b[1]=1;b[2]=(byte)op;b[4]=(byte)payload.length;
        for(int i=0;i<payload.length;i++)b[i+5]=(byte)payload[i];int sum=0;for(int i=0;i<b.length-1;i++)sum+=b[i]&255;b[b.length-1]=(byte)sum;return b;
    }
    public static void main(String[] args) throws Exception {
        Run r=new Run();r.begin(5);check(r.g.writes.size()==1&&r.g.writes.get(0).length==522); // Whole 512-byte stock block, no slicing.
        r.sent(5);r.reply(4,0,2,0);r.sent(4);r.reply(4,0,2,0);check(r.state==null);
        r.sent(3);check(r.state==PenFirmwareGatt.State.VERIFY_REVISION&&r.progress<100&&r.h.queue.isEmpty());
        r.updater.verifyRevision("1.2.3");check(r.state==PenFirmwareGatt.State.COMPLETE&&r.progress==100);
        r=new Run();r.g.reject=true;r.prepare();r.updater.start();r.h.runDelay(1000);check(r.state==PenFirmwareGatt.State.FAILED&&r.h.queue.isEmpty());
        r=new Run();r.g.service=null;r.updater.prepare(34,20867,90,true);check(r.state==PenFirmwareGatt.State.FAILED&&r.g.writes.isEmpty());
        r=new Run();r.g.service.chars.get(MippV2Protocol.NOTIFY).descriptor=false;r.updater.prepare(34,20867,90,true);check(r.state==PenFirmwareGatt.State.FAILED);
        r=new Run();r.updater.prepare(34,20867,90,true);r.updater.onDescriptorWrite(MippV2Protocol.NOTIFY,1);check(r.state==PenFirmwareGatt.State.FAILED&&r.h.queue.isEmpty());
        r=new Run();r.begin(0);r.updater.cancel();check(r.state==PenFirmwareGatt.State.CANCELLED&&r.h.queue.isEmpty());int n=r.events;r.updater.disconnected();check(r.events==n);
        r=new Run();r.prepare();r.updater.start();r.h.runDelay(15000);check(r.state==PenFirmwareGatt.State.FAILED&&r.h.queue.isEmpty());
        r=new Run();r.prepare();r.updater.start();r.updater.disconnected();r.h.runDelay(1000);check(r.state==PenFirmwareGatt.State.FAILED&&r.g.writes.isEmpty());
        System.out.println(checks+" V2 GATT boundary checks passed; fake Android transport, no firmware transferred");
    }
}
