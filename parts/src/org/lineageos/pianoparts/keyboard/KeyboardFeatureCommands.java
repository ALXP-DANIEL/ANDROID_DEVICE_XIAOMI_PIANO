/* SPDX-License-Identifier: Apache-2.0 */
package org.lineageos.pianoparts.keyboard;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** Stock feature commands receive one delayed replay unless their effect is acknowledged. */
final class KeyboardFeatureCommands {
    private static final long REPLAY_DELAY_MS = 50;

    interface Scheduler {
        void postDelayed(Runnable task, long delayMs);
        void remove(Runnable task);
    }

    interface Sender {
        boolean send(byte command, byte value);
    }

    private final Scheduler mScheduler;
    private final Sender mSender;
    private final BooleanSupplier mCanReplay;
    private final Map<Byte, Pending> mPending = new HashMap<>();

    KeyboardFeatureCommands(Scheduler scheduler, Sender sender, BooleanSupplier canReplay) {
        mScheduler = scheduler;
        mSender = sender;
        mCanReplay = canReplay;
    }

    // All methods run on the keyboard handler, including effect replies.
    boolean send(byte command, byte value) {
        Pending previous = mPending.remove(command);
        if (previous != null) mScheduler.remove(previous);
        if (!mSender.send(command, value)) return false;
        Pending pending = new Pending(command, value);
        mPending.put(command, pending);
        mScheduler.postDelayed(pending, REPLAY_DELAY_MS);
        return true;
    }

    void acknowledge(byte command, byte value) {
        Pending pending = mPending.get(command);
        if (pending != null && pending.value == value) {
            mPending.remove(command);
            mScheduler.remove(pending);
        }
    }

    void clear() {
        for (Pending pending : mPending.values()) mScheduler.remove(pending);
        mPending.clear();
    }

    private final class Pending implements Runnable {
        private final byte command;
        private final byte value;

        Pending(byte command, byte value) {
            this.command = command;
            this.value = value;
        }

        @Override
        public void run() {
            if (mPending.get(command) != this) return;
            mPending.remove(command);
            if (mCanReplay.getAsBoolean()) mSender.send(command, value);
        }
    }
}
