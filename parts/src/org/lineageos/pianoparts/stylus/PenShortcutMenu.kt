/* SPDX-License-Identifier: Apache-2.0 */
package org.lineageos.pianoparts.stylus

import android.app.ActivityManager
import android.app.ActivityTaskManager
import android.app.KeyguardManager
import android.app.TaskStackListener
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Handler
import android.os.UserHandle
import android.view.WindowInsets
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import org.lineageos.pianoparts.R
import org.lineageos.pianoparts.StylusSettingsActivity

/** Local system menu; actions use platform roles rather than hard-coded Xiaomi packages. */
internal class PenShortcutMenu(private val context: Context, private val handler: Handler,
    private val canShow: () -> Boolean) {
    private val windows = context.getSystemService(WindowManager::class.java)
    private val activities = context.getSystemService(ActivityManager::class.java)
    private val keyguard = context.getSystemService(KeyguardManager::class.java)
    private var view: FrameLayout? = null
    private var started = false
    private val taskListener = object : TaskStackListener() {
        override fun onTaskMovedToFront(task: ActivityManager.RunningTaskInfo) { handler.post { hide() } }
        override fun onLockTaskModeChanged(mode: Int) { if (mode != ActivityManager.LOCK_TASK_MODE_NONE) handler.post { hide() } }
        override fun onActivityPinned(packageName: String?, userId: Int, taskId: Int, stackId: Int) {
            handler.post { hide() }
        }
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == StylusSettingsContract.ACTION_SHOW_MENU) {
                show(intent.getFloatExtra("x", -1f), intent.getFloatExtra("y", -1f))
            } else hide()
        }
    }

    fun start() {
        if (started) return
        context.registerReceiver(receiver, IntentFilter().apply {
            addAction(StylusSettingsContract.ACTION_SHOW_MENU)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
            addAction(Intent.ACTION_CONFIGURATION_CHANGED)
            addAction(Intent.ACTION_USER_SWITCHED)
        }, null, handler, Context.RECEIVER_NOT_EXPORTED)
        started = true
    }

    fun close() {
        hide()
        if (started) context.unregisterReceiver(receiver)
        started = false
    }

    private fun show(x: Float, y: Float) {
        if (view != null) { hide(); return }
        if (keyguard.isKeyguardLocked || activities.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE || !canShow()) return
        val user = UserHandle.of(ActivityManager.getCurrentUser())
        val userContext = context.createContextAsUser(user, 0)
        val assistant = Intent(Intent.ACTION_ASSIST).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val notes = Intent(Intent.ACTION_CREATE_NOTE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val entries = mutableListOf<Entry>()
        if (assistant.resolveActivity(userContext.packageManager) != null) {
            entries.add(Entry(R.string.stylus_menu_assistant, android.R.drawable.ic_btn_speak_now) {
                context.startActivityAsUser(assistant, user)
            })
        }
        if (notes.resolveActivity(userContext.packageManager) != null) {
            entries.add(Entry(R.string.stylus_menu_notes, android.R.drawable.ic_menu_edit) {
                StylusActions.run(context, handler, StylusSettingsContract.Action.OPEN_NOTES)
            })
        }
        entries.add(Entry(R.string.stylus_menu_screenshot, android.R.drawable.ic_menu_camera) {
            StylusActions.run(context, handler, StylusSettingsContract.Action.SCREENSHOT)
        })
        entries.add(Entry(R.string.stylus_menu_settings, android.R.drawable.ic_menu_manage) {
            val settings = Intent(context, StylusSettingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivityAsUser(settings, user)
        })
        // Laser is added only when its native input bridge can provide interception and cursor hiding.
        val geometry = PenMenuGeometry(entries.size)
        val density = context.resources.displayMetrics.density
        val menu = object : FrameLayout(context) {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor(if (resources.configuration.uiMode and
                    Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES) "#191919" else "#f7f7f7")
                style = Paint.Style.STROKE
                strokeWidth = geometry.stroke * density
                strokeCap = Paint.Cap.ROUND
            }
            override fun onDraw(canvas: Canvas) {
                val cx = -geometry.left * density
                val cy = -geometry.top * density
                val radius = geometry.radius * density
                canvas.drawArc(RectF(cx - radius, cy - radius, cx + radius, cy + radius),
                    geometry.start, geometry.sweep, false, paint)
            }
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK) { hide(); return true }
                return super.dispatchKeyEvent(event)
            }
            override fun onTouchEvent(event: MotionEvent): Boolean {
                if (event.actionMasked == MotionEvent.ACTION_UP) hide()
                return true
            }
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) { hide(); return true }
                return super.dispatchTouchEvent(event)
            }
        }
        menu.setWillNotDraw(false)
        for ((index, entry) in entries.withIndex()) {
            val button = ImageButton(context).apply {
                setImageResource(entry.icon)
                contentDescription = context.getString(entry.label)
                val backgroundId = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless))
                try { setBackgroundResource(backgroundId.getResourceId(0, 0)) } finally { backgroundId.recycle() }
                setOnTouchListener { _, event ->
                    val scale = if (event.actionMasked == MotionEvent.ACTION_DOWN) 1.2f else 1f
                    scaleX = scale; scaleY = scale
                    false
                }
                setOnClickListener { hide(); invoke(entry) }
            }
            menu.addView(button, FrameLayout.LayoutParams((52 * density).toInt(), (52 * density).toInt()).apply {
                leftMargin = ((geometry.x[index] - 26) * density).toInt()
                topMargin = ((geometry.y[index] - 26) * density).toInt()
            })
        }
        val metrics = windows.currentWindowMetrics
        val frame = metrics.bounds
        val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        val width = (geometry.width * density).toInt()
        val height = (geometry.height * density).toInt()
        val px = if (x.isFinite() && x >= 0) x else frame.width().toFloat()
        val py = if (y.isFinite() && y >= 0) y else frame.height().toFloat()
        val params = WindowManager.LayoutParams(width, height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, 0x40120, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            setFitInsetsTypes(0)
            privateFlags = privateFlags or WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS
            this.x = PenMenuGeometry.clamp(px - width * .75f, insets.left + 10 * density,
                frame.width() - insets.right - width - 10 * density).toInt()
            this.y = PenMenuGeometry.clamp(py - height / 2f, insets.top.toFloat(),
                (frame.height() - insets.bottom - height).toFloat()).toInt()
            setTitle("Piano pen shortcuts")
        }
        try {
            windows.addView(menu, params)
            view = menu
            ActivityTaskManager.getService().registerTaskStackListener(taskListener)
        } catch (exception: Exception) {
            Log.w("PianoStylus", "Could not show pen shortcut menu", exception)
            if (menu.isAttachedToWindow) windows.removeViewImmediate(menu)
            view = null
        }
    }

    private fun invoke(entry: Entry) {
        try { entry.action() } catch (exception: RuntimeException) {
            Log.w("PianoStylus", "Pen shortcut failed", exception)
        }
    }

    fun hide() {
        val menu = view ?: return
        view = null
        try { ActivityTaskManager.getService().unregisterTaskStackListener(taskListener) }
        catch (exception: Exception) { Log.w("PianoStylus", "Could not remove task listener", exception) }
        if (menu.isAttachedToWindow) windows.removeViewImmediate(menu)
    }

    private data class Entry(val label: Int, val icon: Int, val action: () -> Unit)
}
