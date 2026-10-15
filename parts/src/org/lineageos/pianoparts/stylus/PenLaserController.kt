/* SPDX-License-Identifier: Apache-2.0 */
package org.lineageos.pianoparts.stylus

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.hardware.input.InputManager
import android.os.Handler
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import org.lineageos.pianoparts.R

/** Renderer backed by the paired native PointerChoreographer interception patch. */
internal class PenLaserController(private val context: Context, private val handler: Handler,
    private val sendMode: (Boolean) -> Unit, private val canShow: () -> Boolean) {
    private val windows = context.getSystemService(WindowManager::class.java)
    private val keyguard = context.getSystemService(KeyguardManager::class.java)
    private val inputs = context.getSystemService(InputManager::class.java)
    private var overlay: LaserView? = null
    private var device = -1
    private var generation = 0
    private var mode = 0
    private var keyDown = false
    private var held = false
    private var pressCount = 0
    private var lastDown = -300L
    private var started = false
    private var virtual = false
    private val heartbeat = object : Runnable {
        override fun run() { if (overlay != null) { control(true); handler.postDelayed(this, 2000) } }
    }
    private val hold = Runnable { if (keyDown) { held = true; present(); overlay?.setDrawing(mode == 1) } }
    private val click = Runnable {
        if (pressCount == 1) {
            mode = 1 - mode; sendMode(mode == 1); present(); overlay?.mode = mode
            if (mode == 0) overlay?.presenting = false
        }
    }
    private val stuckKey = Runnable { closePresentation() }
    private val remove = Runnable { closePresentation() }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                MOTION -> if (intent.getIntExtra("device", -1) == device && overlay != null) {
                    val dx = intent.getFloatExtra("dx", 0f); val dy = intent.getFloatExtra("dy", 0f)
                    if (dx.isFinite() && dy.isFinite()) {
                        overlay?.move(dx, dy)
                        handler.removeCallbacks(remove); handler.postDelayed(remove, 120000)
                    }
                }
                KEY -> handleKey(intent)
                TOGGLE -> {
                    if (!isAvailable(context)) return
                    if (overlay != null) closePresentation() else {
                        val pen = inputs.inputDeviceIds.asSequence().mapNotNull(inputs::getInputDevice).firstOrNull {
                            XiaomiStylusDevice.typeOf(it) in setOf(3, 8) && it.supportsSource(android.view.InputDevice.SOURCE_MOUSE)
                        } ?: return
                        device = pen.id; generation = XiaomiStylusDevice.typeOf(pen)
                        // Stock P81C virtual-laser selection sends mode 1 immediately;
                        // generation 3 starts in dot mode instead.
                        virtual = generation == 8; mode = if (virtual) 1 else 0
                        sendMode(virtual); present()
                    }
                }
                else -> closePresentation()
            }
        }
    }
    fun start() {
        if (started) return
        context.registerReceiver(receiver, IntentFilter().apply {
            addAction(KEY); addAction(MOTION); addAction(TOGGLE)
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_SWITCHED)
            addAction(Intent.ACTION_CONFIGURATION_CHANGED); addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
        }, null, handler, Context.RECEIVER_NOT_EXPORTED)
        started = true
    }
    private fun handleKey(intent: Intent) {
        val id = intent.getIntExtra("device", -1)
        val pen = inputs.getInputDevice(id) ?: return
        val type = XiaomiStylusDevice.typeOf(pen)
        if (type !in setOf(3, 8) || !isAvailable(context) || !canShow() || keyguard.isKeyguardLocked) return
        val mouse = if (pen.supportsSource(android.view.InputDevice.SOURCE_MOUSE)) pen else
            inputs.inputDeviceIds.asSequence().mapNotNull(inputs::getInputDevice).firstOrNull {
                it.vendorId == pen.vendorId && it.productId == pen.productId &&
                    it.supportsSource(android.view.InputDevice.SOURCE_MOUSE)
            } ?: return
        if (device != mouse.id) { closePresentation(); device = mouse.id; generation = type; virtual = type == 8 }
        val down = intent.getIntExtra("action", -1) == KeyEvent.ACTION_DOWN
        if (down && intent.getIntExtra("repeat", 0) != 0) return
        keyDown = down
        handler.removeCallbacks(hold); handler.removeCallbacks(stuckKey)
        if (down) {
            val now = SystemClock.uptimeMillis()
            pressCount = if (now - lastDown < 300) pressCount + 1 else 1; lastDown = now
            handler.removeCallbacks(click)
            handler.postDelayed(stuckKey, 240000)
            if (generation == 8 && virtual) {
                mode = 1; sendMode(true); present(); overlay?.mode = 1; overlay?.setDrawing(true)
            } else if (pressCount == 2) overlay?.clearPaths()
            else { held = false; handler.postDelayed(hold, 200) }
        } else {
            if (generation == 8 && virtual) { mode = 0; overlay?.mode = 0; overlay?.setDrawing(false) }
            else if (held) { overlay?.setDrawing(false); if (mode == 0) overlay?.presenting = false }
            else if (pressCount == 1) handler.postDelayed(click, 300)
        }
    }
    private fun present() {
        if (!started || !canShow() || keyguard.isKeyguardLocked || !isAvailable(context)) return
        handler.removeCallbacks(remove)
        if (overlay == null) {
            val view = LaserView(context)
            val params = WindowManager.LayoutParams(-1, -1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.TOP or Gravity.START
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                setFitInsetsTypes(0); setTrustedOverlay(); setTitle("Piano pen laser")
            }
            try { windows.addView(view, params); overlay = view; heartbeat.run() }
            catch (exception: RuntimeException) { android.util.Log.w("PianoStylus", "Laser overlay unavailable", exception); control(false); return }
        }
        overlay?.mode = mode; overlay?.presenting = true
        handler.postDelayed(remove, 120000)
    }
    private fun control(active: Boolean) {
        context.sendBroadcast(Intent(CONTROL).putExtra("device", device).putExtra("active", active))
    }
    fun closePresentation() {
        handler.removeCallbacks(heartbeat); handler.removeCallbacks(hold); handler.removeCallbacks(click)
        handler.removeCallbacks(stuckKey); handler.removeCallbacks(remove)
        control(false)
        overlay?.let { if (it.isAttachedToWindow) windows.removeViewImmediate(it) }
        overlay = null; keyDown = false; held = false; virtual = false; mode = 0
        sendMode(false)
    }
    fun close() { closePresentation(); if (started) context.unregisterReceiver(receiver); started = false }

    private class LaserView(context: Context) : View(context) {
        private val density = resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.RED; strokeWidth = 3*density; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        private val paths = mutableListOf<Path>()
        private val cursor = context.getDrawable(R.drawable.pen_laser_cursor)!!
        private var x = 0f; private var y = 0f; private var drawing = false
        private var points = 0
        var mode = 0; set(value) { field = value; invalidate() }
        var presenting = false; set(value) { field = value; invalidate() }
        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { x=w/2f; y=h/2f }
        fun setDrawing(value: Boolean) {
            if (value && !drawing) { if (paths.size >= 32) paths.removeAt(0); paths.add(Path().apply { moveTo(x,y) }); points=0 }
            drawing=value
        }
        fun clearPaths() { paths.clear(); points=0; invalidate() }
        fun move(dx: Float, dy: Float) {
            val nx=(x+dx).coerceIn(0f,width.toFloat());val ny=(y+dy).coerceIn(0f,height.toFloat())
            if (drawing && paths.isNotEmpty() && points++ < 8192) paths.last().quadTo(x,y,(x+nx)/2,(y+ny)/2)
            x=nx;y=ny;invalidate()
        }
        override fun onDraw(canvas: Canvas) {
            paint.style=Paint.Style.STROKE; paint.color=Color.RED; paths.forEach { canvas.drawPath(it,paint) }
            if (!presenting) return
            if (mode == 0) {
                paint.style=Paint.Style.FILL; paint.color=Color.parseColor("#50FF001F");canvas.drawCircle(x,y,8*density,paint)
                paint.color=Color.parseColor("#CCFF001F");canvas.drawCircle(x,y,4*density,paint)
            } else {
                val size=(32*density).toInt();val left=(x-size*.129f).toInt();val top=(y-size*.865f).toInt()
                cursor.setBounds(left,top,left+size,top+size);cursor.draw(canvas)
            }
        }
    }
    companion object {
        const val CONTROL="org.lineageos.pianoparts.action.LASER_CONTROL"
        const val MOTION="org.lineageos.pianoparts.action.LASER_MOTION"
        const val KEY="org.lineageos.pianoparts.action.LASER_KEY"
        const val TOGGLE="org.lineageos.pianoparts.action.LASER_TOGGLE"
        fun isReadyForBoot(bootCount: Int, marker: Int): Boolean = bootCount >= 0 && marker == bootCount
        fun isAvailable(context: Context): Boolean {
            val bootCount = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
            val marker = Settings.Global.getInt(context.contentResolver, "piano_pen_laser_bridge", -1)
            return isReadyForBoot(bootCount, marker)
        }
    }
}
