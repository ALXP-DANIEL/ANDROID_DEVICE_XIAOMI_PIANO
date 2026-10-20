/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.alxp.parts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts the features that are switched on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Feature.entries.filter { it.isOn(context) }.forEach { it.start(context) }
    }
}
