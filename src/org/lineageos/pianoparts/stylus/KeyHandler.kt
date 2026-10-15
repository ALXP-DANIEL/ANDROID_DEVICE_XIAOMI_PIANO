/*
 * Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

@file:Suppress("DEPRECATION")

package org.lineageos.pianoparts.stylus

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
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
import org.lineageos.pianoparts.pencilengine.PencilEngineLease

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
    private val laserKeyLease = PenLaserKeyLease()
    private val toolDispatch = PenToolDispatchPolicy()

    init {
        // A volatile, five-second authorization renewed only while the Parts
        // laser overlay exists. No persisted toggle can seize squeeze after a crash.
        try {
            context.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    laserKeyLease.update(intent.getBooleanExtra("active", false),
                        intent.getIntExtra("device", -1),
                        intent.getIntExtra("vendor", -1), intent.getIntExtra("product", -1),
                        SystemClock.uptimeMillis())
                }
            }, IntentFilter(PenLaserController.CONTROL), null, handler, Context.RECEIVER_NOT_EXPORTED)
        } catch (exception: RuntimeException) {
            Log.w(TAG, "Optional pen laser key observer unavailable", exception)
        }
        // Publish only from the loaded system-server handler, never from the APK
        // process. APK-only updates cannot advertise an absent dispatch backend.
        try {
            context.contentResolver.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.BOOT_COUNT), false,
                object : ContentObserver(handler) {
                    override fun onChange(selfChange: Boolean) { publishToolDispatchReadiness() }
                })
            publishToolDispatchReadiness()
        } catch (exception: RuntimeException) {
            Log.w(TAG, "Cannot publish pen tool dispatch readiness", exception)
        }
    }

    private fun hasVirtualLaserLease(device: InputDevice?): Boolean =
        device != null && laserKeyLease.accepts(device.id, device.vendorId, device.productId,
            SystemClock.uptimeMillis())

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

            KeyEvent.KEYCODE_BUTTON_8 -> ButtonSetting(StylusSettingsContract.DOUBLE_TAP_ACTION,
                StylusSettingsContract.Action.NONE)
            KeyEvent.KEYCODE_BUTTON_9 -> ButtonSetting(StylusSettingsContract.SLIDE_UP_ACTION,
                StylusSettingsContract.Action.NONE)
            KeyEvent.KEYCODE_BUTTON_10 -> ButtonSetting(StylusSettingsContract.SLIDE_DOWN_ACTION,
                StylusSettingsContract.Action.NONE)
            else -> return event
        }

        if (!XiaomiStylusDevice.isStylus(event.device)) return event
        val touchFilmKey = event.keyCode in KeyEvent.KEYCODE_BUTTON_8..KeyEvent.KEYCODE_BUTTON_10
        if (touchFilmKey && XiaomiStylusDevice.typeOf(event.device) != 8) return event
        if (touchFilmKey) {
            val editor = foregroundEditor()
            val enabled = editor == null || editorTouchFilmEnabled(editor)
            if (!toolDispatch.accepts(event.deviceId, event.keyCode, event.action,
                    event.repeatCount, event.downTime, event.eventTime, enabled)) return null
        }

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
        val stylusType = XiaomiStylusDevice.typeOf(event.device)
        val effectiveSetting = if (event.keyCode == KeyEvent.KEYCODE_BUTTON_7 && stylusType != 8)
            setting.copy(defaultValue = StylusSettingsContract.Action.RECENTS) else setting
        handler.post { handleEventOnMain(eventIdentity, effectiveSetting, stylusType) }
        val virtualLaserKey = event.keyCode == KeyEvent.KEYCODE_BUTTON_7 && stylusType == 8 &&
            hasVirtualLaserLease(event.device)
        return if ((readAction(setting) == StylusSettingsContract.Action.LASER || virtualLaserKey) &&
            stylusType in setOf(3, 8) && PenLaserController.isAvailable(context)) null else event
    }

    private fun handleEventOnMain(event: EventIdentity, setting: ButtonSetting, stylusType: Int) {
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
                executeAction(StylusSettingsContract.Action.OPEN_NOTES, "off_screen")
            }
            return
        }

        val virtualLaserKey = event.keyCode == KeyEvent.KEYCODE_BUTTON_7 && stylusType == 8 &&
            hasVirtualLaserLease(context.getSystemService(InputManager::class.java)
                ?.getInputDevice(event.deviceId))
        if (readAction(setting) == StylusSettingsContract.Action.LASER || virtualLaserKey) {
            if (stylusType in setOf(3, 8) && PenLaserController.isAvailable(context)) {
                context.sendBroadcastAsUser(Intent(PenLaserController.KEY).setPackage(PARTS_PACKAGE)
                    .putExtra("device", event.deviceId).putExtra("action", event.action)
                    .putExtra("repeat", event.repeatCount), UserHandle.SYSTEM)
            }
            return
        }

        // Stock editors own their pen tool keys. Do not also open the global
        // palette or arm quick-note over the editor's brush/color toolbar.
        if (isNotesEditorForeground()) {
            cancelPending(event.keyCode)
            stylusTouchMonitor.cancel(event.keyCode)
            // .44 sends the gesture feedback before dispatching tool keys to
            // the editor. Keeping global actions out must not also mute it.
            if (stylusType == 8 && event.action == KeyEvent.ACTION_DOWN &&
                event.repeatCount == 0) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_BUTTON_8 -> sendPenHaptic(3)
                    KeyEvent.KEYCODE_BUTTON_9, KeyEvent.KEYCODE_BUTTON_10 -> sendPenHaptic(4)
                }
            }
            return
        }

        if (event.keyCode in KeyEvent.KEYCODE_BUTTON_7..KeyEvent.KEYCODE_BUTTON_10) {
            if (stylusType == 8 && event.keyCode in KeyEvent.KEYCODE_BUTTON_9..KeyEvent.KEYCODE_BUTTON_10 &&
                event.action == KeyEvent.ACTION_DOWN &&
                hasVirtualLaserLease(context.getSystemService(InputManager::class.java)
                    ?.getInputDevice(event.deviceId))) {
                // Stock slide gestures exit P81C virtual laser before ordinary dispatch.
                context.sendBroadcastAsUser(Intent(PenLaserController.EXIT)
                    .setPackage(PARTS_PACKAGE), UserHandle.SYSTEM)
            }
            // A squeeze is a single gesture, so act on it at once.
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                val haptic = when (event.keyCode) {
                    KeyEvent.KEYCODE_BUTTON_7 -> 1
                    KeyEvent.KEYCODE_BUTTON_8 -> 3
                    else -> 4
                }
                if (stylusType == 8) sendPenHaptic(haptic)
                executeAction(readAction(setting))
            } else if (event.action == KeyEvent.ACTION_UP &&
                event.keyCode == KeyEvent.KEYCODE_BUTTON_7 && stylusType == 8
            ) sendPenHaptic(2)
            return
        }

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0 && pendingActions[event.keyCode] == null) {
                    val action = readAction(setting)
                    val runnable = Runnable {
                        pendingActions.remove(event.keyCode)
                        if (!powerManager.isInteractive || isNotesEditorForeground()) return@Runnable
                        val execute = Runnable { if (!isNotesEditorForeground()) executeAction(action) }
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

    private fun sendPenHaptic(type: Int) {
        context.sendBroadcastAsUser(Intent(StylusSettingsContract.ACTION_HAPTIC)
            .setPackage(PARTS_PACKAGE).putExtra(StylusSettingsContract.EXTRA_HAPTIC_TYPE, type), UserHandle.SYSTEM)
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

    private fun foregroundEditor(): String? = try {
        val task = context.getSystemService(ActivityManager::class.java)
            ?.getRunningTasks(1)?.firstOrNull()
        task?.topActivity?.packageName?.takeIf { NotesLaunchContract.ownsToolKeys(it) }
    } catch (_: RuntimeException) { null }

    private fun isNotesEditorForeground(): Boolean = foregroundEditor() != null

    private fun publishToolDispatchReadiness() {
        val boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
        if (boot >= 0) Settings.Global.putInt(context.contentResolver,
            PencilEngineLease.TOUCH_FILM_READY, boot)
    }

    private fun editorTouchFilmEnabled(editor: String): Boolean {
        if (editor == NotesLaunchContract.CREATION) return true // Stock built-in editor exception.
        return try {
            val user = ActivityManager.getCurrentUser()
            val uid = context.packageManager.getPackageUidAsUser(editor, user)
            PencilEngineLease.accepts(Settings.Secure.getStringForUser(context.contentResolver,
                PencilEngineLease.TOUCH_FILM, user),
                Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1),
                uid, SystemClock.elapsedRealtime())
        } catch (_: Exception) { false }
    }

    private fun executeAction(action: String, notesScene: String? = null) {
        if (action == StylusSettingsContract.Action.MENU) {
            context.sendBroadcastAsUser(Intent(StylusSettingsContract.ACTION_SHOW_MENU)
                .setPackage(PARTS_PACKAGE).putExtra("x", stylusTouchMonitor.hoverX)
                .putExtra("y", stylusTouchMonitor.hoverY), UserHandle.SYSTEM)
            return
        }
        try {
            StylusActions.run(context, handler, action, notesScene)
        } catch (exception: RuntimeException) {
            Log.e(TAG, "Stylus action $action failed", exception)
        }
    }

    private class StylusTouchMonitor(
        context: Context,
        private val handler: Handler,
    ) {
        private val inputManager = context.getSystemService(InputManager::class.java)

        var hoverX = -1f
            private set
        var hoverY = -1f
            private set
        init { handler.post { ensureMonitor() } }
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
            if (event.isFromSource(InputDevice.SOURCE_STYLUS) && event.displayId == Display.DEFAULT_DISPLAY) {
                when (event.actionMasked) {
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_HOVER_EXIT -> { hoverX = -1f; hoverY = -1f }
                    MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_MOVE -> {
                        hoverX = event.x; hoverY = event.y
                    }
                }
            }
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
