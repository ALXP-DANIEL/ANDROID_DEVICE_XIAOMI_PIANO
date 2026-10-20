/* SPDX-License-Identifier: Apache-2.0 */
package org.lineageos.pianoparts.stylus;
import android.content.Intent;
public final class NotesLaunchContractTest {
    public static void main(String[] args) {
        int checks = 0;
        for (String pkg : new String[]{NotesLaunchContract.NOTES, NotesLaunchContract.CREATION}) {
            for (String scene : new String[]{"app", "home", "keyguard", "off_screen"}) {
                Intent i = NotesLaunchContract.create(pkg, scene);
                check(pkg.equals(i.getPackage()), "package");
                check(scene.equals(i.getStringExtra("scene")), "scene");
                check("stylus".equals(i.getStringExtra("shortcut")), "caller");
                check(i.getFlags() == 335544320, "stock flags");
                check(i.getBooleanExtra("StartActivityWhenLocked", false) ==
                    (scene.equals("keyguard") || scene.equals("off_screen")), "lock extra");
                boolean creation = pkg.equals(NotesLaunchContract.CREATION);
                check(i.getAction().equals(creation ? "com.miui.creation.action.INSERT_CREATION" :
                    "com.miui.pad.notes.action.INSERT_OR_EDIT"), "action");
                check(i.getType().equals(creation ? "vnd.android.cursor.item/create_creation" :
                    "vnd.android.cursor.item/text_note"), "MIME");
                checks += 7;
            }
        }
        check(!NotesLaunchContract.ownsToolKeys(null), "null foreground");
        check(!NotesLaunchContract.ownsToolKeys("other.app"), "ordinary app");
        try { NotesLaunchContract.create("other.app", "app"); throw new AssertionError("package accepted"); }
        catch (IllegalArgumentException expected) {}
        try { NotesLaunchContract.create(NotesLaunchContract.NOTES, "unknown"); throw new AssertionError("scene accepted"); }
        catch (IllegalArgumentException expected) {}
        System.out.println("Notes launch contract: " + (checks + 4) + " checks passed");
    }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
