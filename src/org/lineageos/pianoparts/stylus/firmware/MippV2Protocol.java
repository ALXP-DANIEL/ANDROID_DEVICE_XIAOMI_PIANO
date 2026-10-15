/* SPDX-License-Identifier: Apache-2.0 */
package org.lineageos.pianoparts.stylus.firmware;

import java.util.Arrays;
import java.util.UUID;

/**
 * Stock MIPP V2 wire codec. This class performs no I/O and establishes no image
 * authenticity. It is deliberately not connected to the production updater.
 * Layout: HyperOS .44 OOB c.a/e/f, b.a/c/d and r1.b.f; final block: OOB d.
 */
public final class MippV2Protocol {
    public static final UUID SERVICE = UUID.fromString("0000fed0-aa6c-462a-964a-7f2ed5b3e512");
    public static final UUID WRITE = UUID.fromString("0000fed1-aa6c-462a-964a-7f2ed5b3e512");
    public static final UUID NOTIFY = UUID.fromString("0000fed2-aa6c-462a-964a-7f2ed5b3e512");
    private static final int HEADER = 6;
    private static final int MAX_PAYLOAD = 1028;

    public static boolean supports(int vendor, int product) {
        if (vendor != 34) return false;
        switch (product) {
            case 20099: case 12675: case 12931: case 20356:
            case 20867: case 20868: case 20869: case 20870: return true;
            default: return false;
        }
    }

    public static byte[] query() { return frame(1, new byte[0]); }
    public static byte[] finish() { return frame(3, new byte[0]); }

    public static byte[] begin(int imageSize, int packetCount) {
        if (imageSize < 1 || imageSize > FirmwareImage.MAX_BYTES
                || packetCount < 2 || packetCount > 65535) {
            throw new IllegalArgumentException("Invalid image/count");
        }
        return frame(2, new byte[] {(byte) (imageSize >>> 24), (byte) (imageSize >>> 16),
                (byte) (imageSize >>> 8), (byte) imageSize,
                (byte) (packetCount >>> 8), (byte) packetCount});
    }

    public static byte[] data(int sequence, byte[] block) {
        if (sequence < 1 || sequence > 65535 || block == null || !validBlockSize(block.length)) {
            throw new IllegalArgumentException("Invalid sequence/block");
        }
        byte[] payload = new byte[block.length + 4];
        int crc = crc16(block);
        payload[0] = (byte) (sequence >>> 8); payload[1] = (byte) sequence;
        payload[2] = (byte) (crc >>> 8); payload[3] = (byte) crc;
        System.arraycopy(block, 0, payload, 4, block.length);
        return frame(block.length == 256 ? 4 : block.length == 512 ? 5 : 6, payload);
    }

    public static int blockSize(int code) {
        if (code < 0 || code > 6) throw new IllegalArgumentException("Unknown block-size code");
        return 16 << code;
    }

    private static boolean validBlockSize(int size) {
        return size >= 16 && size <= 1024 && (size & (size - 1)) == 0;
    }

    /** CRC-16/MODBUS numeric value; stock puts its high byte first on this wire. */
    public static int crc16(byte[] bytes) {
        if (bytes == null) throw new IllegalArgumentException("Missing CRC input");
        int crc = 65535;
        for (byte value : bytes) {
            crc ^= value & 255;
            for (int bit = 0; bit < 8; bit++) crc = (crc & 1) != 0 ? (crc >>> 1) ^ 0xa001 : crc >>> 1;
        }
        return crc;
    }

    private static byte[] frame(int opcode, byte[] payload) {
        byte[] result = new byte[HEADER + payload.length];
        result[0] = (byte) 0xa5; result[1] = 0; result[2] = (byte) opcode;
        result[3] = (byte) (payload.length >>> 8); result[4] = (byte) payload.length;
        System.arraycopy(payload, 0, result, 5, payload.length);
        result[result.length - 1] = checksum(result, result.length - 1);
        return result;
    }

    private static byte checksum(byte[] bytes, int count) {
        int sum = 0;
        for (int i = 0; i < count; i++) sum += bytes[i] & 255;
        return (byte) sum;
    }

    public static final class Reply {
        public final int direction, opcode;
        private final byte[] payload;
        private Reply(int direction, int opcode, byte[] payload) {
            this.direction = direction; this.opcode = opcode; this.payload = payload;
        }
        public byte[] payload() { return payload.clone(); }
    }

    /** Validate the entire frame before accessing any header or payload field. */
    public static Reply parse(byte[] frame) {
        if (frame == null || frame.length < HEADER || frame.length > HEADER + MAX_PAYLOAD
                || (frame[0] & 255) != 0xa5) throw new IllegalArgumentException("Invalid frame header/size");
        int length = ((frame[3] & 255) << 8) | (frame[4] & 255);
        if (length != frame.length - HEADER
                || checksum(frame, frame.length - 1) != frame[frame.length - 1]) {
            throw new IllegalArgumentException("Invalid frame length/checksum");
        }
        return new Reply(frame[1] & 255, frame[2] & 255,
                Arrays.copyOfRange(frame, 5, frame.length - 1));
    }

    /** Padded stock block plan; never allocates one array for every packet. */
    public static final class Plan {
        private final byte[] image;
        public final int blockSize, packetCount;
        public Plan(byte[] image, int sizeCode) {
            if (image == null || image.length < 1 || image.length > FirmwareImage.MAX_BYTES) {
                throw new IllegalArgumentException("Invalid image size");
            }
            blockSize = blockSize(sizeCode);
            long count = ((long) image.length + blockSize - 1) / blockSize + 1;
            if (count > 65535) throw new IllegalArgumentException("Packet sequence overflow");
            packetCount = (int) count;
            this.image = image.clone();
        }
        public byte[] begin() { return MippV2Protocol.begin(image.length, packetCount); }
        public byte[] packet(int sequence) {
            if (sequence < 1 || sequence > packetCount) throw new IllegalArgumentException("Invalid sequence");
            if (sequence == packetCount) {
                byte[] end = new byte[256]; Arrays.fill(end, (byte) 0xff);
                byte[] marker = {(byte) 0xfe, (byte) 0xfe, 0x44, 0x45, 0x42, 0x59, 0x45,
                        (byte) 0xfe, (byte) 0xfe};
                System.arraycopy(marker, 0, end, 0, marker.length);
                return data(sequence, end);
            }
            byte[] block = new byte[blockSize];
            int offset = (sequence - 1) * blockSize;
            System.arraycopy(image, offset, block, 0, Math.min(blockSize, image.length - offset));
            return data(sequence, block);
        }
    }

    private MippV2Protocol() { }
}
