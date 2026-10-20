/* SPDX-License-Identifier: Apache-2.0 */
import java.util.ArrayDeque;
import org.lineageos.pianoparts.stylus.firmware.MippV2Protocol;
import org.lineageos.pianoparts.stylus.firmware.MippV2Session;

public final class MippV2SessionTest {
    static int checks;
    static void check(boolean condition) { checks++; if (!condition) throw new AssertionError("Check " + checks); }
    static byte[] reply(int op, int... payload) {
        byte[] bytes = new byte[payload.length + 6];
        bytes[0]=(byte)0xa5; bytes[1]=1; bytes[2]=(byte)op;
        bytes[3]=(byte)(payload.length>>>8);bytes[4]=(byte)payload.length;
        for(int i=0;i<payload.length;i++)bytes[i+5]=(byte)payload[i];
        int sum=0;for(int i=0;i<bytes.length-1;i++)sum+=bytes[i]&255;
        bytes[bytes.length-1]=(byte)sum;return bytes;
    }
    static final class Run implements MippV2Session.Listener {
        final ArrayDeque<byte[]> frames=new ArrayDeque<>();
        final MippV2Session session=new MippV2Session(34,20867,new byte[17],"1.2.3",frames::add,this);
        int lastProgress=-1,completions;
        public void progress(int value){lastProgress=value;}
        public void finished(MippV2Session.State state,String reason){completions++;}
        void sent(int opcode){check(MippV2Protocol.parse(frames.remove()).opcode==opcode);session.onWrite(true);}
        void initialize(){session.start();sent(1);session.onReply(reply(1,1,0,0,1,0));sent(2);session.onReply(reply(2,0));}
    }
    public static void main(String[] args) {
        Run r=new Run();r.initialize();r.sent(6);r.session.onReply(reply(4,0,2,0));
        r.sent(6);r.session.onReply(reply(4,0,3,0));r.sent(4);r.session.onReply(reply(4,0,3,0));
        check(r.session.state()==MippV2Session.State.END && r.lastProgress<100);
        r.sent(3);r.session.onReply(reply(3,0));
        check(r.session.state()==MippV2Session.State.VERIFY_REVISION && r.lastProgress<100);
        r.session.verifyRevision("1.2.3");check(r.session.state()==MippV2Session.State.COMPLETE && r.lastProgress==100);
        int events=r.completions;r.session.timeout();r.session.cancel();check(r.completions==events);
        r=new Run();r.initialize();r.sent(6);r.session.onReply(reply(4,0,2,0));r.sent(6);r.session.onReply(reply(4,0,3,0));r.sent(4);r.session.onReply(reply(4,0,3,0));r.sent(3);r.session.onReply(reply(3,0));
        r.session.verifyRevision("1.2.2");check(r.session.state()==MippV2Session.State.FAILED && r.lastProgress<100);
        // Notification/write callback ordering must not create overlapping writes.
        r=new Run();r.session.start();r.frames.remove();r.session.onReply(reply(1,1,0,0,1,0));
        check(r.frames.isEmpty());r.session.onWrite(true);check(r.frames.size()==1);
        r=new Run();r.session.start();r.session.onReply(reply(1,1,0,0,1,0));r.session.onWrite(false);
        check(r.session.state()==MippV2Session.State.FAILED);
        r=new Run();r.session.start();r.session.onReply(reply(1,1,0,0,1,0));r.session.onReply(reply(1,1,0,0,1,0));check(r.session.state()==MippV2Session.State.FAILED);
        for(int code:new int[]{7,255}){r=new Run();r.session.start();r.sent(1);r.session.onReply(reply(1,1,0,0,1,code));check(r.session.state()==MippV2Session.State.FAILED);}
        for(int bank:new int[]{0,4}){r=new Run();r.session.start();r.sent(1);r.session.onReply(reply(1,1,0,0,bank,0));check(r.session.state()==MippV2Session.State.FAILED);}
        r=new Run();r.initialize();r.sent(6);r.session.onReply(reply(4,0,1,1));check(r.session.state()==MippV2Session.State.FAILED);
        r=new Run();r.initialize();r.sent(6);r.session.onReply(reply(4,0,3,0));check(r.session.state()==MippV2Session.State.FAILED);
        r=new Run();r.initialize();r.sent(6);r.session.onReply(reply(4,0,0,0));check(r.session.state()==MippV2Session.State.FAILED);
        r=new Run();r.initialize();r.sent(6);r.session.onReply(reply(4,0,1,2));check(r.frames.size()==1);r.sent(6);
        r.session.cancel();check(r.session.state()==MippV2Session.State.CANCELLED);r.session.onReply(reply(4,0,2,0));check(r.frames.isEmpty());
        r=new Run();r.initialize();
        for(int i=0;i<40 && r.session.state()==MippV2Session.State.DATA;i++){r.sent(6);r.session.onReply(reply(4,0,1,2));}
        check(r.session.state()==MippV2Session.State.FAILED);
        r=new Run();r.session.start();r.session.onReply(new byte[0]);check(r.session.state()==MippV2Session.State.FAILED);
        r=new Run();r.initialize();r.session.timeout();check(r.session.state()==MippV2Session.State.FAILED);
        r=new Run();r.session.start();r.sent(1);r.session.onReply(reply(1,1,0,0,1,0));r.sent(2);r.session.onReply(reply(2,1));check(r.session.state()==MippV2Session.State.FAILED);
        r=new Run();r.initialize();r.sent(6);r.session.onReply(reply(4,0,2,0));r.sent(6);r.session.onReply(reply(4,0,3,0));r.sent(4);r.session.onReply(reply(4,0,3,0));check(MippV2Protocol.parse(r.frames.remove()).opcode==3);r.session.onReply(reply(3,1));r.session.onWrite(true);check(r.session.state()==MippV2Session.State.FAILED);
        // An early final-block reply must not complete before the end write callback.
        r=new Run();r.initialize();r.sent(6);r.session.onReply(reply(4,0,2,0));
        r.sent(6);r.session.onReply(reply(4,0,3,0));
        check(MippV2Protocol.parse(r.frames.remove()).opcode==4);
        r.session.onReply(reply(4,0,3,0));r.session.onWrite(true);
        check(r.session.state()==MippV2Session.State.END && r.completions==0);
        r.sent(3);check(r.session.state()==MippV2Session.State.VERIFY_REVISION);
        System.out.println(checks+" V2 session checks passed; no GATT/hardware validation");
    }
}
