/* SPDX-License-Identifier: Apache-2.0 */
package org.lineageos.pianoparts.stylus;

import org.lineageos.pianoparts.pencilengine.PencilEngineLease;

public final class PenToolDispatchPolicyTest {
    private static int checks;
    private static void check(boolean value) {
        if (!value) throw new AssertionError("Check " + (checks + 1));
        checks++;
    }
    public static void main(String[] args) {
        String owner = "0123456789abcdef0123456789abcdef";
        String lease = PencilEngineLease.create(7, 10410, 1000, owner);
        check(PencilEngineLease.accepts(lease, 7, 10410, 1000));
        check(PencilEngineLease.accepts(lease, 7, 10410, 5999));
        check(!PencilEngineLease.accepts(lease, 7, 10410, 6000));
        check(!PencilEngineLease.accepts(lease, 8, 10410, 1000));
        check(!PencilEngineLease.accepts(lease, 7, 1010410, 1000));
        check(!PencilEngineLease.accepts(lease, 7, 10410, 999));
        check(!PencilEngineLease.accepts(null, 7, 10410, 1000));
        check(!PencilEngineLease.accepts("7|10410|6000|bad", 7, 10410, 1000));
        check(!PencilEngineLease.accepts(lease + "|extra", 7, 10410, 1000));
        check(!PencilEngineLease.accepts("x|10410|6000|" + owner, 7, 10410, 1000));
        check(!PencilEngineLease.accepts(lease, -1, 10410, 1000));
        check(PencilEngineLease.ownedBy(lease, owner));
        check(!PencilEngineLease.ownedBy(lease, "abcdef0123456789abcdef0123456789"));
        try { PencilEngineLease.create(7, 10410, Long.MAX_VALUE, owner);
            throw new AssertionError("Overflow accepted");
        } catch (ArithmeticException expected) { checks++; }

        PenToolDispatchPolicy policy = new PenToolDispatchPolicy();
        // Queue/dispatch phases must agree even when focus or lease changes between them.
        check(policy.accepts(1, 195, 0, 0, 10, 10, true));
        check(policy.accepts(1, 195, 0, 0, 10, 10, false));
        check(policy.accepts(1, 195, 1, 0, 10, 20, false));
        check(policy.accepts(1, 195, 1, 0, 10, 20, false));
        // A registration acquired mid-press cannot introduce an orphan release.
        check(!policy.accepts(1, 196, 0, 0, 30, 30, false));
        check(!policy.accepts(1, 196, 0, 1, 30, 35, true));
        check(!policy.accepts(1, 196, 1, 0, 30, 40, true));
        check(policy.accepts(1, 196, 0, 0, 50, 50, true));
        check(policy.accepts(1, 196, 1, 0, 50, 60, true));
        // A stale release cannot end a newer press or another device's press.
        check(policy.accepts(1, 197, 0, 0, 70, 70, true));
        check(policy.accepts(1, 197, 0, 0, 80, 80, true));
        check(!policy.accepts(1, 197, 1, 0, 70, 90, true));
        check(!policy.accepts(2, 197, 1, 0, 80, 95, true));
        check(policy.accepts(1, 197, 1, 0, 80, 100, false));
        check(!policy.accepts(1, 197, 0, 1, 80, 101, true));
        check(!policy.accepts(1, 197, 2, 0, 80, 102, true));
        System.out.println(checks + " pen lease and down/up policy checks passed");
    }
}
