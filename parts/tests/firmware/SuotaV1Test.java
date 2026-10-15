package org.lineageos.pianoparts.stylus.firmware;
import java.security.MessageDigest;
import java.util.*;

public final class SuotaV1Test {
    static void check(boolean value) { if (!value) throw new AssertionError(); }
    static FirmwareImage image(byte[] bytes) throws Exception {
        StringBuilder digest = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) digest.append(String.format("%02x", b & 255));
        return FirmwareImage.validate(new FirmwareImage.Manifest(34,20609,FirmwareImage.PROFILE,"1.2.3",digest.toString()),bytes,34,20609);
    }
    static final class Write {
        UUID uuid; byte[] bytes; boolean noResponse;
        Write(UUID u,byte[] b,boolean n){uuid=u;bytes=b;noResponse=n;}
    }
    public static void main(String[] args) throws Exception {
        byte[] original = new byte[350]; for(int i=0;i<original.length;i++) original[i]=(byte)i;
        FirmwareImage image=image(original);
        byte[] transfer=image.transferBytes();byte xor=0;for(byte b:original)xor^=b;check(transfer[350]==xor);
        original[0]=100; check(image.transferBytes()[0]==0);
        try { FirmwareImage.validate(image.manifest,original,34,20609);throw new AssertionError(); }catch(IllegalArgumentException expected){}
        try { FirmwareImage.validate(image.manifest,new byte[]{1},34,12929);throw new AssertionError(); }catch(IllegalArgumentException expected){}
        ArrayDeque<Write> writes=new ArrayDeque<>(); List<SuotaV1.State> states=new ArrayList<>(); List<Integer> progress=new ArrayList<>();
        SuotaV1.Listener listener=new SuotaV1.Listener(){public void progress(int p){progress.add(p);}public void finished(SuotaV1.State s,String r){states.add(s);}};
        SuotaV1 session=new SuotaV1(image,20,23,(u,b,n)->writes.add(new Write(u,b,n)),listener);
        session.start(); Write w=writes.remove();check(w.uuid.equals(SuotaV1.MEMORY));check(Arrays.equals(w.bytes,new byte[]{0,0,0,0x13}));
        session.onStatus(new byte[]{16});check(writes.isEmpty());session.onWrite(w.uuid,true);
        w=writes.remove();check(w.uuid.equals(SuotaV1.GPIO));check(Arrays.equals(w.bytes,new byte[]{0,3,6,5}));session.onWrite(w.uuid,true);
        List<Byte> sent=new ArrayList<>();int blocks=0;
        while(session.state()!=SuotaV1.State.VERIFY_REVISION){
            w=writes.poll();
            if(w==null){check(session.state()==SuotaV1.State.BLOCK_ACK);session.onStatus(new byte[]{2});blocks++;continue;}
            if(w.uuid.equals(SuotaV1.DATA)){check(w.noResponse&&w.bytes.length<=20);for(byte b:w.bytes)sent.add(b);}
            if(session.state()==SuotaV1.State.END){check(Arrays.equals(w.bytes,new byte[]{0,0,0,(byte)0xfe}));session.onStatus(new byte[]{2});}
            session.onWrite(w.uuid,true);
        }
        check(blocks==3&&sent.size()==351&&progress.get(progress.size()-1)==100);
        for(int i=0;i<transfer.length;i++)check(sent.get(i)==transfer[i]);
        check(states.equals(List.of(SuotaV1.State.VERIFY_REVISION)));
        session.verifyRevision("1.2.3");check(session.state()==SuotaV1.State.COMPLETE);
        SuotaV1 bad=new SuotaV1(image,20,23,(u,b,n)->{},listener);bad.start();bad.onStatus(new byte[]{7});check(bad.state()==SuotaV1.State.FAILED);
        SuotaV1 cancelled=new SuotaV1(image,20,23,(u,b,n)->{},listener);cancelled.start();cancelled.cancel();cancelled.onStatus(new byte[]{16});check(cancelled.state()==SuotaV1.State.CANCELLED);
        SuotaV1 timed=new SuotaV1(image,20,23,(u,b,n)->{},listener);timed.start();timed.timeout();check(timed.state()==SuotaV1.State.FAILED);
        System.out.println("SUOTA profile/model/digest/chunks/ACK-order/revision/error/cancel/timeout passed");
    }
}
