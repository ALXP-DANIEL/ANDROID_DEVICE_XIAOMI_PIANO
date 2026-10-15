/* SPDX-License-Identifier: Apache-2.0 */
package org.lineageos.pianoparts.pencilengine;

import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.NoSuchElementException;
import java.util.Map;
import java.util.Objects;

/**
 * Unpublished stock-compatible transport. This class does not register a service,
 * impersonate TouchService, or provide a touch HAL implementation. The owner must
 * supply a real backend before exposing this binder. Wire reference: .44
 * touchservice/sources/com/xiaomi/touchservice/pencilengine/IPencilEngine.java.
 */
public final class PencilEngineBinder extends Binder {
    public interface Backend {
        /** True only if this function has a working implementation. */
        boolean supports(int function);
        /** Apply synchronously, return true only on real acceptance. Never call clients. */
        boolean apply(PencilEngineRegistry.Caller caller, long lease,
                PencilEngineRegistry.Action action);
    }

    private final Object operations = new Object();
    private final PencilEngineRegistry registry;
    private final Backend backend;
    private boolean closed;
    private final Map<PencilEngineRegistry.Caller, Lease> leases = new HashMap<>();

    public PencilEngineBinder(PackageManager packages, Backend backend) {
        this.backend = Objects.requireNonNull(backend);
        Objects.requireNonNull(packages);
        registry = new PencilEngineRegistry(new PencilEngineRegistry.AccessPolicy() {
            public boolean isAuthorized(PencilEngineRegistry.Caller caller) {
                return packages.checkSignatures(caller.uid, Process.SYSTEM_UID)
                        == PackageManager.SIGNATURE_MATCH;
            }
            public boolean uidOwnsPackage(int uid, String name) {
                String[] names = packages.getPackagesForUid(uid);
                if (names != null) for (String candidate : names) {
                    if (candidate.equals(name)) return true;
                }
                return false;
            }
        });
        attachInterface(null, PencilEngineProtocol.DESCRIPTOR);
    }

    private final class Lease implements IBinder.DeathRecipient {
        final PencilEngineRegistry.Caller caller;
        final IBinder token;
        long id;
        boolean dead;
        Lease(PencilEngineRegistry.Caller caller, IBinder token) {
            this.caller = caller;
            this.token = token;
        }
        public void binderDied() {
            synchronized (operations) {
                dead = true;
                if (leases.get(caller) == this) release(this);
            }
        }
    }

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
            throws RemoteException {
        if (code == INTERFACE_TRANSACTION) {
            if (reply != null) reply.writeString(PencilEngineProtocol.DESCRIPTOR);
            return true;
        }
        if (code < 1 || code > 4) return super.onTransact(code, data, reply, flags);
        data.enforceInterface(PencilEngineProtocol.DESCRIPTOR);
        // These four stock methods are synchronous. Reject malformed one-way requests.
        if (reply == null || (flags & IBinder.FLAG_ONEWAY) != 0) {
            throw new IllegalArgumentException("Synchronous PencilEngine request required");
        }
        PencilEngineRegistry.Caller caller = new PencilEngineRegistry.Caller(
                Binder.getCallingPid(), Binder.getCallingUid());
        if (code == PencilEngineProtocol.TX_REGISTER_LISTENER
                || code == PencilEngineProtocol.TX_REGISTER_BY_PACKAGE) {
            IBinder token = data.readStrongBinder();
            String name = code == PencilEngineProtocol.TX_REGISTER_BY_PACKAGE
                    ? data.readString() : null;
            data.enforceNoDataAvail();
            register(caller, token, name, code == PencilEngineProtocol.TX_REGISTER_BY_PACKAGE);
            reply.writeNoException();
        } else if (code == PencilEngineProtocol.TX_UNREGISTER_LISTENER) {
            data.enforceNoDataAvail();
            synchronized (operations) {
                Lease lease = leases.get(caller);
                if (lease != null) release(lease);
            }
            reply.writeNoException();
        } else {
            int function = data.readInt();
            int value = data.readInt();
            data.enforceNoDataAvail();
            int result = trigger(caller, function, value);
            reply.writeNoException();
            reply.writeInt(result);
        }
        return true;
    }

    private void register(PencilEngineRegistry.Caller caller, IBinder token,
            String name, boolean named) throws RemoteException {
        if (token == null) throw new IllegalArgumentException("Missing callback");
        synchronized (operations) {
            if (closed) throw new IllegalStateException("PencilEngine transport closed");
            if (leases.containsKey(caller)) throw new IllegalStateException("Already registered");
            Lease lease = new Lease(caller, token);
            token.linkToDeath(lease, 0);
            PencilEngineRegistry.Outcome outcome = named
                    ? registry.registerByPackage(caller, (f, a, b) -> {}, name)
                    : registry.registerListener(caller, (f, a, b) -> {});
            if (outcome.status != PencilEngineRegistry.Status.OK) {
                unlink(lease);
                throw new SecurityException("Registration rejected: " + outcome.status);
            }
            lease.id = outcome.lease;
            leases.put(caller, lease);
            if (lease.dead || !token.isBinderAlive()) {
                release(lease);
                throw new RemoteException("Callback died during registration");
            }
        }
    }

    private int trigger(PencilEngineRegistry.Caller caller, int function, int value) {
        synchronized (operations) {
            // Unsupported effects must not advance the registry or report success.
            if (closed || !backend.supports(function)) return -1;
            PencilEngineRegistry.Outcome outcome = registry.triggerFunction(caller, function, value);
            if (!apply(caller, outcome)) {
                Lease lease = leases.get(caller);
                if (lease != null) release(lease);
                return -1;
            }
            if (outcome.status != PencilEngineRegistry.Status.OK) {
                Lease lease = leases.get(caller);
                if (lease != null && outcome.status == PencilEngineRegistry.Status.UNAUTHORIZED) {
                    release(lease);
                }
                return -1;
            }
            return 0;
        }
    }

    private boolean apply(PencilEngineRegistry.Caller caller,
            PencilEngineRegistry.Outcome outcome) {
        Lease lease = leases.get(caller);
        long leaseId = lease == null ? 0 : lease.id;
        boolean success = true;
        long identity = Binder.clearCallingIdentity();
        try {
            for (PencilEngineRegistry.Action action : outcome.actions) {
                try { success &= backend.apply(caller, leaseId, action); }
                catch (RuntimeException failure) { success = false; }
            }
        } finally { Binder.restoreCallingIdentity(identity); }
        return success;
    }

    private void release(Lease lease) {
        apply(lease.caller, registry.onClientDeath(lease.caller, lease.id));
        leases.remove(lease.caller, lease);
        unlink(lease);
    }

    private static void unlink(Lease lease) {
        try {
            lease.token.unlinkToDeath(lease, 0);
        } catch (NoSuchElementException alreadyDead) {
            // Binder death may already have removed this recipient.
        }
    }

    /** Service-owner teardown: revoke all requests before discarding the backend. */
    public void close() {
        synchronized (operations) {
            if (closed) return;
            closed = true;
            for (Lease lease : new ArrayList<>(leases.values())) release(lease);
        }
    }

    /** Trusted backend cancellation after it removes its UI; no Binder transaction. */
    public void cancelColorPick(int pid, int uid, long leaseId) {
        synchronized (operations) {
            registry.cancelColorPick(new PencilEngineRegistry.Caller(pid, uid), leaseId);
        }
    }

    /** Trusted backend completion; not an exposed Binder transaction. */
    public void completeColorPick(int pid, int uid, long leaseId, int space, int color) {
        IBinder callback;
        synchronized (operations) {
            PencilEngineRegistry.Caller caller = new PencilEngineRegistry.Caller(pid, uid);
            Lease lease = leases.get(caller);
            if (lease == null || lease.id != leaseId) return;
            PencilEngineRegistry.Outcome outcome = registry.completeColorPick(
                    caller, leaseId, space, color);
            if (outcome.status != PencilEngineRegistry.Status.OK) return;
            callback = lease.token;
        }
        // Synchronous stock callback, deliberately outside both registry and transport locks.
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        long identity = Binder.clearCallingIdentity();
        try {
            data.writeInterfaceToken(PencilEngineProtocol.CALLBACK_DESCRIPTOR);
            data.writeInt(PencilEngineProtocol.FUNCTION_COLOR_PICKER);
            data.writeInt(space);
            data.writeInt(color);
            if (!callback.transact(PencilEngineProtocol.CALLBACK_TX_ON_CHANGE, data, reply, 0)) {
                throw new RemoteException("Callback transaction unsupported");
            }
            reply.readException();
        } catch (RemoteException | RuntimeException failure) {
            synchronized (operations) {
                PencilEngineRegistry.Caller caller = new PencilEngineRegistry.Caller(pid, uid);
                Lease lease = leases.get(caller);
                if (lease != null && lease.id == leaseId) release(lease);
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
            reply.recycle();
            data.recycle();
        }
    }
}
