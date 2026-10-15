/* SPDX-License-Identifier: Apache-2.0 */
package org.lineageos.pianoparts.stylus

import android.content.Context
import android.provider.Settings

/** Stock touch-film controls are enabled only for P81C, not every MIPP pen. */
internal object PenControls {
    const val DOUBLE_TAP = "stylus_double_click_status"
    const val PINCH = "stylus_pinch_status"
    const val PRESSURE = "stylus_pinch_pressure_adjust"
    const val SLIDE = "stylus_slide_option"
    const val HOVER = "stylus_hover_enable"
    const val PINCH_FEEDBACK = "piano_stylus_pinch_feedback"
    const val WRITING_TYPE = "piano_stylus_writing_type"
    const val WRITING_FEEDBACK = "piano_stylus_writing_feedback"
    val keys = listOf(DOUBLE_TAP, PINCH, PRESSURE, SLIDE, HOVER, PINCH_FEEDBACK,
        WRITING_TYPE, WRITING_FEEDBACK)

    data class Command(val bytes: ByteArray, val replyLength: Int, val successStatus: Boolean)

    fun isTouchFilm(status: StylusStatus) =
        status.vendorId == 34 && status.productId in setOf(20609, 12929)

    fun value(context: Context, key: String, defaultValue: Int): Int =
        Settings.System.getInt(context.contentResolver, key, defaultValue)

    fun commands(context: Context, status: StylusStatus): List<Command> {
        if (!isTouchFilm(status)) return emptyList()
        val level = value(context, PRESSURE, 2).coerceIn(0, 4)
        val enabled = value(context, PINCH, 5) != 0
        val pressure = if (enabled) intArrayOf(300, 450, 600, 750, 900)[level] else 0
        val result = mutableListOf(
            Command(byteArrayOf(0x5f, 1, if (value(context, DOUBLE_TAP, 1) != 0) 1 else 0), 1, true),
            Command(MippProtocol.pressure(pressure, if (enabled) 300 else 0), 1, true),
            Command(byteArrayOf(0x5a, 1, value(context, PINCH_FEEDBACK, 3).coerceIn(0, 5).toByte()), 1, true),
            Command(byteArrayOf(0x63, 1, if (value(context, SLIDE, 1) != 0) 1 else 0), 1, true),
        )
        // Preserve firmware defaults unless the user explicitly configures drawing feedback.
        if (Settings.System.getString(context.contentResolver, WRITING_TYPE) != null) {
            result.add(Command(byteArrayOf(0x59, 2,
                value(context, WRITING_TYPE, 1).coerceIn(1, 4).toByte(),
                value(context, WRITING_FEEDBACK, 0).coerceIn(0, 5).toByte()), 1, true))
        }
        return result
    }

    fun haptic(context: Context, type: Int): ByteArray? {
        if (type !in 1..4) return null
        val level = value(context, PRESSURE, 2).coerceIn(0, 4)
        val amplitude = if (type in 1..2) intArrayOf(96, 134, 172, 210, 248)[level] else 128
        return byteArrayOf(0x5e, 2, type.toByte(), amplitude.toByte())
    }
}
