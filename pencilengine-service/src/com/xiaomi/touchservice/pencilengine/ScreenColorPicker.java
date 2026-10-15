/* SPDX-License-Identifier: Apache-2.0 */
package com.xiaomi.touchservice.pencilengine;

import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorSpace;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.hardware.HardwareBuffer;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.os.OutcomeReceiver;
import android.os.PowerManager;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.window.ScreenCapture;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.lineageos.pianoparts.pencilengine.PencilEngineBinder;
import org.lineageos.pianoparts.pencilengine.PencilEngineProtocol;
import org.lineageos.pianoparts.pencilengine.PencilEngineRegistry;

/**
 * Privileged, memory-only screenshot picker. No secure/protected capture, files,
 * network, arbitrary caller packages, or simulated colors. The transport separately
 * requires platform-signature authorization and owns callback/death leases.
 */
final class ScreenColorPicker implements PencilEngineBinder.Backend, AutoCloseable {
    private static final String TAG = "PianoPencilPicker";
    private static final String NOTES = "com.miui.notes";
    private static final long MAX_PIXELS = 16_000_000;
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final WindowManager windows;
    private final ExecutorService callbacks = Executors.newSingleThreadExecutor();
    private PencilEngineBinder transport;
    // Admission is synchronous on binder threads; all capture/UI work runs on main.
    private final Object admission = new Object();
    private Request current;
    private boolean closed;
    private PickerView view;
    private Request displayedRequest;
    private Bitmap snapshot;
    private boolean attached;
    private boolean receiverRegistered;

    private static final class Request {
        final PencilEngineRegistry.Caller caller;
        final long lease;
        Request(PencilEngineRegistry.Caller caller, long lease) {
            this.caller = caller;
            this.lease = lease;
        }
    }

    private final BroadcastReceiver lifecycle = new BroadcastReceiver() {
        @Override public void onReceive(Context unused, Intent intent) { cancel(); }
    };

    ScreenColorPicker(Context context) {
        this.context = context;
        windows = context.getSystemService(WindowManager.class);
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_USER_SWITCHED);
        filter.addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        context.registerReceiver(lifecycle, filter, null, main, Context.RECEIVER_NOT_EXPORTED);
        receiverRegistered = true;
    }

    void setTransport(PencilEngineBinder transport) { this.transport = transport; }

    @Override public boolean supports(int function) {
        return function == PencilEngineProtocol.FUNCTION_COLOR_PICKER;
    }

    @Override public boolean apply(PencilEngineRegistry.Caller caller, long lease,
            PencilEngineRegistry.Action action) {
        if (action.type == PencilEngineRegistry.ActionType.HIDE_COLOR_PICKER) {
            Request old;
            synchronized (admission) {
                old = current;
                if (old == null || old.lease != lease || !old.caller.equals(caller)) return true;
                current = null;
            }
            main.post(() -> teardown(old, false));
            return true;
        }
        if (action.type != PencilEngineRegistry.ActionType.SHOW_COLOR_PICKER) return false;
        if (lease <= 0 || !eligible(caller)) return false;
        Request request = new Request(caller, lease);
        synchronized (admission) {
            if (closed || current != null) return false;
            current = request;
        }
        if (!main.post(() -> capture(request))) {
            synchronized (admission) { if (current == request) current = null; }
            return false;
        }
        return true;
    }

    /** Only the foreground, current-user Notes process may request display pixels. */
    private boolean eligible(PencilEngineRegistry.Caller caller) {
        try {
            if (UserHandle.getUserId(caller.uid) != ActivityManager.getCurrentUser()) return false;
            if (!context.getSystemService(PowerManager.class).isInteractive()
                    || context.getSystemService(KeyguardManager.class).isKeyguardLocked()
                    || !context.getSystemService(UserManager.class).isUserUnlocked(
                            UserHandle.of(UserHandle.getUserId(caller.uid)))) return false;
            String[] packages = context.getPackageManager().getPackagesForUid(caller.uid);
            boolean ownsNotes = false;
            if (packages != null) for (String name : packages) ownsNotes |= NOTES.equals(name);
            if (!ownsNotes) return false;
            List<ActivityManager.RunningTaskInfo> tasks = context.getSystemService(
                    ActivityManager.class).getRunningTasks(1);
            if (tasks.isEmpty()) return false;
            ActivityManager.RunningTaskInfo task = tasks.get(0);
            ComponentName top = task.topActivity;
            return top != null && NOTES.equals(top.getPackageName())
                    && task.userId == UserHandle.getUserId(caller.uid)
                    && task.displayId == Display.DEFAULT_DISPLAY;
        } catch (RuntimeException failure) {
            Log.w(TAG, "Foreground check unavailable", failure);
            return false;
        }
    }

    private boolean owns(Request request) {
        synchronized (admission) { return !closed && current == request; }
    }

    private Point displaySize() {
        Display display = context.getSystemService(DisplayManager.class)
                .getDisplay(Display.DEFAULT_DISPLAY);
        Point size = new Point();
        if (display != null) display.getRealSize(size);
        return size;
    }

    private void capture(Request request) {
        if (!owns(request) || !eligible(request.caller)) { teardown(request, true); return; }
        // The picker itself is not added until the underlying screen is captured.
        main.postDelayed(() -> {
            if (owns(request) && !attached) teardown(request, true);
        }, 2500);
        try {
            ScreenCapture.ScreenCaptureParams params =
                    new ScreenCapture.ScreenCaptureParams.Builder(Display.DEFAULT_DISPLAY)
                    .setSecureContentPolicy(ScreenCapture.ScreenCaptureParams
                            .SECURE_CONTENT_POLICY_THROW_EXCEPTION)
                    .setProtectedContentPolicy(ScreenCapture.ScreenCaptureParams
                            .PROTECTED_CONTENT_POLICY_THROW_EXCEPTION)
                    .setUseDisplayInstallationOrientation(false)
                    .setPreserveDisplayColors(false)
                    .build();
            ScreenCapture.capture(params, command -> main.post(command),
                    new OutcomeReceiver<ScreenCapture.ScreenCaptureResult, Exception>() {
                @Override public void onResult(ScreenCapture.ScreenCaptureResult result) {
                    received(request, result);
                }
                @Override public void onError(Exception failure) {
                    Log.w(TAG, "Screenshot unavailable; picker cancelled", failure);
                    teardown(request, true);
                }
            });
        } catch (RuntimeException failure) {
            Log.w(TAG, "Screenshot API unavailable", failure);
            teardown(request, true);
        }
    }

    private void received(Request request, ScreenCapture.ScreenCaptureResult result) {
        HardwareBuffer buffer = result == null ? null : result.getHardwareBuffer();
        Bitmap hardware = null;
        Bitmap pixels = null;
        try {
            if (buffer == null || !owns(request) || !eligible(request.caller)) return;
            Point size = displaySize();
            if (size.x <= 0 || size.y <= 0 || buffer.getWidth() != size.x
                    || buffer.getHeight() != size.y || (long) size.x * size.y > MAX_PIXELS) return;
            ColorSpace sourceSpace = result.getColorSpace();
            if (sourceSpace == null) return;
            hardware = Bitmap.wrapHardwareBuffer(buffer, sourceSpace);
            if (hardware == null) return;
            Bitmap software = hardware.copy(Bitmap.Config.ARGB_8888, false);
            if (software == null) return;
            try {
                // Always sample/display actual sRGB pixels. Bitmap.getPixel returns
                // sRGB ints; do not label those ints as Display P3 or another space.
                pixels = Bitmap.createBitmap(size.x, size.y, Bitmap.Config.ARGB_8888);
                new Canvas(pixels).drawBitmap(software, 0, 0, null);
            } finally { software.recycle(); }
            show(request, pixels);
            pixels = null;
        } catch (RuntimeException | OutOfMemoryError failure) {
            Log.w(TAG, "Cannot prepare screenshot", failure);
        } finally {
            if (pixels != null) pixels.recycle();
            if (hardware != null) hardware.recycle();
            if (buffer != null) buffer.close();
            if (owns(request) && !attached) teardown(request, true);
        }
    }

    private void show(Request request, Bitmap pixels) {
        if (!owns(request) || !eligible(request.caller)) { pixels.recycle(); return; }
        snapshot = pixels;
        displayedRequest = request;
        view = new PickerView(context, request, pixels);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                pixels.getWidth(), pixels.getHeight(),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_SECURE,
                PixelFormat.OPAQUE);
        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.setFitInsetsTypes(0);
        params.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        params.setTrustedOverlay();
        params.setTitle("Piano pen color picker");
        try {
            windows.addView(view, params);
            attached = true;
            view.requestFocus();
            watch(request);
            main.postDelayed(() -> teardown(request, true), 30_000);
        } catch (RuntimeException failure) {
            Log.w(TAG, "Cannot display picker", failure);
            teardown(request, true);
        }
    }

    private void watch(Request request) {
        main.postDelayed(() -> {
            if (!owns(request)) return;
            if (!eligible(request.caller)) teardown(request, true);
            else watch(request);
        }, 200);
    }

    void cancel() {
        Request request;
        synchronized (admission) { request = current; }
        if (request != null) main.post(() -> teardown(request, true));
    }

    private void teardown(Request request, boolean cancelLease) {
        // A delayed completion must never remove a subsequent owner's overlay.
        boolean wasCurrent;
        synchronized (admission) {
            wasCurrent = current == request;
            if (wasCurrent) current = null;
        }
        if (displayedRequest == request && attached && view != null) {
            try { windows.removeViewImmediate(view); }
            catch (RuntimeException failure) { Log.w(TAG, "Picker removal failed", failure); }
        }
        if (displayedRequest == request) {
            attached = false;
            view = null;
            displayedRequest = null;
            if (snapshot != null) { snapshot.recycle(); snapshot = null; }
        }
        // The same listener lease can start another pick. Old UI timers must not
        // cancel that newer request merely because its client lease still matches.
        if (cancelLease && wasCurrent && transport != null) transport.cancelColorPick(
                request.caller.pid, request.caller.uid, request.lease);
    }

    @Override public void close() {
        synchronized (admission) { closed = true; }
        cancel();
        callbacks.shutdownNow();
        if (receiverRegistered) {
            context.unregisterReceiver(lifecycle);
            receiverRegistered = false;
        }
    }

    /** Stock-inspired 7x7 magnified grid and sampled center-color ring. */
    private final class PickerView extends View {
        final Request request;
        final Bitmap pixels;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final float density;
        float x;
        float y;
        boolean touched;
        PickerView(Context context, Request request, Bitmap pixels) {
            super(context);
            this.request = request;
            this.pixels = pixels;
            density = getResources().getDisplayMetrics().density;
            x = pixels.getWidth() / 2f;
            y = pixels.getHeight() / 2f;
            setFocusableInTouchMode(true);
            setContentDescription("Drag to sample a screen color, release to select. Back cancels.");
        }
        private int sample(int px, int py) {
            if (px < 0 || py < 0 || px >= pixels.getWidth() || py >= pixels.getHeight())
                return 0xff000000;
            return pixels.getPixel(px, py);
        }
        @Override protected void onDraw(Canvas canvas) {
            if (pixels.isRecycled()) return;
            canvas.drawBitmap(pixels, 0, 0, null);
            float radius = 61 * density;
            canvas.save();
            android.graphics.Path clip = new android.graphics.Path();
            clip.addCircle(x, y, radius, android.graphics.Path.Direction.CW);
            canvas.clipPath(clip);
            float cell = radius * 2 / 7;
            int centerX = (int) x;
            int centerY = (int) y;
            for (int row = 0; row < 7; row++) for (int col = 0; col < 7; col++) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(sample(centerX + col - 3, centerY + row - 3));
                float left = x - radius + col * cell;
                float top = y - radius + row * cell;
                canvas.drawRect(left, top, left + cell, top + cell, paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(density);
                paint.setColor(0xff707070);
                canvas.drawRect(left, top, left + cell, top + cell, paint);
            }
            canvas.restore();
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(12 * density);
            paint.setColor(sample(centerX, centerY));
            canvas.drawCircle(x, y, radius + 8 * density, paint);
            paint.setStrokeWidth(2 * density);
            paint.setColor(0xff202020);
            canvas.drawCircle(x, y, radius + 15 * density, paint);
            paint.setColor(0xffffffff);
            canvas.drawRect(x - cell / 2, y - cell / 2, x + cell / 2, y + cell / 2, paint);
        }
        @Override public boolean onTouchEvent(MotionEvent event) {
            if (!owns(request) || pixels.isRecycled()) return true;
            int action = event.getActionMasked();
            if (event.getPointerCount() != 1 || action == MotionEvent.ACTION_CANCEL) {
                teardown(request, true);
                return true;
            }
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
                touched = true;
                x = Math.max(0, Math.min(pixels.getWidth() - 1, event.getX()));
                y = Math.max(0, Math.min(pixels.getHeight() - 1, event.getY()));
                invalidate();
            } else if (action == MotionEvent.ACTION_UP && touched) {
                if (!eligible(request.caller)) { teardown(request, true); return true; }
                int color = sample((int) x, (int) y);
                teardown(request, false);
                // Never call a client while its bitmap/window or binder operation lock is held.
                callbacks.execute(() -> transport.completeColorPick(request.caller.pid,
                        request.caller.uid, request.lease,
                        ColorSpace.get(ColorSpace.Named.SRGB).getId(), color));
            }
            return true;
        }
        @Override public boolean dispatchKeyEvent(KeyEvent event) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_BACK
                    || event.getKeyCode() == KeyEvent.KEYCODE_ESCAPE) {
                if (event.getAction() == KeyEvent.ACTION_UP) teardown(request, true);
                return true;
            }
            return super.dispatchKeyEvent(event);
        }
    }
}
