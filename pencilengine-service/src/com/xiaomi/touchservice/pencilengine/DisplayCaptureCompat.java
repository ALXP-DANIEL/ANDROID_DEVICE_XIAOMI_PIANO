/* SPDX-License-Identifier: Apache-2.0 */
package com.xiaomi.touchservice.pencilengine;

import android.graphics.ColorSpace;
import android.hardware.HardwareBuffer;
import android.os.Handler;
import android.os.OutcomeReceiver;
import android.view.Display;
import java.lang.reflect.Method;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.ObjIntConsumer;

/** Platform-signed capture bridge for both Android 16 capture ABI revisions and 17. */
final class DisplayCaptureCompat {
    static final class Frame {
        final HardwareBuffer buffer;
        final ColorSpace colorSpace;
        Frame(HardwareBuffer buffer, ColorSpace colorSpace) {
            this.buffer = buffer;
            this.colorSpace = colorSpace;
        }
    }

    static void capture(Handler main, Consumer<Frame> success, Consumer<Exception> failure) {
        try {
            Class<?> params;
            try {
                params = Class.forName("android.window.ScreenCapture$ScreenCaptureParams");
            } catch (ClassNotFoundException legacy) {
                legacy(main, success, failure);
                return;
            }
            Class<?> builderType = Class.forName(params.getName() + "$Builder");
            Object builder = builderType.getConstructor(int.class).newInstance(Display.DEFAULT_DISPLAY);
            builderType.getMethod("setSecureContentPolicy", int.class).invoke(builder,
                    params.getField("SECURE_CONTENT_POLICY_THROW_EXCEPTION").getInt(null));
            builderType.getMethod("setProtectedContentPolicy", int.class).invoke(builder,
                    params.getField("PROTECTED_CONTENT_POLICY_THROW_EXCEPTION").getInt(null));
            builderType.getMethod("setUseDisplayInstallationOrientation", boolean.class)
                    .invoke(builder, false);
            builderType.getMethod("setPreserveDisplayColors", boolean.class).invoke(builder, false);
            Object args = builderType.getMethod("build").invoke(builder);
            OutcomeReceiver<Object, Exception> receiver = new OutcomeReceiver<>() {
                @Override public void onResult(Object result) {
                    deliver(main, success, failure, result, false);
                }
                @Override public void onError(Exception error) {
                    main.post(() -> failure.accept(error));
                }
            };
            Class.forName("android.window.ScreenCapture")
                    .getMethod("capture", params, Executor.class, OutcomeReceiver.class)
                    .invoke(null, args, (Executor) Runnable::run, receiver);
        } catch (ReflectiveOperationException | RuntimeException error) {
            main.post(() -> failure.accept(error));
        }
    }

    private static void legacy(Handler main, Consumer<Frame> success,
            Consumer<Exception> failure) throws ReflectiveOperationException {
        // Ask WindowManager to capture the default display in its current rotation.
        // Derive parameter classes from the actual Binder ABI: later 16 builds
        // moved these nested types from ScreenCapture to ScreenCaptureInternal.
        Class<?> wmType = Class.forName("android.view.IWindowManager");
        Method method = null;
        for (Method candidate : wmType.getMethods()) {
            if (candidate.getName().equals("captureDisplay") && candidate.getParameterCount() == 3) {
                method = candidate;
                break;
            }
        }
        if (method == null) throw new NoSuchMethodException("IWindowManager.captureDisplay");
        Class<?> argsType = method.getParameterTypes()[1];
        Class<?> listenerType = method.getParameterTypes()[2];
        Class<?> builderType = Class.forName(argsType.getName() + "$Builder");
        Object builder = builderType.getConstructor().newInstance();
        builderType.getMethod("setCaptureSecureLayers", boolean.class).invoke(builder, false);
        builderType.getMethod("setAllowProtected", boolean.class).invoke(builder, false);
        Object args = builderType.getMethod("build").invoke(builder);
        ObjIntConsumer<Object> consumer = (result, status) -> {
            if (status == 0) deliver(main, success, failure, result, true);
            else {
                closeResult(result);
                main.post(() -> failure.accept(new IllegalStateException("Capture status " + status)));
            }
        };
        Object listener = listenerType.getConstructor(ObjIntConsumer.class).newInstance(consumer);
        Object wm = Class.forName("android.view.WindowManagerGlobal")
                .getMethod("getWindowManagerService").invoke(null);
        method.invoke(wm, Display.DEFAULT_DISPLAY, args, listener);
    }

    private static void deliver(Handler main, Consumer<Frame> success,
            Consumer<Exception> failure, Object result, boolean legacy) {
        HardwareBuffer buffer = null;
        try {
            if (result == null) throw new IllegalStateException("Missing capture result");
            Class<?> type = result.getClass();
            buffer = (HardwareBuffer) type.getMethod("getHardwareBuffer").invoke(result);
            if (buffer == null) throw new IllegalStateException("Missing capture buffer");
            if (legacy && (boolean) type.getMethod("containsSecureLayers").invoke(result)) {
                throw new SecurityException("Secure capture rejected");
            }
            ColorSpace colorSpace = (ColorSpace) type.getMethod("getColorSpace").invoke(result);
            Frame frame = new Frame(buffer, colorSpace);
            if (!main.post(() -> success.accept(frame))) buffer.close();
        } catch (ReflectiveOperationException | RuntimeException error) {
            if (buffer != null) buffer.close();
            main.post(() -> failure.accept(error));
        }
    }

    private static void closeResult(Object result) {
        if (result == null) return;
        try {
            HardwareBuffer buffer = (HardwareBuffer) result.getClass()
                    .getMethod("getHardwareBuffer").invoke(result);
            if (buffer != null) buffer.close();
        } catch (ReflectiveOperationException | RuntimeException ignored) { }
    }

    private DisplayCaptureCompat() { }
}
