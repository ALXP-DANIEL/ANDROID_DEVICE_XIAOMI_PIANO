/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.alxp.parts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.alxp.parts.services.AirPodsService

/** Enabled together with the service by the main switch. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        context.startForegroundService(Intent(context, AirPodsService::class.java))
    }
}
