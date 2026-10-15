/* SPDX-License-Identifier: Apache-2.0 */
package org.lineageos.pianoparts.keyboard;

import android.os.Handler;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.function.BooleanSupplier;

/**
 * Explicit connector-MCU updater. Unlike keyboard/touchpad OTA, the target is
 * the pogo controller at 0x18; flashing is never automatically requested.
 */
final class KeyboardMcuUpdater {
    private static final String TAG = "PianoPartsMCU";
    private static final int RETRIES = 4;
    private static final long ACK_TIMEOUT_MS = 1500;
    private static final long VERIFY_DELAY_MS = 1500;
    private static final int VERIFY_ATTEMPTS = 8;

    interface Callback {
        void onProgress(int percent);
        void onFinished(boolean success);
    }

    private final KeyboardTransport transport;
    private final Handler handler;
    private final Runnable retryTask = this::onTimeout;
    private final Runnable verifyTask = this::pollVersion;

    private volatile boolean running;
    private volatile boolean abortRequested;
    private KeyboardMcuFirmware firmware;
    private BooleanSupplier allowed;
    private Callback callback;
    private byte[] pending;
    private byte expectedCommand;
    private int chunkIndex;
    private int retries;
    private int verifyAttempts;
    private boolean verifying;

    KeyboardMcuUpdater(KeyboardTransport transport) {
        this.transport = transport;
        handler = transport.getHandler();
    }

    boolean isRunning() { return running; }
    boolean isVerifying() { return running && verifying; }

    void start(KeyboardMcuFirmware image, BooleanSupplier canContinue, Callback completed) {
        if (running) {
            handler.post(() -> completed.onFinished(false));
            return;
        }
        running = true;
        abortRequested = false;
        handler.post(() -> {
            if (abortRequested || !canContinue.getAsBoolean()) {
                running = false;
                completed.onFinished(false);
                return;
            }
            firmware = image;
            allowed = canContinue;
            callback = completed;
            verifying = false;
            chunkIndex = 0;
            LocalDate today = LocalDate.now();
            Log.i(TAG, "Starting manual connector MCU firmware update to " + image.version());
            send(image.infoPacket(today.getYear(), today.getMonthValue(), today.getDayOfMonth()));
        });
    }

    void abort() {
        abortRequested = true;
        handler.post(() -> {
            if (firmware != null) finish(false);
        });
    }

    /** Called on the same serial keyboard-transport Handler as sends/retries. */
    boolean onReply(byte[] reply) {
        if (!running || firmware == null || reply.length < 5
                || reply[2] != KeyboardProtocol.ADDRESS_MCU
                || reply[1] != KeyboardProtocol.VERSION_UPGRADE) {
            return false;
        }
        int command = reply[4] & 0xFF;
        if (abortRequested) return true;
        if (verifying && command == (KeyboardProtocol.CMD_GET_VERSION & 0xFF)) {
            if (reply.length >= 23) {
                String current = new String(reply, 7, 16, StandardCharsets.US_ASCII)
                        .replace("\u0000", "").trim();
                if (firmware.version().equals(current)) {
                    Log.i(TAG, "Verified MCU firmware version after reset: " + current);
                    callback.onProgress(100);
                    finish(true);
                }
            }
            return true;
        }
        if (command != 0x02 && command != 0x91 && command != 0x04 && command != 0x06) {
            return false;
        }
        if (verifying) return true; // Stale flash ACKs after reset cannot advance us.
        if (reply.length < (command == 0x91 ? 11 : 7)) {
            Log.w(TAG, "Ignoring truncated MCU upgrade reply");
            return true;
        }
        int status = reply[6] & 0xFF;
        if (status == 0x0D) return true; // Stock 'busy': retry current packet.
        if (status != 0) {
            Log.e(TAG, "MCU rejected firmware stage " + command + ", status " + status);
            finish(false);
            return true;
        }
        if (command == 0x91) {
            if (expectedCommand != KeyboardProtocol.CMD_UPGRADE_DATA) return true;
            int offset = (reply[8] & 0xFF) | (reply[9] & 0xFF) << 8
                    | (reply[10] & 0xFF) << 16;
            if (offset != chunkIndex * KeyboardMcuFirmware.CHUNK) {
                Log.w(TAG, "Ignoring out-of-order MCU data ACK " + offset);
                return true;
            }
            handler.removeCallbacks(retryTask);
            callback.onProgress(Math.min(99, (chunkIndex + 1) * 100 / firmware.chunkCount()));
            if (++chunkIndex < firmware.chunkCount()) {
                send(firmware.chunkPacket(chunkIndex));
            } else {
                send(firmware.endPacket());
            }
            return true;
        }
        if (command != (expectedCommand & 0xFF)) return true;
        handler.removeCallbacks(retryTask);
        switch (command) {
            case 0x02:
                send(firmware.chunkPacket(0));
                break;
            case 0x04:
                send(firmware.flashPacket());
                break;
            case 0x06:
                beginVerification();
                break;
            default:
                finish(false);
        }
        return true;
    }

    private void send(byte[] packet) {
        handler.removeCallbacks(retryTask);
        if (abortRequested || !allowed.getAsBoolean()) {
            finish(false);
            return;
        }
        pending = packet;
        expectedCommand = packet[8];
        retries = RETRIES;
        if (!transport.send(pending)) {
            finish(false);
            return;
        }
        handler.postDelayed(retryTask, ACK_TIMEOUT_MS);
    }

    private void onTimeout() {
        if (!running || verifying) return;
        if (abortRequested || !allowed.getAsBoolean() || retries-- <= 0) {
            Log.e(TAG, "Connector MCU update timed out or became unsafe");
            finish(false);
            return;
        }
        if (!transport.send(pending)) {
            finish(false);
            return;
        }
        handler.postDelayed(retryTask, ACK_TIMEOUT_MS);
    }

    private void beginVerification() {
        handler.removeCallbacks(retryTask);
        if (abortRequested || !allowed.getAsBoolean()) {
            finish(false);
            return;
        }
        // Stock queues a controller reset after the flash ACK. A reset may
        // disconnect the cover; verification does not require it to stay attached.
        if (!transport.send(firmware.resetPacket())) {
            finish(false);
            return;
        }
        verifying = true;
        verifyAttempts = 0;
        handler.postDelayed(verifyTask, VERIFY_DELAY_MS);
    }

    private void pollVersion() {
        if (!running || !verifying || abortRequested) {
            if (running) finish(false);
            return;
        }
        if (verifyAttempts++ >= VERIFY_ATTEMPTS) {
            Log.e(TAG, "MCU did not report the expected version after reset");
            finish(false);
            return;
        }
        transport.send(KeyboardProtocol.getVersion(KeyboardProtocol.ADDRESS_MCU));
        handler.postDelayed(verifyTask, VERIFY_DELAY_MS);
    }

    private void finish(boolean success) {
        handler.removeCallbacks(retryTask);
        handler.removeCallbacks(verifyTask);
        Callback completion = callback;
        callback = null;
        firmware = null;
        allowed = null;
        pending = null;
        verifying = false;
        running = false;
        if (completion != null) completion.onFinished(success);
    }
}
