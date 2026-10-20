/* SPDX-License-Identifier: Apache-2.0 */
package org.lineageos.pianoparts.stylus;

import android.content.Intent;

/** Stock .44 QuickAppPanel / ShortCutActionsUtils entry contracts. */
final class NotesLaunchContract {
    static final String NOTES = "com.miui.notes";
    static final String CREATION = "com.miui.creation";

    static boolean ownsToolKeys(String packageName) {
        return NOTES.equals(packageName) || CREATION.equals(packageName);
    }

    static Intent create(String packageName, String scene) {
        if (!ownsToolKeys(packageName)) throw new IllegalArgumentException("Unsupported notes package");
        if (!"app".equals(scene) && !"home".equals(scene) &&
                !"keyguard".equals(scene) && !"off_screen".equals(scene)) {
            throw new IllegalArgumentException("Unsupported notes scene");
        }
        boolean creation = CREATION.equals(packageName);
        Intent intent = new Intent(creation ? "com.miui.creation.action.INSERT_CREATION" :
                "com.miui.pad.notes.action.INSERT_OR_EDIT")
                .setPackage(packageName)
                .setType(creation ? "vnd.android.cursor.item/create_creation" :
                        "vnd.android.cursor.item/text_note")
                .putExtra("shortcut", "stylus")
                .putExtra("scene", scene)
                .putExtra(Intent.EXTRA_USE_STYLUS_MODE, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if ("keyguard".equals(scene) || "off_screen".equals(scene)) {
            intent.putExtra("StartActivityWhenLocked", true);
        }
        return intent;
    }
    private NotesLaunchContract() {}
}
