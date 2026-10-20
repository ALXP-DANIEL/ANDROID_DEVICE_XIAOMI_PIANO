/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.alxp.parts.mac

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import java.nio.ByteBuffer

/** Full-screen view of the Mac's extra display. Touch and pen drive the Mac pointer. */
class SidecarActivity : Activity(), SurfaceHolder.Callback {

    companion object {
        private const val TAG = "AlxpSidecar"
        private var current: SidecarActivity? = null
        /** Input goes out in order on one thread, off the UI thread. */
        private val sender = java.util.concurrent.Executors.newSingleThreadExecutor()

        fun open(context: Context, width: Int, height: Int) {
            context.startActivity(Intent(context, SidecarActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("width", width).putExtra("height", height))
        }

        fun closeAll() {
            current?.finish()
        }
    }

    private var codec: MediaCodec? = null
    private var width = 0
    private var height = 0
    private var started = 0L
    private var lastScroll: Pair<Float, Float>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = this
        width = intent.getIntExtra("width", 2560)
        height = intent.getIntExtra("height", 1600)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val view = SurfaceView(this)
        view.holder.addCallback(this)
        setContentView(view)
        window.insetsController?.apply {
            hide(WindowInsets.Type.systemBars())
            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onDestroy() {
        if (current == this) current = null
        super.onDestroy()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        format.setInteger(MediaFormat.KEY_PRIORITY, 0)
        codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
            configure(format, holder.surface, null, 0)
            start()
        }
        started = System.nanoTime()
        MacLinkService.videoSink = ::decode
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        MacLinkService.videoSink = null
        codec?.let {
            runCatching { it.stop() }
            it.release()
        }
        codec = null
    }

    /** One Annex B access unit per frame from the Mac. */
    private fun decode(frame: ByteArray) {
        val decoder = codec ?: return
        try {
            val index = decoder.dequeueInputBuffer(20_000)
            if (index >= 0) {
                decoder.getInputBuffer(index)!!.apply { clear(); put(frame) }
                decoder.queueInputBuffer(index, 0, frame.size,
                    (System.nanoTime() - started) / 1000, 0)
            }
            val info = MediaCodec.BufferInfo()
            while (true) {
                val out = decoder.dequeueOutputBuffer(info, 0)
                if (out < 0) break
                decoder.releaseOutputBuffer(out, true)
            }
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Decoder stopped", e)
        }
    }

    // Touch: one finger or the pen moves and clicks; two fingers scroll.
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val link = MacLinkService.link ?: return true
        val view = window.decorView
        if (event.pointerCount >= 2) {
            val x = (event.getX(0) + event.getX(1)) / 2
            val y = (event.getY(0) + event.getY(1)) / 2
            lastScroll?.let { (px, py) -> send(link, MacLink.SCROLL, x - px, y - py, 0) }
            lastScroll = if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) null else x to y
            return true
        }
        lastScroll = null
        val action = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> 1
            MotionEvent.ACTION_MOVE -> 2
            MotionEvent.ACTION_UP -> 3
            else -> return true
        }
        send(link, MacLink.POINTER, event.x / view.width, event.y / view.height, action)
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        // Hovering pen moves the pointer without clicking.
        if (event.actionMasked == MotionEvent.ACTION_HOVER_MOVE) {
            val link = MacLinkService.link ?: return true
            val view = window.decorView
            send(link, MacLink.POINTER, event.x / view.width, event.y / view.height, 0)
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    private fun send(link: MacLink, type: Byte, a: Float, b: Float, action: Int) {
        val payload = ByteBuffer.allocate(9).put(action.toByte()).putFloat(a).putFloat(b).array()
        sender.execute { runCatching { link.send(type, payload) } }
    }
}
