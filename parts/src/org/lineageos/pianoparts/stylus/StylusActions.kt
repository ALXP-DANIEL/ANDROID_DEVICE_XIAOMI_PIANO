/*
 * Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pianoparts.stylus

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.hardware.input.InputManager
import android.media.session.MediaSessionLegacyHelper
import android.os.Handler
import android.os.SystemClock
import android.os.UserHandle
import android.util.Log
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.WindowManager
import com.android.internal.util.ScreenshotHelper

object StylusActions {
    fun run(context: Context, handler: Handler, action: String) {
        when (action) {
            StylusSettingsContract.Action.NONE -> Unit
            StylusSettingsContract.Action.OPEN_NOTES -> openNotes(context)
            StylusSettingsContract.Action.SCREENSHOT -> takePartialScreenshot(context, handler)
            StylusSettingsContract.Action.BACK -> injectKey(context, KeyEvent.KEYCODE_BACK)
            StylusSettingsContract.Action.HOME -> injectKey(context, KeyEvent.KEYCODE_HOME)
            StylusSettingsContract.Action.RECENTS -> injectKey(context, KeyEvent.KEYCODE_APP_SWITCH)
            StylusSettingsContract.Action.MEDIA_PLAY_PAUSE -> sendMediaPlayPause(context)
            else -> Log.w(TAG, "Ignoring unsupported stylus action $action")
        }
    }

    private fun openNotes(context: Context) {
        val intent = Intent(Intent.ACTION_CREATE_NOTE).apply {
            putExtra(Intent.EXTRA_USE_STYLUS_MODE, true)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                    Intent.FLAG_ACTIVITY_NEW_DOCUMENT,
            )
        }
        try {
            context.startActivityAsUser(intent, UserHandle.CURRENT)
        } catch (exception: ActivityNotFoundException) {
            Log.w(TAG, "No notes role holder handles ACTION_CREATE_NOTE", exception)
        } catch (exception: RuntimeException) {
            Log.e(TAG, "Could not open notes", exception)
        }
    }

    private fun takePartialScreenshot(context: Context, handler: Handler) {
        try {
            ScreenshotHelper(context).takeScreenshot(
                WindowManager.TAKE_SCREENSHOT_SELECTED_REGION,
                WindowManager.ScreenshotSource.SCREENSHOT_KEY_OTHER,
                handler,
                null,
            )
        } catch (exception: RuntimeException) {
            Log.e(TAG, "Could not take a partial screenshot", exception)
        }
    }

    private fun injectKey(context: Context, keyCode: Int) {
        val inputManager = context.getSystemService(InputManager::class.java) ?: return
        val now = SystemClock.uptimeMillis()
        val down = KeyEvent(
            now,
            now,
            KeyEvent.ACTION_DOWN,
            keyCode,
            0,
            0,
            KeyCharacterMap.VIRTUAL_KEYBOARD,
            0,
            KeyEvent.FLAG_FROM_SYSTEM,
            InputDevice.SOURCE_KEYBOARD,
        )
        val up = KeyEvent.changeAction(down, KeyEvent.ACTION_UP)
        inputManager.injectInputEvent(down, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC)
        inputManager.injectInputEvent(up, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC)
    }

    private fun sendMediaPlayPause(context: Context) {
        try {
            val helper = MediaSessionLegacyHelper.getHelper(context)
            val now = SystemClock.uptimeMillis()
            val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0)
            helper.sendMediaButtonEvent(down, true)
            helper.sendMediaButtonEvent(KeyEvent.changeAction(down, KeyEvent.ACTION_UP), true)
        } catch (exception: RuntimeException) {
            Log.e(TAG, "Could not send media play/pause", exception)
        }
    }

    private const val TAG = "PianoParts.Actions"
}
