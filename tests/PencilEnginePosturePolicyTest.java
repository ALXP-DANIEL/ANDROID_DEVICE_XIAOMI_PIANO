/* SPDX-License-Identifier: Apache-2.0 */
import org.lineageos.pianoparts.PencilEnginePosturePolicy;
public final class PencilEnginePosturePolicyTest {
    private static void expect(int expected, int actual) {
        if (expected != actual) throw new AssertionError(expected + " != " + actual);
    }
    public static void main(String[] args) {
        PencilEnginePosturePolicy p = new PencilEnginePosturePolicy();
        expect(0, p.transition(true, true, false));
        expect(-1, p.transition(true, true, false));
        expect(1, p.transition(true, true, true));
        expect(-1, p.transition(true, true, true));
        expect(0, p.transition(true, true, false));
        expect(-1, p.transition(false, true, true));
        expect(-1, p.transition(true, false, true));
        expect(1, p.transition(true, true, true));
        expect(0, p.transition(true, false, true));
        p.invalidate();
        expect(0, p.transition(false, true, false));
        System.out.println("10 posture aggregate/lifecycle policy checks passed");
    }
}
