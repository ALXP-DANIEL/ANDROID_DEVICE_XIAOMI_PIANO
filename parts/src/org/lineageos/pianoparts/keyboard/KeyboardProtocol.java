/*
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pianoparts.keyboard;

/**
 * Packet format of the pogo pin keyboard, as used by the stock HyperOS
 * keyboard service over the keyboard nanoapp HAL.
 *
 * Every packet is 68 bytes: a fixed head (AA 42 32 00), then a report id,
 * protocol version, source and target addresses, a command, the payload
 * length, the payload and a checksum (byte sum starting at the report id).
 * Replies arrive without the head, starting at the report id.
 */
final class KeyboardProtocol {

    static final int PACKET_SIZE = 68;

    // Report ids.
    static final byte REPORT_SHORT = 0x4E;
    static final byte REPORT_LONG = 0x4F;

    // Protocol versions: 0x30 for upgrade and version requests, 0x31 for features.
    static final byte VERSION_UPGRADE = 0x30;
    static final byte VERSION_FEATURE = 0x31;

    // Addresses.
    static final byte ADDRESS_PAD = (byte) 0x80;
    static final byte ADDRESS_MCU = 0x18;
    static final byte ADDRESS_KEYBOARD = 0x38;
    static final byte ADDRESS_TOUCHPAD = 0x40;

    // Commands.
    static final byte CMD_AUTH_START = 0x31;
    static final byte CMD_AUTH_STEP3 = 0x32;
    static final byte CMD_AUTH_STEP5 = 0x33;
    static final byte CMD_AUTH_LAST = 0x35;
    static final byte CMD_GET_VERSION = 0x01;
    static final byte CMD_UPGRADE_INFO = 0x02;
    static final byte CMD_RECOVER_STATUS = 0x20;
    static final byte CMD_UPGRADE_END = 0x04;
    static final byte CMD_UPGRADE_FLASH = 0x06;
    static final byte CMD_UPGRADE_DATA = 0x11;
    static final byte CMD_UPGRADE_DATA_ACK = (byte) 0x91;
    static final byte CMD_TOUCHPAD_ENABLE = 0x21;
    static final byte CMD_BACKLIGHT = 0x23;
    static final byte CMD_REQUEST = 0x24;
    static final byte CMD_POWER = 0x25;
    static final byte CMD_TIPS_LIGHT_2022 = 0x26;
    static final byte CMD_SLEEP = 0x28;
    static final byte CMD_TIPS_LIGHT = 0x2E;
    static final byte CMD_MAC_ADDRESS = 0x52;
    static final byte CMD_G_SENSOR = 0x64;
    static final byte CMD_CHECK_MCU_STATUS = (byte) 0xA1;
    static final byte CMD_MCU_STATUS = (byte) 0xA2;
    static final byte CMD_FEATURE_RESPONSE = (byte) 0xF0;

    // Tips light values (caps lock).
    static final byte CAPS_ON = (byte) 0xFD;
    static final byte CAPS_OFF = (byte) 0xFC;

    private KeyboardProtocol() {}

    static byte[] newPacket(byte report, byte version, byte target, byte command, int length) {
        byte[] packet = new byte[PACKET_SIZE];
        packet[0] = (byte) 0xAA;
        packet[1] = 0x42;
        packet[2] = 0x32;
        packet[3] = 0x00;
        packet[4] = report;
        packet[5] = version;
        packet[6] = ADDRESS_PAD;
        packet[7] = target;
        packet[8] = command;
        packet[9] = (byte) length;
        return packet;
    }

    /** Stores the checksum of bytes 4 .. index - 1 at index. */
    static void seal(byte[] packet, int index) {
        packet[index] = sum(packet, 4, index - 4);
    }

    static byte sum(byte[] data, int start, int length) {
        int sum = 0;
        for (int i = start; i < start + length; i++) {
            sum += data[i] & 0xFF;
        }
        return (byte) sum;
    }

    static long sumInt(byte[] data, int start, int length) {
        long sum = 0;
        for (int i = start; i < start + length; i++) {
            sum += data[i] & 0xFF;
        }
        return sum;
    }

    /** A one byte feature command (backlight, touchpad, lights, power). */
    static byte[] feature(byte command, byte value) {
        byte[] packet = newPacket(REPORT_SHORT, VERSION_FEATURE, ADDRESS_KEYBOARD, command, 1);
        packet[10] = value;
        seal(packet, 11);
        return packet;
    }

    static byte[] getVersion(byte target) {
        byte[] packet = newPacket(REPORT_SHORT, VERSION_UPGRADE, target, CMD_GET_VERSION, 1);
        packet[10] = 0;
        seal(packet, 11);
        return packet;
    }

    static byte[] checkMcuStatus() {
        byte[] packet = newPacket(REPORT_SHORT, VERSION_FEATURE, ADDRESS_KEYBOARD,
                CMD_CHECK_MCU_STATUS, 1);
        packet[10] = 1;
        seal(packet, 11);
        return packet;
    }

    /** First step of the genuineness check: asks the cover for its identity. */
    static byte[] authStart() {
        byte[] packet = newPacket(REPORT_LONG, VERSION_FEATURE, ADDRESS_KEYBOARD,
                CMD_AUTH_START, 6);
        byte[] magic = {'M', 'I', 'A', 'U', 'T', 'H'};
        System.arraycopy(magic, 0, packet, 10, magic.length);
        seal(packet, 16);
        return packet;
    }

    /** Third step: the key chosen by the auth service and its challenge. */
    static byte[] authStep3(byte[] keyMeta, byte[] challenge) {
        byte[] packet = newPacket(REPORT_LONG, VERSION_FEATURE, ADDRESS_KEYBOARD,
                CMD_AUTH_STEP3, 20);
        System.arraycopy(keyMeta, 0, packet, 10, 4);
        System.arraycopy(challenge, 0, packet, 14, 16);
        seal(packet, 30);
        return packet;
    }

    /** Fifth step: the tablet's own token, for the cover to check. */
    static byte[] authStep5(byte[] token) {
        byte[] packet = newPacket(REPORT_LONG, VERSION_FEATURE, ADDRESS_KEYBOARD,
                CMD_AUTH_STEP5, 16);
        System.arraycopy(token, 0, packet, 10, 16);
        seal(packet, 26);
        return packet;
    }

    static String hex(byte[] data, int length) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < Math.min(length, data.length); i++) {
            builder.append(String.format("%02x ", data[i]));
        }
        return builder.toString().trim();
    }
}
