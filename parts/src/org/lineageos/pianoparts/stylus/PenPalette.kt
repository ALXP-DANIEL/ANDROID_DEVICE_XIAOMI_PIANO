/* SPDX-License-Identifier: Apache-2.0 */
package org.lineageos.pianoparts.stylus

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.UserHandle
import android.provider.Settings
import org.json.JSONArray
import org.lineageos.pianoparts.R
import java.util.Locale

/** Current-user explicit launcher components, never arbitrary app-supplied intent URIs. */
object PenPalette {
    const val SETTING = "piano_pen_palette"
    const val MAX_ENTRIES = 6
    data class Candidate(val id: String, val label: CharSequence, val icon: Drawable)
    private val defaults = listOf("assistant", "notes", "screenshot", "settings")

    fun candidates(context: Context): List<Candidate> {
        val user = context.createContextAsUser(UserHandle.of(ActivityManager.getCurrentUser()), 0)
        val result = mutableListOf<Candidate>()
        fun builtin(id: String, label: Int, icon: Int, intent: Intent? = null) {
            if (intent == null || intent.resolveActivity(user.packageManager) != null) {
                result.add(Candidate(id, context.getString(label), context.getDrawable(icon)!!))
            }
        }
        builtin("assistant", R.string.stylus_menu_assistant, android.R.drawable.ic_btn_speak_now, Intent(Intent.ACTION_ASSIST))
        builtin("notes", R.string.stylus_menu_notes, android.R.drawable.ic_menu_edit, Intent(Intent.ACTION_CREATE_NOTE))
        builtin("screenshot", R.string.stylus_menu_screenshot, android.R.drawable.ic_menu_camera)
        builtin("settings", R.string.stylus_menu_settings, android.R.drawable.ic_menu_manage)
        if (PenLaserController.isAvailable(context)) builtin("laser", R.string.stylus_menu_laser, android.R.drawable.ic_menu_compass)
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = user.packageManager.queryIntentActivities(launcher, 0).filter {
            it.activityInfo.exported && it.activityInfo.enabled && it.activityInfo.applicationInfo.enabled
        }.map {
            Candidate("app:" + ComponentName(it.activityInfo.packageName, it.activityInfo.name).flattenToString(),
                it.loadLabel(user.packageManager), it.loadIcon(user.packageManager))
        }.distinctBy { it.id }.sortedBy { it.label.toString().lowercase(Locale.ROOT) }
        return result + apps
    }

    fun selected(context: Context): List<String> {
        val raw = Settings.Secure.getStringForUser(context.contentResolver, SETTING, UserHandle.USER_CURRENT)
            ?: return if (PenLaserController.isAvailable(context)) listOf("assistant", "notes", "laser", "screenshot") else defaults
        return parseSelection(raw)
    }

    fun parseSelection(raw: String): List<String> {
        if (raw.length > 8192) return defaults
        return try {
            val array = JSONArray(raw)
            if (array.length() !in 1..MAX_ENTRIES) return defaults
            val ids = (0 until array.length()).map { array.getString(it) }
            if (ids.distinct().size != ids.size || ids.any { !validId(it) }) return defaults
            ids
        } catch (_: Exception) { defaults }
    }

    private fun validId(id: String): Boolean = id in defaults || id == "laser" ||
        (id.length <= 1024 && id.startsWith("app:") &&
            ComponentName.unflattenFromString(id.removePrefix("app:")) != null)

    fun save(context: Context, ids: List<String>): Boolean {
        if (ids.isEmpty() || ids.size > MAX_ENTRIES || ids.distinct().size != ids.size) return false
        val allowed = candidates(context).map { it.id }.toSet()
        if (!ids.all(allowed::contains)) return false
        return Settings.Secure.putStringForUser(context.contentResolver, SETTING,
            JSONArray(ids).toString(), UserHandle.USER_CURRENT)
    }

    fun reset(context: Context): Boolean = Settings.Secure.putStringForUser(
        context.contentResolver, SETTING, null, UserHandle.USER_CURRENT)

    fun visible(context: Context): List<Candidate> {
        val allowed = candidates(context).associateBy { it.id }
        return selected(context).mapNotNull(allowed::get).take(MAX_ENTRIES)
    }

    fun launch(context: Context, candidate: Candidate) {
        // Re-resolve at launch; package removal/disabled activity invalidates persisted selection.
        val current = candidates(context).firstOrNull { it.id == candidate.id } ?: return
        val user = UserHandle.of(ActivityManager.getCurrentUser())
        when (current.id) {
            "assistant" -> context.startActivityAsUser(Intent(Intent.ACTION_ASSIST).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), user)
            "notes" -> StylusActions.run(context, android.os.Handler(context.mainLooper), StylusSettingsContract.Action.OPEN_NOTES)
            "screenshot" -> StylusActions.run(context, android.os.Handler(context.mainLooper), StylusSettingsContract.Action.SCREENSHOT)
            "settings" -> context.startActivityAsUser(Intent(Intent.ACTION_MAIN)
                .setClassName(context.packageName, "${context.packageName}.StylusSettingsActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), user)
            "laser" -> context.sendBroadcast(Intent(PenLaserController.TOGGLE).setPackage(context.packageName))
            else -> if (current.id.startsWith("app:")) {
                val component = ComponentName.unflattenFromString(current.id.removePrefix("app:")) ?: return
                context.startActivityAsUser(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), user)
            }
        }
    }
}
