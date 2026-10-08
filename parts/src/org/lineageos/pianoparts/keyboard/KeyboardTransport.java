/*
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pianoparts.keyboard;

import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

import java.util.Arrays;

/**
 * Sends packets to the keyboard nanoapp HAL and receives its replies through
 * a callback, split into single replies.
 */
final class KeyboardTransport {

    private static final String TAG = "PianoPartsKeyboard";

    private static final String SERVICE =
            "vendor.xiaomi.hardware.keyboardnanoapp_aidl.IKeyboardNanoapp_aidl/default";
    private static final String DESCRIPTOR =
            "vendor.xiaomi.hardware.keyboardnanoapp_aidl.IKeyboardNanoapp_aidl";
    private static final String CALLBACK_DESCRIPTOR =
            "vendor.xiaomi.hardware.keyboardnanoapp_aidl.INanoappCallback_aidl";
    private static final String CALLBACK_HASH = "8dfe728c2dd203419628eb8462c136809f607d3c";
    private static final int CALLBACK_VERSION = 1;

    private static final int TRANSACTION_SEND_CMD = IBinder.FIRST_CALL_TRANSACTION;
    private static final int TRANSACTION_SET_CALLBACK = IBinder.FIRST_CALL_TRANSACTION + 1;
    private static final int TRANSACTION_DATA_RECEIVE = IBinder.FIRST_CALL_TRANSACTION;
    private static final int TRANSACTION_ERROR_RECEIVE = IBinder.FIRST_CALL_TRANSACTION + 1;

    interface Listener {
        /** A reply from the keyboard side, starting at its report id. */
        void onReply(byte[] reply);
    }

    private final Listener mListener;
    private final Handler mHandler;
    private IBinder mService;

    private final Binder mCallback = new Binder() {
        {
            markVintfStability();
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            switch (code) {
                case TRANSACTION_DATA_RECEIVE: {
                    data.enforceInterface(CALLBACK_DESCRIPTOR);
                    byte[] buffer = data.createByteArray();
                    if (buffer != null) {
                        mHandler.post(() -> split(buffer));
                    }
                    return true;
                }
                case TRANSACTION_ERROR_RECEIVE:
                    data.enforceInterface(CALLBACK_DESCRIPTOR);
                    Log.w(TAG, "Keyboard nanoapp error " + data.readInt());
                    return true;
                case IBinder.INTERFACE_TRANSACTION:
                    reply.writeString(CALLBACK_DESCRIPTOR);
                    return true;
                case 16777215: // getInterfaceVersion
                    data.enforceInterface(CALLBACK_DESCRIPTOR);
                    reply.writeNoException();
                    reply.writeInt(CALLBACK_VERSION);
                    return true;
                case 16777214: // getInterfaceHash
                    data.enforceInterface(CALLBACK_DESCRIPTOR);
                    reply.writeNoException();
                    reply.writeString(CALLBACK_HASH);
                    return true;
            }
            return super.onTransact(code, data, reply, flags);
        }
    };

    KeyboardTransport(Listener listener) {
        mListener = listener;
        HandlerThread thread = new HandlerThread("PianoPartsKeyboard");
        thread.start();
        mHandler = new Handler(thread.getLooper());
    }

    Handler getHandler() {
        return mHandler;
    }

    /** Connects to the HAL and registers the reply callback. */
    boolean connect() {
        mService = ServiceManager.checkService(SERVICE);
        if (mService == null) {
            Log.e(TAG, "Keyboard nanoapp service is not available");
            return false;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeStrongBinder(mCallback);
            mService.transact(TRANSACTION_SET_CALLBACK, data, reply, 0);
            reply.readException();
            return true;
        } catch (RemoteException | RuntimeException e) {
            Log.e(TAG, "Failed to register the keyboard callback", e);
            mService = null;
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    boolean send(byte[] packet) {
        if (mService == null && !connect()) {
            return false;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeByteArray(packet);
            mService.transact(TRANSACTION_SEND_CMD, data, reply, 0);
            reply.readException();
            return true;
        } catch (RemoteException | RuntimeException e) {
            Log.e(TAG, "Failed to send a keyboard command", e);
            mService = null;
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    // The HAL may deliver several replies in one buffer, each prefixed by
    // 0xAA and its length.
    private void split(byte[] buffer) {
        int position = 0;
        while (position < buffer.length) {
            if (buffer[position] == (byte) 0xAA && position + 1 < buffer.length) {
                int length = buffer[position + 1] & 0xFF;
                if (position + 2 + length > buffer.length) {
                    Log.w(TAG, "Dropping a truncated keyboard reply");
                    return;
                }
                dispatch(Arrays.copyOfRange(buffer, position + 2, position + 2 + length));
                position += length + 2;
            } else {
                Log.w(TAG, "Unexpected keyboard data: " + KeyboardProtocol.hex(buffer, 16));
                return;
            }
        }
    }

    private void dispatch(byte[] reply) {
        if (reply.length < 5) {
            return;
        }
        byte report = reply[0];
        boolean known = report == 0x22 || report == 0x23 || report == 0x24 || report == 0x26;
        byte version = reply[1];
        byte source = reply[2];
        // Match stock CommunicationUtil.dealReadSocketPackage: upgrade
        // replies come from MCU or keyboard addresses 0x38/0x39; feature
        // replies come only from the keyboard at 0x38.
        boolean sender = version == KeyboardProtocol.VERSION_UPGRADE
                && (source == KeyboardProtocol.ADDRESS_MCU
                        || source == KeyboardProtocol.ADDRESS_KEYBOARD || source == 0x39)
                || version == KeyboardProtocol.VERSION_FEATURE
                        && source == KeyboardProtocol.ADDRESS_KEYBOARD;
        if (!known || !sender || reply[3] != KeyboardProtocol.ADDRESS_PAD) {
            Log.d(TAG, "Ignoring keyboard reply: " + KeyboardProtocol.hex(reply, reply.length));
            return;
        }
        mListener.onReply(reply);
    }
}
