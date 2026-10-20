/* SPDX-License-Identifier: Apache-2.0 */
package org.lineageos.pianoparts.stylus;

import java.util.LinkedHashMap;
import java.util.Map;

/** Preserve a key's down/up decision across policy stages and lease/focus changes. */
final class PenToolDispatchPolicy {
    private final Map<String, Boolean> decisions = new LinkedHashMap<>();
    private final Map<Long, Press> pressed = new LinkedHashMap<>();
    private static final class Press {
        final long downTime;
        final boolean enabled;
        Press(long downTime, boolean enabled) { this.downTime = downTime; this.enabled = enabled; }
    }

    synchronized boolean accepts(int device, int code, int action, int repeat,
            long downTime, long eventTime, boolean enabled) {
        String event = device + ":" + code + ":" + action + ":" + repeat + ":"
                + downTime + ":" + eventTime;
        Boolean known = decisions.get(event);
        if (known != null) return known;
        long key = ((long) device << 32) | (code & 0xffffffffL);
        boolean result;
        if (action == 0) {
            if (repeat == 0) pressed.put(key, new Press(downTime, enabled));
            Press press = pressed.get(key);
            result = press != null && press.downTime == downTime && press.enabled;
        } else if (action == 1) {
            Press press = pressed.get(key);
            result = press != null && press.downTime == downTime && press.enabled;
            if (press != null && press.downTime == downTime) pressed.remove(key);
        } else {
            result = false;
        }
        decisions.put(event, result);
        while (decisions.size() > 64) decisions.remove(decisions.keySet().iterator().next());
        while (pressed.size() > 32) pressed.remove(pressed.keySet().iterator().next());
        return result;
    }
}
