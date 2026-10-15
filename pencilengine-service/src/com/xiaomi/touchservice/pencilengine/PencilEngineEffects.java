/* SPDX-License-Identifier: Apache-2.0 */
package com.xiaomi.touchservice.pencilengine;

import android.Manifest;
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
            return UserHandle.getUserId(caller.uid) == context.getUserId()
                    && context.getPackageManager().getPackageUid(NOTES, 0) == caller.uid
                    ? NOTES : null;
        } catch (PackageManager.NameNotFoundException unavailable) { return null; }
    }

    private boolean ready() {
        int boot = boot();
        return boot >= 0 && context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED
                && Settings.Global.getInt(context.getContentResolver(),
                        PencilEngineLease.TOUCH_FILM_READY, -2) == boot;
    }

    @Override public boolean supports(int function) {
        if (function == PencilEngineProtocol.FUNCTION_COLOR_PICKER) return true;
        return function == PencilEngineProtocol.FUNCTION_TOUCH_FILM
                && !closed && !unavailable && ready();
    }

    @Override public synchronized boolean apply(PencilEngineRegistry.Caller caller, long lease,
            PencilEngineRegistry.Action action) {
        if (action.type != PencilEngineRegistry.ActionType.SET_TOUCH_FILM_PACKAGE) {
            return picker.apply(caller, lease, action);
        }
        if (closed || !NOTES.equals(action.packageName)
                || UserHandle.getUserId(caller.uid) != context.getUserId()) return false;
        try {
            if (context.getPackageManager().getPackageUid(NOTES, 0) != caller.uid) return false;
            if (action.enabled) {
                if (!ready()) return false;
                uid = caller.uid;
                if (!publish()) { unavailable = true; return false; }
                enabled = true;
                main.removeCallbacks(renew);
                if (!main.postDelayed(renew, 2000)) {
                    enabled = false;
                    unavailable = true;
                    clear();
                    return false;
                }
            } else {
                enabled = false;
                main.removeCallbacks(renew);
                return clear();
            }
            return true;
        } catch (PackageManager.NameNotFoundException | RuntimeException failure) { return false; }
    }

    private boolean publish() {
        return Settings.Secure.putStringForUser(context.getContentResolver(),
                PencilEngineLease.TOUCH_FILM,
                PencilEngineLease.create(boot(), uid, SystemClock.elapsedRealtime(), owner),
                context.getUserId());
    }

    private boolean clear() {
        String value = Settings.Secure.getStringForUser(context.getContentResolver(),
                PencilEngineLease.TOUCH_FILM, context.getUserId());
        return !PencilEngineLease.ownedBy(value, owner)
                || Settings.Secure.putStringForUser(context.getContentResolver(),
                        PencilEngineLease.TOUCH_FILM, null, context.getUserId());
    }

    private synchronized void renew() {
        if (closed || !enabled) return;
        try {
            if (!ready() || !publish()) {
                enabled = false;
                unavailable = true;
                clear();
                return;
            }
        } catch (RuntimeException failure) { enabled = false; unavailable = true; return; }
        if (!main.postDelayed(renew, 2000)) {
            enabled = false;
            unavailable = true;
            try { clear(); } catch (RuntimeException ignored) { /* Lease expires. */ }
        }
    }

    @Override public synchronized void close() {
        closed = true;
        enabled = false;
        main.removeCallbacks(renew);
        try { clear(); } catch (RuntimeException ignored) { /* Lease expires independently. */ }
    }
}
