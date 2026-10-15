/* SPDX-License-Identifier: Apache-2.0 */
package org.lineageos.pianoparts.stylus

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Messenger

/**
 * Privately bound motion transport for the framework laser bridge.
 *
 * It skips ActivityManager broadcasts for ordinary frames. The service and
 * controller share the PianoParts main looper, so frames reach the renderer
 * directly without another queue or an extra 16 ms frame-delay timer.
 */
class PenLaserMotionService : Service() {
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { message ->
        if (message.what != MSG_MOTION) return@Handler false
        val data: Bundle = message.data
        val id = data.getInt("device", -1)
        val dx = data.getFloat("dx", Float.NaN)
        val dy = data.getFloat("dy", Float.NaN)
        if (id >= 0 && dx.isFinite() && dy.isFinite()) {
            listener?.invoke(id, dx, dy)
        }
        true
    })

    override fun onBind(intent: Intent?): IBinder? =
        if (listener != null) messenger.binder else null

    companion object {
        const val MSG_MOTION = 1

        // Only the started, currently active controller may consume motion.
        @Volatile private var listener: ((Int, Float, Float) -> Unit)? = null

        fun attach(callback: (Int, Float, Float) -> Unit) {
            listener = callback
        }

        fun detach() {
            listener = null
        }
    }
}
