/* SPDX-License-Identifier: Apache-2.0 */
package org.lineageos.pianoparts.stylus;

/** Piano profile from HyperOS .44 BluetoothExtension and its device overlay. */
final class MippProtocol {
    private MippProtocol() {}

    static byte[] parameters(byte[] localAddress, int id, boolean second) {
        if (localAddress.length != 6) throw new IllegalArgumentException("Bluetooth address");
        byte[] result = new byte[32];
        result[0] = 0x30;
        result[1] = 0x1e;
        System.arraycopy(localAddress, 0, result, 2, 6);
        result[8] = 3;
        result[9] = (byte) id;
        byte[] basic = {(byte) 0xa0, 0x1e, 0x3e, 0x17, 0x1a, 4, 0x1a};
        System.arraycopy(basic, 0, result, 10, 7);
        result[17] = (byte) (second ? 1 : 3);
        byte[] sequence = {0, 0, 0x0d, 0x68, 0, 0, 5, 0x28, 0, 0, 0x0d, 0x68};
        System.arraycopy(sequence, 0, result, 18, 12);
        result[30] = (byte) 0xf4;
        result[31] = 1;
        return result;
    }

    static byte[] frequency(int id, int index) {
        byte[][] frequencies = {
            {(byte) 0x9b, 0x6f, (byte) 0xef, 0x43},
            {(byte) 0xe9, 0x5b, (byte) 0xcd, 0x37},
            {0x40, (byte) 0x9c, (byte) 0xdd, 0x2c},
            {0x20, 0x4e, (byte) 0xf7, 0x21},
        };
        if (index < 0 || index >= frequencies.length) {
            throw new IllegalArgumentException("Pen frequency");
        }
        byte[] result = {0x31, 8, (byte) id, 3, 0, 0, 0, 0,
                (byte) (index == 0 ? 40 : 60), 0};
        System.arraycopy(frequencies[index], 0, result, 4, 4);
        return result;
    }

    static byte[] voltage(int id) {
        return new byte[] {0x32, 3, (byte) id, 40, 0};
    }

    static byte[] timestamp(long seconds) {
        return new byte[] {0x33, 4, (byte) (seconds >>> 24), (byte) (seconds >>> 16),
                (byte) (seconds >>> 8), (byte) seconds};
    }

    static boolean validReply(byte[] reply, int opcode, int payloadLength) {
        return reply.length >= payloadLength + 2 && (reply[0] & 255) == opcode
                && (reply[1] & 255) == payloadLength;
    }

    static byte[] pressure(int down, int up) {
        if (down < 0 || down > 900 || up < 0 || up > 900) {
            throw new IllegalArgumentException("Squeeze pressure");
        }
        if (down == 0) up = 0;
        return new byte[] {0x5c, 4, (byte) (down >>> 8), (byte) down,
                (byte) (up >>> 8), (byte) up};
    }
}
