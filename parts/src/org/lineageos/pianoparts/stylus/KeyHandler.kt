/*
 * Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

@file:Suppress("DEPRECATION")

package org.lineageos.pianoparts.stylus

import android.content.Context
import android.content.Intent
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import android.util.SparseArray
import android.view.Display
import android.view.InputDevice
import android.view.InputEvent
import android.view.InputEventReceiver
import android.view.InputMonitor
import android.view.KeyEvent
import android.view.MotionEvent
import com.android.internal.os.DeviceKeyHandler
import java.util.ArrayDeque

/**
 * Global piano stylus-button handler loaded into system_server.
 */
class KeyHandler(private val context: Context) : DeviceKeyHandler {
    private val handler = Handler(Looper.getMainLooper())
    private val powerManager = context.getSystemService(PowerManager::class.java)
    private val pendingActions = SparseArray<Runnable>()
    private val stylusTouchMonitor = StylusTouchMonitor(context, handler)
    private val recentEvents = HashSet<EventIdentity>()
    private val recentEventOrder = ArrayDeque<EventIdentity>()

    override fun handleKeyEvent(event: KeyEvent): KeyEvent? {
        val setting = when (event.keyCode) {
            KeyEvent.KEYCODE_PAGE_DOWN -> ButtonSetting(
                StylusSettingsContract.PRIMARY_BUTTON_ACTION,
                StylusSettingsContract.DEFAULT_PRIMARY_BUTTON_ACTION,
            )

            KeyEvent.KEYCODE_PAGE_UP -> ButtonSetting(
                StylusSettingsContract.SECONDARY_BUTTON_ACTION,
                StylusSettingsContract.DEFAULT_SECONDARY_BUTTON_ACTION,
            )

            KeyEvent.KEYCODE_BUTTON_7 -> ButtonSetting(
                StylusSettingsContract.PINCH_ACTION,
                StylusSettingsContract.DEFAULT_PINCH_ACTION,
            )

            else -> return event
        }

        if (!XiaomiStylusDevice.isStylus(event.device)) return event

        // PhoneWindowManager invokes a DeviceKeyHandler once before queueing
        // and again before dispatching. Those callbacks can run on different
        // input threads. Snapshot and post the event so all mutable gesture
        // state is confined to the system-server main looper; rememberEvent()
        // removes the duplicate policy-stage delivery.
        val eventIdentity = EventIdentity(
            event.deviceId,
            event.keyCode,
            event.scanCode,
            event.action,
            event.repeatCount,
            event.downTime,
            event.eventTime,
        )
        handler.post { handleEventOnMain(eventIdentity, setting) }
        return event
    }

    private fun handleEventOnMain(event: EventIdentity, setting: ButtonSetting) {
        if (!rememberEvent(event)) return
        if (!powerManager.isInteractive) {
            cancelPending(event.keyCode)
            stylusTouchMonitor.cancel(event.keyCode)
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 &&
                readAction(setting) == StylusSettingsContract.Action.OPEN_NOTES &&
                isScreenOffQuickNoteEnabled()
            ) {
                // Stock HyperOS opens a quick note from the pen with the screen off.
                powerManager.wakeUp(
                    event.eventTime,
                    PowerManager.WAKE_REASON_WAKE_KEY,
                    "$TAG:quick_note",
                )
                executeAction(StylusSettingsContract.Action.OPEN_NOTES)
            }
            return
        }

        if (readAction(setting) == StylusSettingsContract.Action.LASER) {
            // Like the stock P81C laser key: laser while the button is held.
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                setLaser(true)
            } else if (event.action != KeyEvent.ACTION_DOWN) {
                setLaser(false)
            }
            return
        }

        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_7) {
            // A squeeze is a single gesture, so act on it at once.
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                executeAction(readAction(setting))
            }
            return
        }

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0 && pendingActions[event.keyCode] == null) {
                    val action = readAction(setting)
                    val runnable = Runnable {
                        pendingActions.remove(event.keyCode)
                        if (!powerManager.isInteractive) return@Runnable
                        val execute = Runnable { executeAction(action) }
                        if (action == StylusSettingsContract.Action.OPEN_NOTES) {
                            stylusTouchMonitor.arm(event.keyCode, execute)
                        } else {
                            execute.run()
                        }
                    }
                    pendingActions.put(event.keyCode, runnable)
                    handler.postAtTime(runnable, event.downTime + LONG_PRESS_TIMEOUT_MS)
                }
            }

            KeyEvent.ACTION_UP -> {
                cancelPending(event.keyCode)
                stylusTouchMonitor.cancel(event.keyCode)
            }

            else -> {
                cancelPending(event.keyCode)
                stylusTouchMonitor.cancel(event.keyCode)
            }
        }
    }

    private fun rememberEvent(event: EventIdentity): Boolean {
        if (!recentEvents.add(event)) return false
        recentEventOrder.addLast(event)
        while (recentEventOrder.size > RECENT_EVENT_LIMIT) {
            recentEvents.remove(recentEventOrder.removeFirst())
        }
        return true
    }

    private fun readAction(setting: ButtonSetting): String {
        val value = Settings.Secure.getStringForUser(
            context.contentResolver,
            setting.key,
            UserHandle.USER_CURRENT,
        )
        return StylusSettingsContract.sanitizeAction(value, setting.defaultValue)
    }

    private fun setLaser(on: Boolean) {
        context.sendBroadcastAsUser(
            Intent(StylusSettingsContract.ACTION_LASER_MODE)
                .setPackage(PARTS_PACKAGE)
                .putExtra(StylusSettingsContract.EXTRA_LASER_ON, on),
            UserHandle.SYSTEM,
        )
    }

    private fun isScreenOffQuickNoteEnabled(): Boolean =
        Settings.Secure.getIntForUser(
            context.contentResolver,
            StylusSettingsContract.SCREEN_OFF_QUICK_NOTE,
            if (StylusSettingsContract.DEFAULT_SCREEN_OFF_QUICK_NOTE) 1 else 0,
            UserHandle.USER_CURRENT,
        ) != 0

    private fun cancelPending(keyCode: Int) {
        val runnable = pendingActions[keyCode] ?: return
        handler.removeCallbacks(runnable)
        pendingActions.remove(keyCode)
    }

    private fun executeAction(action: String) {
        try {
            StylusActions.run(context, handler, action)
        } catch (exception: RuntimeException) {
            Log.e(TAG, "Stylus action $action failed", exception)
        }
    }

    private class StylusTouchMonitor(
        context: Context,
        private val handler: Handler,
    ) {
        private val inputManager = context.getSystemService(InputManager::class.java)

        private var inputMonitor: InputMonitor? = null
        private var inputReceiver: InputEventReceiver? = null
        private var armedKeyCode = KeyEvent.KEYCODE_UNKNOWN
        private var armedAction: Runnable? = null
        private var timeoutAction: Runnable? = null

        fun arm(keyCode: Int, action: Runnable): Boolean {
            if (!ensureMonitor()) return false
            clearArm()
            armedKeyCode = keyCode
            armedAction = action
            timeoutAction = Runnable {
                if (armedKeyCode == keyCode) clearArm()
            }.also { handler.postDelayed(it, HOLD_TIMEOUT_MS) }
            return true
        }

        fun cancel(keyCode: Int) {
            if (armedKeyCode != keyCode) return
            clearArm()
        }

        private fun clearArm() {
            timeoutAction?.let(handler::removeCallbacks)
            timeoutAction = null
            armedKeyCode = KeyEvent.KEYCODE_UNKNOWN
            armedAction = null
        }

        private fun ensureMonitor(): Boolean {
            if (inputReceiver != null) return true
            val manager = inputManager ?: return false
            return try {
                val monitor = manager.monitorGestureInput(MONITOR_NAME, Display.DEFAULT_DISPLAY)
                val receiver = object : InputEventReceiver(monitor.inputChannel, handler.looper) {
                    override fun onInputEvent(event: InputEvent) {
                        try {
                            if (event is MotionEvent) handleMotionEvent(event)
                        } finally {
                            finishInputEvent(event, false)
                        }
                    }
                }
                inputMonitor = monitor
                inputReceiver = receiver
                true
            } catch (exception: RuntimeException) {
                Log.e(TAG, "Could not monitor the stylus button touch", exception)
                false
            }
        }

        private fun handleMotionEvent(event: MotionEvent) {
            if (armedAction == null || event.actionMasked != MotionEvent.ACTION_DOWN ||
                !event.isFromSource(InputDevice.SOURCE_STYLUS) ||
                event.getToolType(event.actionIndex) != MotionEvent.TOOL_TYPE_STYLUS
            ) {
                return
            }

            val action = armedAction
            clearArm()
            // InputEventReceiver already runs on the handler looper. Posting
            // keeps action execution outside the native input callback frame.
            if (action != null) handler.post(action)
        }

        companion object {
            private const val MONITOR_NAME = "Piano stylus button monitor"
            private const val HOLD_TIMEOUT_MS = 10_000L
        }
    }

    private data class ButtonSetting(val key: String, val defaultValue: String)

    private data class EventIdentity(
        val deviceId: Int,
        val keyCode: Int,
        val scanCode: Int,
        val action: Int,
        val repeatCount: Int,
        val downTime: Long,
        val eventTime: Long,
    )

    companion object {
        private const val TAG = "PianoParts.KeyHandler"
        private const val PARTS_PACKAGE = "org.lineageos.pianoparts"
        private const val LONG_PRESS_TIMEOUT_MS = 380L
        private const val RECENT_EVENT_LIMIT = 512
    }
}
