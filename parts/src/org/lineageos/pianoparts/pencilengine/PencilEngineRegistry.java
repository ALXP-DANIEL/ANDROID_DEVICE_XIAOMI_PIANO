/* SPDX-License-Identifier: Apache-2.0 */
package org.lineageos.pianoparts.pencilengine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Pure-Java, clean-room PencilEngine client/feature state machine.
 *
 * Source reference (decoded JADX, not a confirmed device runtime capture):
 * RND/analysis/accessories-44-20261014/pencilengine-stock/touchservice/
 * sources/com/xiaomi/touchservice/pencilengine/
 *   PencilEngineManagerService.java:98-134,137-175,196-255
 *   ColorPickController.java:24-56,72-96
 *   PencilPostureController.java:28-62,65-102
 *   TouchFilmController.java:26-79
 *
 * Available here: caller registration, lease-scoped cleanup, exclusive picker,
 * global aggregate posture mode 20036, per-app reference-counted feature
 * ownership, and declarative effects. No Android Binder or OEM libraries.
 *
 * TODO for future integration: an authenticated, permission-checked Binder
 * adapter, PackageManager UID ownership checks, linkToDeath/unlinkToDeath,
 * color-picker UI and callback delivery, foreground-app policy, and verified
 * touch HAL / system-server dispatch. None of these are active in this class.
 *
 * Deliberate secure compatibility changes from the decompiled stock code:
 * register by package requires UID ownership, identity keys include UID,
 * unknown functions are rejected, repeated registration is reported, a busy
 * picker is reported, and per-package state waits for its last owner to exit.
 *
 * Caller MUST be obtained from a trusted transport (e.g. Binder.getCallingPid
 * and getCallingUid), never read from client-provided arguments. AccessPolicy
 * MUST verify package ownership against the OS's authoritative UID-to-package
 * mapping; a supplied package string is never proof of package identity.
 *
 * Changes are descriptions for a future privileged adapter. No actual UI,
 * callback, framework or touch HAL operation happens in this class. The adapter
 * must apply changes in order and arrange Binder death monitoring independently.
 */
public final class PencilEngineRegistry {
    public interface AccessPolicy {
        /** Authorization to use this privileged feature, independently of registration. */
        boolean isAuthorized(Caller caller);

        /** Return true only for a package verifiably owned by this UID. */
        boolean uidOwnsPackage(int uid, String packageName);
    }

    /** Callback signature from stock IPencilEngineCallback.onChange, transaction 1. */
    public interface Callback {
        void onChange(int function, int value1, int value2);
    }

    /** Identity taken from a trusted binder/transport boundary, not a request parcel. */
    public static final class Caller {
        public final int pid;
        public final int uid;

        public Caller(int pid, int uid) {
            if (pid <= 0 || uid < 0) {
                throw new IllegalArgumentException("Invalid calling PID/UID");
            }
            this.pid = pid;
            this.uid = uid;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Caller)) return false;
            Caller that = (Caller) other;
            return pid == that.pid && uid == that.uid;
        }

        @Override
        public int hashCode() {
            return 31 * pid + uid;
        }
    }

    public enum Status {
        OK,
        ALREADY_REGISTERED,
        NOT_REGISTERED,
        UNAUTHORIZED,
        INVALID_ARGUMENT,
        MISSING_PACKAGE,
        BUSY,
        UNKNOWN_FUNCTION,
        STALE_LEASE
    }

    public enum ActionType {
        SHOW_COLOR_PICKER,
        HIDE_COLOR_PICKER,
        SET_POSTURE_MODE,
        SET_POSTURE_PACKAGE,
        SET_TOUCH_FILM_PACKAGE
    }

    /** Declarative, immutable output for the future platform integration layer. */
    public static final class Action {
        public final ActionType type;
        public final String packageName;
        public final boolean enabled;

        private Action(ActionType type, String packageName, boolean enabled) {
            this.type = type;
            this.packageName = packageName;
            this.enabled = enabled;
        }

        private static Action of(ActionType type, String packageName, boolean enabled) {
            return new Action(type, packageName, enabled);
        }
    }

    /**
     * A callback delivery to be made only by a trusted future adapter.
     * Calling arbitrary client code while holding the registry lock is avoided.
     */
    public static final class Notification {
        public final Callback callback;
        public final int function;
        public final int value1;
        public final int value2;

        private Notification(Callback callback, int function, int value1, int value2) {
            this.callback = callback;
            this.function = function;
            this.value1 = value1;
            this.value2 = value2;
        }
    }

    public static final class Outcome {
        public final Status status;
        /** Unique lease for Binder-death attribution. Zero if no new client was registered. */
        public final long lease;
        public final List<Action> actions;
        public final Notification notification;

        private Outcome(Status status, long lease, List<Action> actions,
                Notification notification) {
            this.status = status;
            this.lease = lease;
            this.actions = Collections.unmodifiableList(new ArrayList<>(actions));
            this.notification = notification;
        }
    }

    private static final class Client {
        final Caller caller;
        final Callback callback;
        final String packageName; // Null for registerListener(), as in stock.
        final long lease;
        boolean posture;
        boolean touchFilm;

        Client(Caller caller, Callback callback, String packageName, long lease) {
            this.caller = caller;
            this.callback = callback;
            this.packageName = packageName;
            this.lease = lease;
        }
    }

    private final AccessPolicy access;
    private final Map<Caller, Client> clients = new HashMap<>();
    private final Map<String, Integer> posturePackages = new HashMap<>();
    private final Map<String, Integer> touchFilmPackages = new HashMap<>();
    private Caller pickerOwner;
    private long nextLease = 1;
    private int postureCount;

    public PencilEngineRegistry(AccessPolicy access) {
        this.access = Objects.requireNonNull(access, "access");
    }

    /** Stock Binder transaction 1: registerListener(callback), no package label. */
    public synchronized Outcome registerListener(Caller caller, Callback callback) {
        return register(caller, callback, null, false);
    }

    /** Stock Binder transaction 4: registerListenerByPackageName(callback, package). */
    public synchronized Outcome registerByPackage(Caller caller, Callback callback,
            String packageName) {
        return register(caller, callback, packageName, true);
    }

    private Outcome register(Caller caller, Callback callback, String packageName,
            boolean withPackage) {
        Objects.requireNonNull(caller, "caller");
        if (!access.isAuthorized(caller)) return outcome(Status.UNAUTHORIZED);
        if (callback == null) return outcome(Status.INVALID_ARGUMENT);
        if (withPackage && !validPackageName(packageName)) {
            return outcome(Status.INVALID_ARGUMENT);
        }
        if (withPackage && !access.uidOwnsPackage(caller.uid, packageName)) {
            return outcome(Status.UNAUTHORIZED);
        }
        if (clients.containsKey(caller)) return outcome(Status.ALREADY_REGISTERED);
        long lease = nextLease++;
        // Refuse wraparound before a generation could be reused.
        if (lease <= 0 || nextLease <= 0) {
            throw new IllegalStateException("PencilEngine lease sequence exhausted");
        }
        clients.put(caller, new Client(caller, callback, packageName, lease));
        return new Outcome(Status.OK, lease, Collections.emptyList(), null);
    }

    /**
     * Stock Binder transaction 2: unregisterListener(). Releasing an owned
     * registration remains possible after authorization has been revoked.
     */
    public synchronized Outcome unregister(Caller caller) {
        Objects.requireNonNull(caller, "caller");
        Client client = clients.get(caller);
        if (client == null) return outcome(Status.NOT_REGISTERED);
        List<Action> actions = new ArrayList<>();
        release(client, actions);
        return new Outcome(Status.OK, 0, actions, null);
    }

    /**
     * A Binder adapter must bind its death recipient to the returned lease.
     * Stale death notifications cannot close a newer registration, even when
     * the OS later reuses a PID or the client re-registers.
     */
    public synchronized Outcome onClientDeath(Caller caller, long lease) {
        Objects.requireNonNull(caller, "caller");
        Client client = clients.get(caller);
        if (client == null || client.lease != lease) return outcome(Status.STALE_LEASE);
        List<Action> actions = new ArrayList<>();
        release(client, actions);
        return new Outcome(Status.OK, 0, actions, null);
    }

    /** Stock Binder transaction 3, modeled with typed results instead of wire replies. */
    public synchronized Outcome triggerFunction(Caller caller, int function, int value) {
        Objects.requireNonNull(caller, "caller");
        Client client = clients.get(caller);
        if (!access.isAuthorized(caller)) {
            // Fail closed on a policy revocation and release any existing leases.
            if (client == null) return outcome(Status.UNAUTHORIZED);
            List<Action> actions = new ArrayList<>();
            release(client, actions);
            return new Outcome(Status.UNAUTHORIZED, 0, actions, null);
        }
        if (client == null) return outcome(Status.NOT_REGISTERED);
        List<Action> actions = new ArrayList<>();
        switch (function) {
            case PencilEngineProtocol.FUNCTION_COLOR_PICKER:
                if (pickerOwner != null && !pickerOwner.equals(caller)) {
                    return outcome(Status.BUSY);
                }
                if (pickerOwner == null) {
                    pickerOwner = caller;
                    actions.add(Action.of(ActionType.SHOW_COLOR_PICKER, null, true));
                }
                break;
            case PencilEngineProtocol.FUNCTION_POSTURE:
                setPosture(client, value != 0, actions);
                break;
            case PencilEngineProtocol.FUNCTION_TOUCH_FILM:
                if (client.packageName == null) return outcome(Status.MISSING_PACKAGE);
                setTouchFilm(client, value != 0, actions);
                break;
            default:
                // Stock silently returned success for unknown functions; reject here.
                return outcome(Status.UNKNOWN_FUNCTION);
        }
        return new Outcome(Status.OK, 0, actions, null);
    }

    /**
     * Trusted UI completion only, never callable as an untrusted Binder method.
     * The returned callback and values represent stock onChange(0, space, color).
     */
    public synchronized Outcome completeColorPick(Caller owner, long lease,
            int colorSpace, int color) {
        Objects.requireNonNull(owner, "owner");
        Client client = clients.get(owner);
        if (client == null || client.lease != lease || !owner.equals(pickerOwner)) {
            return outcome(Status.STALE_LEASE);
        }
        pickerOwner = null;
        return new Outcome(Status.OK, 0, Collections.emptyList(),
                new Notification(client.callback, PencilEngineProtocol.FUNCTION_COLOR_PICKER,
                        colorSpace, color));
    }

    private void setPosture(Client client, boolean enable, List<Action> actions) {
        if (client.posture == enable) return;
        client.posture = enable;
        if (enable) {
            postureCount++;
            if (client.packageName != null
                    && increment(posturePackages, client.packageName)) {
                actions.add(Action.of(ActionType.SET_POSTURE_PACKAGE,
                        client.packageName, true));
            }
            if (postureCount == 1) {
                actions.add(Action.of(ActionType.SET_POSTURE_MODE, null, true));
            }
        } else {
            if (client.packageName != null
                    && decrement(posturePackages, client.packageName)) {
                actions.add(Action.of(ActionType.SET_POSTURE_PACKAGE,
                        client.packageName, false));
            }
            postureCount--;
            if (postureCount == 0) {
                actions.add(Action.of(ActionType.SET_POSTURE_MODE, null, false));
            }
        }
    }

    private void setTouchFilm(Client client, boolean enable, List<Action> actions) {
        if (client.touchFilm == enable) return;
        client.touchFilm = enable;
        boolean changed = enable
                ? increment(touchFilmPackages, client.packageName)
                : decrement(touchFilmPackages, client.packageName);
        if (changed) {
            actions.add(Action.of(ActionType.SET_TOUCH_FILM_PACKAGE,
                    client.packageName, enable));
        }
    }

    /** Returns true only when the package goes from zero to one owner. */
    private static boolean increment(Map<String, Integer> counts, String packageName) {
        Integer old = counts.get(packageName);
        counts.put(packageName, old == null ? 1 : old + 1);
        return old == null;
    }

    /** Returns true only when the package loses its final owner. */
    private static boolean decrement(Map<String, Integer> counts, String packageName) {
        Integer old = counts.get(packageName);
        // A stale cleanup is harmless: never throw or emit a false disable.
        if (old == null || old <= 0) return false;
        if (old == 1) {
            counts.remove(packageName);
            return true;
        }
        counts.put(packageName, old - 1);
        return false;
    }

    private void release(Client client, List<Action> actions) {
        if (client.caller.equals(pickerOwner)) {
            pickerOwner = null;
            actions.add(Action.of(ActionType.HIDE_COLOR_PICKER, null, false));
        }
        setTouchFilm(client, false, actions);
        setPosture(client, false, actions);
        clients.remove(client.caller);
    }

    public synchronized int registeredClientCount() {
        return clients.size();
    }

    public synchronized boolean isPostureEnabled() {
        return postureCount != 0;
    }

    public synchronized boolean isColorPickerOwnedBy(Caller caller) {
        return pickerOwner != null && pickerOwner.equals(caller);
    }

    public synchronized boolean isTouchFilmEnabledForPackage(String packageName) {
        return touchFilmPackages.containsKey(packageName);
    }

    private static boolean validPackageName(String packageName) {
        return packageName != null && packageName.length() <= 255
                && packageName.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+");
    }

    private static Outcome outcome(Status status) {
        return new Outcome(status, 0, Collections.emptyList(), null);
    }
}
