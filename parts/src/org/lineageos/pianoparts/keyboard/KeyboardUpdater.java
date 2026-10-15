/*
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pianoparts.keyboard;

import android.os.Handler;
import android.util.Log;

import java.util.function.BooleanSupplier;

/**
 * Flashes a keyboard or touchpad image the way stock does: an info packet,
 * the image in 52 byte chunks (each acknowledged with its offset), then end
 * and flash packets. Every packet is resent up to four times, 1.5 s apart.
 */
final class KeyboardUpdater {

    private static final String TAG = "PianoPartsKeyboard";

    private static final long RETRY_DELAY_MS = 1500;
    private static final int RETRIES = 4;
    private static final byte STATUS_RETRY = 0x0D;

    interface Callback {
        void onProgress(int percent);

        void onFinished(boolean success);
    }

    private final KeyboardTransport mTransport;
    private final Handler mHandler;
    private volatile KeyboardFirmware mFirmware;
    private Callback mCallback;
    private byte[] mPending;
    private byte mPendingCommand;
    private int mRetriesLeft;
    private int mLastPercent = -1;

    private final Runnable mRetry = this::retry;

    KeyboardUpdater(KeyboardTransport transport) {
        mTransport = transport;
        mHandler = transport.getHandler();
    }

    boolean isRunning() {
        return mFirmware != null;
    }

    void start(KeyboardFirmware firmware, BooleanSupplier canStart, Callback callback) {
        mHandler.post(() -> {
            if (mFirmware != null || !canStart.getAsBoolean()) {
                // The caller already holds a wake lock. Complete even when
                // a competing request or detach makes the queued start stale.
                callback.onFinished(false);
                return;
            }
            Log.i(TAG, "Updating " + (firmware.touchpad ? "touchpad" : "keyboard")
                    + " to " + firmware.version + " from " + firmware.path);
            mFirmware = firmware;
            mCallback = callback;
            mLastPercent = -1;
            send(firmware.infoPacket());
        });
    }

    /** Stops an update, for example when the keyboard is detached. */
    void abort() {
        mHandler.post(() -> {
            if (mFirmware != null) {
                Log.w(TAG, "Keyboard update aborted");
                finish(false);
            }
        });
    }

    /** Called on the transport thread for update replies. */
    boolean onReply(byte[] reply) {
        if (mFirmware == null || reply.length < 5) {
            return false;
        }
        byte command = reply[4];
        boolean ack = command == KeyboardProtocol.CMD_UPGRADE_DATA_ACK;
        if (!ack && command != KeyboardProtocol.CMD_UPGRADE_INFO
                && command != KeyboardProtocol.CMD_UPGRADE_END
                && command != KeyboardProtocol.CMD_UPGRADE_FLASH) {
            return false;
        }
        if (reply.length < (ack ? 11 : 7)) {
            // Keep the pending packet and its timeout. A missing status is
            // not an acknowledgement and must never advance the update.
            Log.w(TAG, "Truncated keyboard update reply");
            return true;
        }
        byte status = reply[6];
        if (status != 0) {
            if (status == STATUS_RETRY || command == KeyboardProtocol.CMD_UPGRADE_FLASH) {
                Log.i(TAG, "Keyboard asked to retry");
            } else {
                Log.e(TAG, "Keyboard update error: " + KeyboardProtocol.hex(reply, reply.length));
                finish(false);
                return true;
            }
        }
        if (ack) {
            if (mPendingCommand != KeyboardProtocol.CMD_UPGRADE_DATA || reply.length < 11) {
                return true;
            }
            int offset = (reply[8] & 0xFF) | (reply[9] & 0xFF) << 8 | (reply[10] & 0xFF) << 16;
            int index = offset / KeyboardFirmware.CHUNK;
            int count = mFirmware.chunkCount();
            report(index + 1, count);
            if (index == count - 1) {
                send(mFirmware.endPacket());
            } else {
                send(mFirmware.dataPacket(index + 1));
            }
            return true;
        }
        if (command != mPendingCommand) {
            Log.d(TAG, "Unexpected update reply " + command + ", waiting for " + mPendingCommand);
            return true;
        }
        switch (command) {
            case KeyboardProtocol.CMD_UPGRADE_INFO:
                send(mFirmware.dataPacket(0));
                break;
            case KeyboardProtocol.CMD_UPGRADE_END:
                send(mFirmware.flashPacket());
                break;
            case KeyboardProtocol.CMD_UPGRADE_FLASH:
                Log.i(TAG, "Keyboard update flashed");
                finish(true);
                break;
        }
        return true;
    }

    private void send(byte[] packet) {
        mHandler.removeCallbacks(mRetry);
        mPending = packet;
        mPendingCommand = packet[8];
        mRetriesLeft = RETRIES;
        mTransport.send(packet);
        mHandler.postDelayed(mRetry, RETRY_DELAY_MS);
    }

    private void retry() {
        if (mFirmware == null || mPending == null) {
            return;
        }
        if (mRetriesLeft-- <= 0) {
            Log.e(TAG, "Keyboard stopped answering during the update");
            finish(false);
            return;
        }
        mTransport.send(mPending);
        mHandler.postDelayed(mRetry, RETRY_DELAY_MS);
    }

    private void report(int done, int count) {
        int percent = Math.min(100, done * 100 / Math.max(1, count));
        if (percent != mLastPercent) {
            mLastPercent = percent;
            mCallback.onProgress(percent);
        }
    }

    private void finish(boolean success) {
        mHandler.removeCallbacks(mRetry);
        Callback callback = mCallback;
        mFirmware = null;
        mCallback = null;
        mPending = null;
        callback.onFinished(success);
    }
}
