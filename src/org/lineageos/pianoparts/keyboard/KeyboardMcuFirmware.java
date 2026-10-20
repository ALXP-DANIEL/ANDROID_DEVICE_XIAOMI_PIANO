/* SPDX-License-Identifier: Apache-2.0 */
package org.lineageos.pianoparts.keyboard;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * Exact HyperOS .44 connector MCU image parser and packet planner.
 *
 * <p>This value object performs no I/O to a device. Only an explicit manual
 * KeyboardMcuUpdater session may send packets, with authenticated connection
 * guards, ACK validation and mandatory post-reset version verification.
 */
final class KeyboardMcuFirmware {
    static final int MCU_VID = 0x3206;
    static final int MCU_PID = 0x1294;
    static final String VERSION = "XM_P81_V206-0828";
    static final int HEADER_OFFSET = 32704;
    static final int PAYLOAD_OFFSET = 32768;
    static final int IMAGE_SIZE = 83436;
    static final int CHUNK = 52;
    static final String STOCK_SHA256 =
            "c03d6b650cb85a983359c6686d831875df10ee1206fd31afc1403baefeb7e407";
    private static final byte[] MAGIC = "NANO IC".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    private final byte[] image;
    private final int payloadLength;

    private KeyboardMcuFirmware(byte[] image, int payloadLength) {
        this.image = image;
        this.payloadLength = payloadLength;
    }

    static KeyboardMcuFirmware inspect(Path path) throws IOException {
        if (path == null || !Files.isRegularFile(path) || Files.size(path) != IMAGE_SIZE) {
            throw new IOException("Missing or unsupported MCU firmware image");
        }
        byte[] image = Files.readAllBytes(path);
        if (image.length != IMAGE_SIZE || !Arrays.equals(
                Arrays.copyOfRange(image, HEADER_OFFSET, HEADER_OFFSET + MAGIC.length), MAGIC)) {
            throw new IOException("MCU image header/length mismatch");
        }
        if (!STOCK_SHA256.equals(hex(sha256(image)))) {
            throw new IOException("MCU image differs from pinned stock firmware");
        }
        int length = le32(image, 32752);
        int checksum = le32(image, 32756);
        if (length <= 0 || length != image.length - PAYLOAD_OFFSET ||
                length + 64 > 0xFFFFFF) {
            throw new IOException("MCU image payload length invalid");
        }
        if (le16(image, 32729) != MCU_PID || le16(image, 32731) != MCU_VID) {
            throw new IOException("MCU target identity mismatch");
        }
        byte[] version = Arrays.copyOfRange(image, 32735, 32751);
        if (!VERSION.equals(new String(version, java.nio.charset.StandardCharsets.US_ASCII))) {
            throw new IOException("MCU target revision mismatch");
        }
        long sum = 0;
        for (int i = PAYLOAD_OFFSET; i < image.length; i++) sum += image[i] & 0xFF;
        if (sum != (checksum & 0xFFFFFFFFL)) {
            throw new IOException("MCU payload checksum mismatch");
        }
        if (KeyboardProtocol.sum(image, HEADER_OFFSET, 63) != image[HEADER_OFFSET + 63]) {
            throw new IOException("MCU header checksum mismatch");
        }
        return new KeyboardMcuFirmware(image, length);
    }

    String version() { return VERSION; }
    int payloadLength() { return payloadLength; }

    /** The transmitted blob includes the 64-byte stock upgrade header. */
    int chunkCount() { return (payloadLength + 64 + CHUNK - 1) / CHUNK; }

    /** No updater may treat an unknown or equal MCU revision as newer. */
    boolean isCandidateNewerThan(String connectedVersion) {
        if (connectedVersion == null || !connectedVersion.startsWith("XM_P81_V206-")) {
            return false;
        }
        String current = connectedVersion.substring("XM_P81_V206-".length());
        if (!current.matches("[0-9]{4}")) return false;
        return Integer.parseInt(VERSION.substring(VERSION.length() - 4)) > Integer.parseInt(current);
    }

    /** Packet formation is independent of device transport. */
    byte[] infoPacket(int year, int month, int day) {
        if (year < 2020 || year > 2100 || month < 1 || month > 12 || day < 1 || day > 31) {
            throw new IllegalArgumentException("Invalid date");
        }
        byte[] p = packet(KeyboardProtocol.CMD_UPGRADE_INFO, 25);
        System.arraycopy(image, 32752, p, 10, 4); // length
        System.arraycopy(image, 32756, p, 18, 4); // checksum
        p[23] = (byte) 0x80; // flash address 0x00008000
        p[26] = image[32731]; p[27] = image[32732]; // VID
        p[28] = image[32729]; p[29] = image[32730]; // PID
        p[30] = 1; p[31] = KeyboardProtocol.ADDRESS_MCU;
        // Stock uses Calendar.YEAR as a full integer, then truncates to a byte.
        p[32] = (byte) year; p[33] = (byte) month; p[34] = (byte) day;
        KeyboardProtocol.seal(p, 35);
        return p;
    }

    byte[] chunkPacket(int index) {
        if (index < 0 || index >= chunkCount()) throw new IllegalArgumentException("Invalid chunk");
        int offset = index * CHUNK;
        byte[] p = packet(KeyboardProtocol.CMD_UPGRADE_DATA, 0x38);
        // MiuiKeyboardUtil.int2Bytes is big-endian; stock sends its low three bytes.
        p[10] = (byte) offset; p[11] = (byte) (offset >>> 8);
        p[12] = (byte) (offset >>> 16);
        p[13] = CHUNK;
        int amount = Math.min(CHUNK, payloadLength + 64 - offset);
        System.arraycopy(image, HEADER_OFFSET + offset, p, 14, amount);
        KeyboardProtocol.seal(p, 66);
        return p;
    }

    byte[] endPacket() {
        byte[] p = packet(KeyboardProtocol.CMD_UPGRADE_END, 21);
        System.arraycopy(image, 32752, p, 10, 4);
        System.arraycopy(image, 32756, p, 18, 4);
        p[23] = (byte) 0x80;
        p[26] = image[32731]; p[27] = image[32732];
        p[28] = image[32729]; p[29] = image[32730];
        p[30] = KeyboardProtocol.ADDRESS_MCU;
        KeyboardProtocol.seal(p, 31);
        return p;
    }

    byte[] flashPacket() {
        byte[] p = packet(KeyboardProtocol.CMD_UPGRADE_FLASH, 4);
        System.arraycopy(image, 32752, p, 10, 4);
        KeyboardProtocol.seal(p, 14);
        return p;
    }

    byte[] resetPacket() {
        byte[] p = packet((byte) 0x03, 4);
        p[10] = 0x57; p[11] = KeyboardProtocol.REPORT_SHORT;
        p[12] = KeyboardProtocol.ADDRESS_KEYBOARD; p[13] = 0x30;
        KeyboardProtocol.seal(p, 14);
        return p;
    }

    private static byte[] packet(byte command, int length) {
        return KeyboardProtocol.newPacket(KeyboardProtocol.REPORT_LONG,
                KeyboardProtocol.VERSION_UPGRADE, KeyboardProtocol.ADDRESS_MCU, command, length);
    }

    private static int le16(byte[] image, int offset) {
        return (image[offset] & 255) | (image[offset + 1] & 255) << 8;
    }

    private static int le32(byte[] image, int offset) {
        return le16(image, offset) | le16(image, offset + 2) << 16;
    }

    private static byte[] sha256(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    private static String hex(byte[] bytes) {
        StringBuilder b = new StringBuilder(bytes.length * 2);
        for (byte x : bytes) b.append(String.format("%02x", x & 255));
        return b.toString();
    }
}
