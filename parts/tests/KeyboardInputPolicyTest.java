/* SPDX-License-Identifier: Apache-2.0 */
package org.lineageos.pianoparts.keyboard;

/** Tests actual-state drift/reconfiguration without forcing folded/rejected/detached covers on. */
public final class KeyboardInputPolicyTest {
    private static int checks;
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static final class Device implements KeyboardInputPolicy.DeviceState {
        boolean enabled;
        int writes;
        int reads;
        Device(boolean enabled) { this.enabled = enabled; }
        public boolean isEnabled(int id) { reads++; return enabled; }
        public void setEnabled(int id, boolean enabled) { this.enabled = enabled; writes++; }
    }

    public static void main(String[] args) {
        Device device = new Device(false);
        check(KeyboardInputPolicy.reconcile(device, 7, 0x15d9, 163, true), "attach enables keys");
        check(device.enabled && device.writes == 1, "attach sets desired state once");
        check(!KeyboardInputPolicy.reconcile(device, 7, 0x15d9, 163, true),
                "enable-generated change callback cannot loop");
        check(device.writes == 1, "no redundant native enable call");
        device.enabled = false; // Simulate actual state lost while cached keysEnabled stays true.
        check(KeyboardInputPolicy.reconcile(device, 7, 0x15d9, 163, true),
                "device reconfiguration restores allowed state");
        check(device.writes == 2, "drift corrected exactly once");
        check(KeyboardInputPolicy.reconcile(device, 7, 0x15d9, 163, false),
                "fold/auth rejection/detach disables keys");
        check(!device.enabled, "blocked cover remains disabled");
        device.enabled = true; // Another actor reconfigured the rejected/folded cover.
        check(KeyboardInputPolicy.reconcile(device, 7, 0x15d9, 163, false),
                "blocked reconfiguration never forces keys on");
        check(!device.enabled, "policy guard dominates actual enabled state");
        Device auxiliary = new Device(true);
        check(KeyboardInputPolicy.reconcile(auxiliary, 8, 0x15d9, 162, true),
                "stock-unused auxiliary node stays off even with working cover");
        check(!auxiliary.enabled, "unused node disabled");
        Device consumer = new Device(false);
        check(KeyboardInputPolicy.reconcile(consumer, 9, 0x15d9, 164, true), "consumer keys follow cover");
        Device other = new Device(true);
        check(!KeyboardInputPolicy.reconcile(other, 10, 0x1234, 163, false), "other vendor untouched");
        check(other.reads == 0 && other.writes == 0, "other vendor state not queried or changed");
        check(!KeyboardInputPolicy.reconcile(other, 10, 0x15d9, 999, false), "unknown product untouched");
        check(other.reads == 0 && other.writes == 0, "unknown product state not queried or changed");
        System.out.println("PASS " + checks + " keyboard input-state checks");
    }
}
