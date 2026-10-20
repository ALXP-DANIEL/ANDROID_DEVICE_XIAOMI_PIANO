/* SPDX-License-Identifier: Apache-2.0 */
package com.xiaomi.touchservice.pencilengine;

import android.Manifest;
import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import java.util.UUID;
import org.lineageos.pianoparts.pencilengine.PencilEngineBinder;
import org.lineageos.pianoparts.pencilengine.PencilEngineLease;
import org.lineageos.pianoparts.pencilengine.PencilEngineProtocol;
import org.lineageos.pianoparts.pencilengine.PencilEngineRegistry;

/** Colour UI plus a renewable request consumed by the system pen-key handler. */
final class PencilEngineEffects implements PencilEngineBinder.Backend, AutoCloseable {
    private static final String NOTES = "com.miui.notes";
    private final Context context;
    private final ScreenColorPicker picker;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final String owner = UUID.randomUUID().toString().replace("-", "");
    private boolean enabled;
    private boolean posturePackage;
    private boolean postureMode;
    private volatile boolean closed;
    private volatile boolean unavailable;
    private int uid = -1;
    private final Runnable renew = this::renew;

    PencilEngineEffects(Context context, ScreenColorPicker picker) {
        this.context = context;
        this.picker = picker;
    }

    private int boot() {
        return Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
    }

    @Override public String registrationPackage(PencilEngineRegistry.Caller caller) {
        try {
            // Notes uses legacy registration unless MIUI advertises engine 0.3.2.
            // Resolve the installed UID instead of claiming that OEM engine exists.
            return ActivityManager.getCurrentUser() == context.getUserId()
                    && UserHandle.getUserId(caller.uid) == context.getUserId()
                    && context.getPackageManager().getPackageUid(NOTES, 0) == caller.uid
                    ? NOTES : null;
        } catch (PackageManager.NameNotFoundException unavailable) { return null; }
    }

    private boolean ready() { return ready(PencilEngineLease.TOUCH_FILM_READY); }

    private boolean ready(String marker) {
        int boot = boot();
        if (PencilEngineLease.POSTURE_READY.equals(marker)) {
            long last = Settings.Global.getLong(context.getContentResolver(),
                    PencilEngineLease.POSTURE_HEARTBEAT, -1);
            long now = SystemClock.elapsedRealtime();
            if (last < 0 || last > now || now - last > 2000) return false;
        }
        return !closed && !unavailable && ActivityManager.getCurrentUser() == context.getUserId()
                && boot >= 0 && context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED
                && Settings.Global.getInt(context.getContentResolver(),
                        marker, -2) == boot;
    }

    @Override public boolean supports(int function) {
        if (function == PencilEngineProtocol.FUNCTION_COLOR_PICKER) return true;
        if (function == PencilEngineProtocol.FUNCTION_POSTURE) {
            return !closed && !unavailable && ready(PencilEngineLease.POSTURE_READY);
        }
        return function == PencilEngineProtocol.FUNCTION_TOUCH_FILM
                && !closed && !unavailable && ready();
    }

    @Override public synchronized boolean apply(PencilEngineRegistry.Caller caller, long lease,
            PencilEngineRegistry.Action action) {
        if (action.type == PencilEngineRegistry.ActionType.SET_POSTURE_PACKAGE
                || action.type == PencilEngineRegistry.ActionType.SET_POSTURE_MODE) {
            return applyPosture(caller, action);
        }
        if (action.type != PencilEngineRegistry.ActionType.SET_TOUCH_FILM_PACKAGE) {
            return picker.apply(caller, lease, action);
        }
        if (closed || (action.enabled && unavailable) || !NOTES.equals(action.packageName)
                || UserHandle.getUserId(caller.uid) != context.getUserId()) return false;
        try {
            if (context.getPackageManager().getPackageUid(NOTES, 0) != caller.uid) return false;
            if (action.enabled) {
                if (!ready()) return false;
                uid = caller.uid;
                if (!publish()) { fail(); return false; }
                enabled = true;
                main.removeCallbacks(renew);
                if (!main.postDelayed(renew, 2000)) {
                    fail();
                    return false;
                }
            } else {
                enabled = false;
                main.removeCallbacks(renew);
                boolean cleared = clear();
                if (!cleared) { fail(); return false; }
                return schedule();
            }
            return true;
        } catch (PackageManager.NameNotFoundException | RuntimeException failure) { fail(); return false; }
    }

    private boolean applyPosture(PencilEngineRegistry.Caller caller,
            PencilEngineRegistry.Action action) {
        if (closed || (action.enabled && unavailable) || UserHandle.getUserId(caller.uid) != context.getUserId()) return false;
        try {
            if (context.getPackageManager().getPackageUid(NOTES, 0) != caller.uid) return false;
            if (action.type == PencilEngineRegistry.ActionType.SET_POSTURE_PACKAGE) {
                if (!NOTES.equals(action.packageName)) return false;
                posturePackage = action.enabled;
                // The package aggregate is staged before the global aggregate.
                // No HAL command is sent until both effects have an owner.
                if (!action.enabled) return clear(PencilEngineLease.POSTURE);
                return ready(PencilEngineLease.POSTURE_READY);
            }
            if (action.enabled && (!posturePackage || !ready(PencilEngineLease.POSTURE_READY))) {
                return false;
            }
            postureMode = action.enabled;
            uid = caller.uid;
            if (postureMode && !publish(PencilEngineLease.POSTURE)) { fail(); return false; }
            if (!postureMode && !clear(PencilEngineLease.POSTURE)) { fail(); return false; }
            main.removeCallbacks(renew);
            return schedule();
        } catch (PackageManager.NameNotFoundException | RuntimeException failure) {
            fail();
            return false;
        }
    }

    private boolean schedule() {
        if (!(enabled || postureMode) || main.postDelayed(renew, 2000)) return true;
        fail();
        return false;
    }

    private void fail() {
        enabled = false;
        postureMode = false;
        posturePackage = false;
        unavailable = true;
        main.removeCallbacks(renew);
        try { clear(); } catch (RuntimeException ignored) { /* Lease expires. */ }
        try { clear(PencilEngineLease.POSTURE); } catch (RuntimeException ignored) { /* Lease expires. */ }
    }

    private boolean publish() { return publish(PencilEngineLease.TOUCH_FILM); }

    private boolean publish(String key) {
        return Settings.Secure.putStringForUser(context.getContentResolver(),
                key,
                PencilEngineLease.create(boot(), uid, SystemClock.elapsedRealtime(), owner),
                context.getUserId());
    }

    private boolean clear() { return clear(PencilEngineLease.TOUCH_FILM); }

    private boolean clear(String key) {
        String value = Settings.Secure.getStringForUser(context.getContentResolver(),
                key, context.getUserId());
        return !PencilEngineLease.ownedBy(value, owner)
                || Settings.Secure.putStringForUser(context.getContentResolver(),
                        key, null, context.getUserId());
    }

    private synchronized void renew() {
        if (closed || !(enabled || postureMode)) return;
        try {
            if (context.getPackageManager().getPackageUid(NOTES, 0) != uid) {
                throw new IllegalStateException("Notes UID changed");
            }
            if ((enabled && (!ready() || !publish()))
                    || (postureMode && (!ready(PencilEngineLease.POSTURE_READY)
                        || !publish(PencilEngineLease.POSTURE)))) {
                fail();
                return;
            }
        } catch (PackageManager.NameNotFoundException | RuntimeException failure) { fail(); return; }
        schedule();
    }

    @Override public synchronized void close() {
        closed = true;
        fail();
    }
}
