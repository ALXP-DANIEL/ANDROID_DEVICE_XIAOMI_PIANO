/*
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pianoparts.keyboard;

import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 * A keyboard or touchpad firmware image from the stock /odm/etc, with the
 * packets that flash it. The image carries a 64 byte header (length, sum,
 * version) that is validated before anything is sent.
 */
final class KeyboardFirmware {

    private static final String TAG = "PianoPartsKeyboard";

    static final int CHUNK = 52;

    // Header offsets of the keyboard image layouts.
    private static final int HEADER_8012 = 12224;
    private static final int HEADER_176 = 135104;
    private static final int HEADER_179 = 24512;

    // Keyboard type whose image uses the 179 layout.
    private static final byte KEYBOARD_TYPE_179 = 33;

    final boolean touchpad;
    final String path;
    final String version;

    private final byte[] mImage;
    private final int mHeader;
    private final int mDataStart;
    private final long mLength;
    private final byte[] mLengthBytes = new byte[4];
    private final byte[] mSumBytes = new byte[4];
    private final byte[] mVersionBytes = new byte[2];
    private final byte[] mStartAddress = new byte[4];
    private byte mKeyType;
    private byte mTouchType;
    private byte mNfcType;

    private KeyboardFirmware(boolean touchpad, String path, byte[] image, int header) {
        this.touchpad = touchpad;
        this.path = path;
        mImage = image;
        mHeader = header;
        // The keyboard sends the image after its header, the touchpad the
        // whole file including it.
        mDataStart = touchpad ? 0 : header + 64;
        System.arraycopy(image, header + 8, mLengthBytes, 0, 4);
        System.arraycopy(image, header + 12, mSumBytes, 0, 4);
        System.arraycopy(image, header + 16, mVersionBytes, 0, 2);
        mLength = le32(mLengthBytes);
        version = touchpad
                ? String.format("%02x%02x", mVersionBytes[0], mVersionBytes[1])
                : String.format("%02x%02x", mVersionBytes[1], mVersionBytes[0]);
    }

    /** Loads a keyboard image, or null if it is missing or corrupt. */
    static KeyboardFirmware loadKeyboard(String path, byte keyboardType, boolean mcu2022) {
        byte[] image = read(path);
        if (image == null) {
            return null;
        }
        int header;
        if (keyboardType == KEYBOARD_TYPE_179) {
            header = HEADER_179;
        } else if (mcu2022) {
            header = HEADER_176;
        } else {
            header = HEADER_8012;
        }
        if (image.length <= header + 64) {
            Log.e(TAG, "Keyboard image is too short: " + path);
            return null;
        }
        KeyboardFirmware firmware = new KeyboardFirmware(false, path, image, header);
        if (!firmware.verify()) {
            return null;
        }
        // Where each layout keeps its keyboard, NFC and touch type bytes.
        int typeIndex;
        if (header == HEADER_176) {
            typeIndex = 135368;
        } else if (header == HEADER_179) {
            typeIndex = 24648;
        } else {
            typeIndex = 24536;
        }
        if (image.length <= typeIndex + 2) {
            Log.e(TAG, "Keyboard image has no type bytes: " + path);
            return null;
        }
        firmware.mKeyType = image[typeIndex];
        firmware.mNfcType = (byte) (image[typeIndex + 1] >> (header == HEADER_176 ? 6 : 5));
        firmware.mTouchType = (byte) (image[typeIndex + 2] & 0x0F);
        if (header == HEADER_176) {
            firmware.setStartAddress(0x80, 0x05);
        } else if (header == HEADER_179) {
            firmware.setStartAddress(0x10, 0x06);
        } else {
            firmware.setStartAddress(0x20, 0x00);
        }
        return firmware;
    }

    /** Loads a touchpad image, or null if it is missing or corrupt. */
    static KeyboardFirmware loadTouchpad(String path) {
        byte[] image = read(path);
        if (image == null || image.length <= 64) {
            return null;
        }
        KeyboardFirmware firmware = new KeyboardFirmware(true, path, image, 0);
        if (!firmware.verify()) {
            return null;
        }
        firmware.setStartAddress(0x80, 0x06);
        return firmware;
    }

    private void setStartAddress(int second, int third) {
        mStartAddress[0] = 0;
        mStartAddress[1] = (byte) second;
        mStartAddress[2] = (byte) third;
        mStartAddress[3] = 0;
    }

    private boolean verify() {
        int bodyStart = mHeader + 64;
        long sum = KeyboardProtocol.sumInt(mImage, bodyStart, mImage.length - bodyStart);
        if ((sum & 0xFFFFFFFFL) != le32(mSumBytes)) {
            Log.e(TAG, "Firmware body checksum mismatch: " + path);
            return false;
        }
        if (KeyboardProtocol.sum(mImage, mHeader, 63) != mImage[mHeader + 63]) {
            Log.e(TAG, "Firmware header checksum mismatch: " + path);
            return false;
        }
        return true;
    }

    /** Whether this image is newer than the running version. */
    boolean isNewerThan(String running) {
        return running != null && !version.startsWith(running) && version.compareTo(running) > 0;
    }

    int chunkCount() {
        long bytes = touchpad ? mLength + 64 : mLength;
        return (int) ((bytes + CHUNK - 1) / CHUNK);
    }

    byte[] infoPacket() {
        byte[] packet = KeyboardProtocol.newPacket(KeyboardProtocol.REPORT_LONG,
                KeyboardProtocol.VERSION_UPGRADE, KeyboardProtocol.ADDRESS_KEYBOARD,
                KeyboardProtocol.CMD_UPGRADE_INFO, 19);
        System.arraycopy(mLengthBytes, 0, packet, 10, 4);
        System.arraycopy(mStartAddress, 0, packet, 14, 4);
        System.arraycopy(mSumBytes, 0, packet, 18, 4);
        System.arraycopy(mVersionBytes, 0, packet, 22, 2);
        packet[24] = 43;
        packet[25] = 18;
        if (touchpad) {
            packet[26] = 32;
            packet[27] = 3;
        } else {
            packet[26] = mKeyType;
            packet[27] = mTouchType;
            packet[28] = mNfcType;
        }
        KeyboardProtocol.seal(packet, 29);
        return packet;
    }

    byte[] dataPacket(int index) {
        int offset = index * CHUNK;
        byte[] packet = KeyboardProtocol.newPacket(KeyboardProtocol.REPORT_LONG,
                KeyboardProtocol.VERSION_UPGRADE, KeyboardProtocol.ADDRESS_KEYBOARD,
                KeyboardProtocol.CMD_UPGRADE_DATA, CHUNK + 4);
        packet[10] = (byte) offset;
        packet[11] = (byte) (offset >> 8);
        packet[12] = (byte) (offset >> 16);
        packet[13] = CHUNK;
        int length = Math.min(CHUNK, mImage.length - (mDataStart + offset));
        if (length > 0) {
            System.arraycopy(mImage, mDataStart + offset, packet, 14, length);
        }
        KeyboardProtocol.seal(packet, 66);
        return packet;
    }

    byte[] endPacket() {
        byte[] packet = KeyboardProtocol.newPacket(
                touchpad ? KeyboardProtocol.REPORT_SHORT : KeyboardProtocol.REPORT_LONG,
                KeyboardProtocol.VERSION_UPGRADE, KeyboardProtocol.ADDRESS_KEYBOARD,
                KeyboardProtocol.CMD_UPGRADE_END, 14);
        System.arraycopy(mLengthBytes, 0, packet, 10, 4);
        System.arraycopy(mStartAddress, 0, packet, 14, 4);
        System.arraycopy(mSumBytes, 0, packet, 18, 4);
        System.arraycopy(mVersionBytes, 0, packet, 22, 2);
        KeyboardProtocol.seal(packet, 24);
        return packet;
    }

    byte[] flashPacket() {
        byte[] packet = KeyboardProtocol.newPacket(KeyboardProtocol.REPORT_LONG,
                KeyboardProtocol.VERSION_UPGRADE, KeyboardProtocol.ADDRESS_KEYBOARD,
                KeyboardProtocol.CMD_UPGRADE_FLASH, 4);
        System.arraycopy(mStartAddress, 0, packet, 10, 4);
        KeyboardProtocol.seal(packet, 14);
        return packet;
    }

    private static long le32(byte[] bytes) {
        return (bytes[0] & 0xFFL) | (bytes[1] & 0xFFL) << 8 | (bytes[2] & 0xFFL) << 16
                | (bytes[3] & 0xFFL) << 24;
    }

    private static byte[] read(String path) {
        File file = new File(path);
        if (!file.exists()) {
            return null;
        }
        try {
            return Files.readAllBytes(file.toPath());
        } catch (IOException e) {
            Log.e(TAG, "Failed to read " + path, e);
            return null;
        }
    }
}
