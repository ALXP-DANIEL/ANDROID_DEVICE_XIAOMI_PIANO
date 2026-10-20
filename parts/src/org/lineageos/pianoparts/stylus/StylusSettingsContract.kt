/*
 * Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pianoparts.stylus

/**
 * Stable settings contract shared by the settings UI and stylus helper.
 */
object StylusSettingsContract {
    const val PRIMARY_BUTTON_ACTION = "piano_stylus_primary_button_action"
    const val SECONDARY_BUTTON_ACTION = "piano_stylus_secondary_button_action"

    /** Stock reports a pen squeeze as key 194 (BUTTON_7). */
    const val PINCH_ACTION = "piano_stylus_pinch_action"
    const val DOUBLE_TAP_ACTION = "piano_stylus_double_tap_action"
    const val SLIDE_UP_ACTION = "piano_stylus_slide_up_action"
    const val SLIDE_DOWN_ACTION = "piano_stylus_slide_down_action"

    /** Like stock, a pen button set to open notes also works with the screen off. */
    const val SCREEN_OFF_QUICK_NOTE = "piano_stylus_screen_off_quick_note"
    const val DEFAULT_SCREEN_OFF_QUICK_NOTE = true

    object Action {
        const val NONE = "none"
        const val OPEN_NOTES = "open_notes"
        const val SCREENSHOT = "screenshot"
        const val BACK = "back"
        const val HOME = "home"
        const val RECENTS = "recents"
        const val MEDIA_PLAY_PAUSE = "media_play_pause"
        const val LASER = "laser"
        const val MENU = "menu"

        val SUPPORTED: Set<String> = setOf(
            NONE,
            OPEN_NOTES,
            SCREENSHOT,
            BACK,
            HOME,
            RECENTS,
            MEDIA_PLAY_PAUSE,
            LASER,
            MENU,
        )
    }

    /** Sent to the Parts process by the key handler: pen laser mode on or off. */
    const val ACTION_LASER_MODE = "org.lineageos.pianoparts.action.LASER_MODE"
    const val EXTRA_LASER_ON = "on"
    const val ACTION_HAPTIC = "org.lineageos.pianoparts.action.PEN_HAPTIC"
    const val ACTION_SHOW_MENU = "org.lineageos.pianoparts.action.PEN_SHORTCUT_MENU"
    const val ACTION_UPDATE_FIRMWARE = "org.lineageos.pianoparts.action.PEN_UPDATE_FIRMWARE"
    const val EXTRA_HAPTIC_TYPE = "haptic_type"

    const val DEFAULT_PRIMARY_BUTTON_ACTION = Action.OPEN_NOTES
    const val DEFAULT_SECONDARY_BUTTON_ACTION = Action.SCREENSHOT
    const val DEFAULT_PINCH_ACTION = Action.MENU

    fun sanitizeAction(value: String?, defaultValue: String): String =
        value?.takeIf(Action.SUPPORTED::contains) ?: defaultValue
}
