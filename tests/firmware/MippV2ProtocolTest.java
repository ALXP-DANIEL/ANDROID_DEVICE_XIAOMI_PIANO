/* SPDX-License-Identifier: Apache-2.0 */
import java.util.Arrays;
import java.nio.charset.StandardCharsets;
import org.lineageos.pianoparts.stylus.firmware.MippV2Protocol;

public final class MippV2ProtocolTest {
    private static int checks;
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("Check " + checks); }
    private static void reject(Runnable operation) {
        try { operation.run(); } catch (IllegalArgumentException expected) { checks++; return; }
        throw new AssertionError("Expected rejection");
    }
    private static String hex(byte[] data) {
        StringBuilder s = new StringBuilder(); for (byte b : data) s.append(String.format("%02x", b & 255));
        return s.toString();
    }
    public static void main(String[] args) throws Exception {
        check(hex(MippV2Protocol.query()).equals("a500010000a6"));
        check(hex(MippV2Protocol.finish()).equals("a500030000a8"));
        check(hex(MippV2Protocol.begin(0x020304, 258)).equals("a500020006000203040102b9"));
        check(MippV2Protocol.crc16("123456789".getBytes(StandardCharsets.US_ASCII)) == 0x4b37);
        check(!MippV2Protocol.supports(34, 12929));
        check(!MippV2Protocol.supports(34, 20609));
        check(!MippV2Protocol.supports(1, 20867));
        for (int pid : new int[]{20099,12675,12931,20356,20867,20868,20869,20870}) check(MippV2Protocol.supports(34,pid));
        for (int code = 0; code <= 6; code++) {
            int size = 16 << code;
            byte[] input = new byte[size + 1]; Arrays.fill(input, (byte) 0x42);
            MippV2Protocol.Plan plan = new MippV2Protocol.Plan(input, code);
            input[0] = 9;
            check(plan.blockSize == size && plan.packetCount == 3);
            byte[] first = MippV2Protocol.parse(plan.packet(1)).payload();
            check(first.length == size + 4 && first[4] == 0x42);
            byte[] tail = MippV2Protocol.parse(plan.packet(2)).payload();
            check(tail[4] == 0x42 && tail[5] == 0 && tail[tail.length - 1] == 0);
            byte[] end = MippV2Protocol.parse(plan.packet(3)).payload();
            check(end.length == 260 && hex(Arrays.copyOfRange(end,4,13)).equals("fefe4445425945fefe"));
            check(end[end.length - 1] == (byte) 0xff);
            reject(() -> plan.packet(0)); reject(() -> plan.packet(4));
            check(new MippV2Protocol.Plan(new byte[size],code).packetCount == 2);
        }
        byte[] frame = MippV2Protocol.data(65535, new byte[16]);
        check(MippV2Protocol.parse(frame).payload()[0] == (byte) 0xff);
        byte[] copy = MippV2Protocol.parse(frame).payload(); copy[0] = 0;
        check(MippV2Protocol.parse(frame).payload()[0] == (byte) 0xff);
        for (int length = 0; length < 6; length++) { final int n=length; reject(() -> MippV2Protocol.parse(new byte[n])); }
        byte[] corrupt = frame.clone(); corrupt[0] = 0; reject(() -> MippV2Protocol.parse(corrupt));
        byte[] wrongSize = frame.clone(); wrongSize[4]++; reject(() -> MippV2Protocol.parse(wrongSize));
        byte[] wrongSum = frame.clone(); wrongSum[5]++; reject(() -> MippV2Protocol.parse(wrongSum));
        reject(() -> MippV2Protocol.parse(null));
        reject(() -> MippV2Protocol.data(0,new byte[16]));
        reject(() -> MippV2Protocol.data(65536,new byte[16]));
        reject(() -> MippV2Protocol.data(1,new byte[17]));
        reject(() -> new MippV2Protocol.Plan(new byte[0],0));
        reject(() -> new MippV2Protocol.Plan(new byte[1],7));
        reject(() -> new MippV2Protocol.Plan(new byte[16*65535],0));
        if (args.length > 0) compareStock();
        System.out.println(checks + " V2 codec/planning checks passed; no transfer or hardware tested");
    }
    private static void compareStock() throws Exception {
        Class<?> type=Class.forName("com.android.bluetooth.ble.app.oobhelper.c");
        Object stock=type.getConstructor().newInstance();
        check(Arrays.equals((byte[])type.getMethod("d").invoke(stock),MippV2Protocol.query()));
        check(Arrays.equals((byte[])type.getMethod("c").invoke(stock),MippV2Protocol.finish()));
        check(Arrays.equals((byte[])type.getMethod("f",int.class,int.class).invoke(stock,1000,8),MippV2Protocol.begin(1000,8)));
        java.util.Random random=new java.util.Random(44);
        for (int size=16;size<=1024;size*=2) for(int sequence:new int[]{1,255,256,65535}) {
            byte[] block=new byte[size];random.nextBytes(block);
            byte[] expected=(byte[])type.getMethod("e",int.class,byte[].class).invoke(stock,sequence,block);
            check(Arrays.equals(expected,MippV2Protocol.data(sequence,block)));
        }
    }
}
