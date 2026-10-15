/* SPDX-License-Identifier: Apache-2.0 */
package org.lineageos.pianoparts.keyboard;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

public final class KeyboardMcuFirmwareTest {
    private static int checks = 0;
    private static void test(boolean ok) {
        checks++;
        if (!ok) throw new AssertionError("check " + checks);
    }
    private static void invalid(Runnable runnable) {
        try { runnable.run(); throw new AssertionError("accepted invalid input"); }
        catch (IllegalArgumentException expected) { checks++; }
    }
    private static int le24(byte[] b, int i) {
        return (b[i] & 255) | (b[i + 1] & 255) << 8 | (b[i + 2] & 255) << 16;
    }
    private static void packet(byte[] data, int command, int len, int checksum) {
        test(data.length == 68);
        test((data[0] & 255) == 0xaa && data[1] == 0x42 &&
             data[2] == 0x32 && data[4] == 0x4f && data[5] == 0x30);
        test((data[7] & 255) == 0x18 && (data[8] & 255) == command);
        test((data[9] & 255) == len);
        test(KeyboardProtocol.sum(data, 4, checksum - 4) == data[checksum]);
    }
    public static void main(String[] args) throws Exception {
        Path stock = Path.of(args[0]);
        KeyboardMcuFirmware fw = KeyboardMcuFirmware.inspect(stock);
        test(fw.payloadLength() == 50668);
        test(fw.chunkCount() == 976);
        test(!fw.isCandidateNewerThan("XM_P81_V206-0828"));
        test(!fw.isCandidateNewerThan(null));
        test(!fw.isCandidateNewerThan("invalid"));
        test(fw.isCandidateNewerThan("XM_P81_V206-0827"));
        byte[] info = fw.infoPacket(2026, 10, 15);
        packet(info, 0x02, 25, 35);
        test((info[30] & 255) == 1 && (info[31] & 255) == 0x18);
        test((info[32] & 255) == (2026 & 255) &&
             (info[33] & 255) == 10 && (info[34] & 255) == 15);
        test((info[23] & 255) == 0x80 && info[26] == 0x06 && info[27] == 0x32 &&
             info[28] == (byte) 0x94 && info[29] == 0x12);
        byte[] first = fw.chunkPacket(0);
        packet(first, 0x11, 0x38, 66);
        test(le24(first, 10) == 0);
        test(Arrays.equals(Arrays.copyOfRange(first, 14, 21), "NANO IC".getBytes()));
        byte[] last = fw.chunkPacket(975);
        packet(last, 0x11, 0x38, 66);
        test(le24(last, 10) == 50700 && (last[13] & 255) == 52);
        byte[] blob = Files.readAllBytes(stock);
        test(Arrays.equals(Arrays.copyOfRange(last, 14, 46),
                Arrays.copyOfRange(blob, 32704 + 50700, blob.length)));
        test(Arrays.equals(Arrays.copyOfRange(last, 46, 66), new byte[20]));
        invalid(() -> fw.chunkPacket(-1));
        invalid(() -> fw.chunkPacket(fw.chunkCount()));
        invalid(() -> fw.infoPacket(1990, 1, 1));
        byte[] end = fw.endPacket(); packet(end, 0x04, 21, 31);
        test((end[30] & 255) == 0x18 && (end[23] & 255) == 0x80);
        packet(fw.flashPacket(), 0x06, 4, 14);
        byte[] reset = fw.resetPacket(); packet(reset, 0x03, 4, 14);
        test((reset[10] & 255) == 0x57 && (reset[11] & 255) == 0x4e &&
             (reset[12] & 255) == 0x38 && (reset[13] & 255) == 0x30);
        byte[] altered = blob.clone(); altered[altered.length - 1] ^= 1;
        Path temporary = Files.createTempFile("mcu-invalid-", ".bin");
        try {
            Files.write(temporary, altered);
            try { KeyboardMcuFirmware.inspect(temporary); throw new AssertionError("Accepted tampered MCU file"); }
            catch (java.io.IOException expected) { checks++; }
        } finally { Files.deleteIfExists(temporary); }
        System.out.println("PASS " + checks + " pinned MCU parser and packet-preview checks; no transport used");
    }
}
