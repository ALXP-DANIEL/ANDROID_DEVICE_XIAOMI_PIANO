/* SPDX-License-Identifier: Apache-2.0 */
package org.lineageos.pianoparts.pencilengine;

/** Short, boot- and UID-scoped requests shared with the system pen handler. */
public final class PencilEngineLease {
    public static final String TOUCH_FILM = "piano_pencil_touchfilm_request";
    public static final String TOUCH_FILM_READY = "piano_pencil_touchfilm_backend_boot";
    public static final long DURATION_MS = 5000;

    public static String create(int boot, int uid, long now, String owner) {
        if (boot < 0 || uid < 0 || now < 0 || owner == null
                || !owner.matches("[a-f0-9]{32}")) throw new IllegalArgumentException("Invalid lease");
        return boot + "|" + uid + "|" + Math.addExact(now, DURATION_MS) + "|" + owner;
    }

    public static boolean accepts(String value, int boot, int uid, long now) {
        if (value == null || value.length() > 128 || boot < 0 || uid < 0 || now < 0) return false;
        String[] fields = value.split("\\|", -1);
        if (fields.length != 4 || !fields[3].matches("[a-f0-9]{32}")) return false;
        try {
            long deadline = Long.parseLong(fields[2]);
            return Integer.parseInt(fields[0]) == boot && Integer.parseInt(fields[1]) == uid
                    && deadline > now && deadline - now <= DURATION_MS;
        } catch (NumberFormatException invalid) { return false; }
    }

    public static boolean ownedBy(String value, String owner) {
        return value != null && owner != null && owner.matches("[a-f0-9]{32}")
                && value.endsWith("|" + owner);
    }

    private PencilEngineLease() {}
}
