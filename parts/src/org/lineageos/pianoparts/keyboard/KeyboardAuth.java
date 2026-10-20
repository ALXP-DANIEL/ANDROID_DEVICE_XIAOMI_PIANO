/*
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pianoparts.keyboard;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;

import com.xiaomi.devauth.IMiDevAuthInterface;

import java.util.Arrays;

/**
 * Checks that a keyboard cover is genuine, as the stock keyboard service does:
 * the cover and the stock MiDevAuthService (com.xiaomi.devauth) exchange keys
 * and tokens, with this class carrying the cover's side over the connector.
 *
 * {@link #check} blocks, so it must not run on the thread that receives the
 * cover's replies.
 */
final class KeyboardAuth {

    private static final String TAG = "PianoPartsKeyboardAuth";

    private static final String SERVICE_PACKAGE = "com.xiaomi.devauth";
    private static final String SERVICE_CLASS = "com.xiaomi.devauth.MiDevAuthService";

    static final int RESULT_OK = 0;
    static final int RESULT_REJECT = 1;
    static final int RESULT_AGAIN = 2;
    static final int RESULT_INTERNAL_ERROR = 3;
    static final int RESULT_TRANSFER_ERROR = 4;

    private static final int TRANSFER_ERROR_LIMIT = 2;
    private static final int INTERNAL_ERROR_LIMIT = 3;
    private static final long REBIND_DELAY_MS = 1000;

    /** Sends a packet to the cover and returns its reply, or null. */
    interface Link {
        byte[] request(byte[] packet);
    }

    private final Context mContext;
    private final Link mLink;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private volatile IMiDevAuthInterface mService;
    private boolean mBound;
    private int mTransferErrors;
    private int mInternalErrors;

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            mService = IMiDevAuthInterface.Stub.asInterface(binder);
            Log.i(TAG, "Connected to the device authentication service");
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            // Like stock, drop the binding and connect again a second later.
            Log.i(TAG, "Device authentication service disconnected");
            unbind();
            mHandler.postDelayed(KeyboardAuth.this::bind, REBIND_DELAY_MS);
        }
    };

    KeyboardAuth(Context context, Link link) {
        mContext = context;
        mLink = link;
    }

    /**
     * Binds like the stock MiuiKeyboardManager: early, for the current user, and
     * kept bound, so the service is connected by the time a check runs.
     */
    synchronized void bind() {
        if (mBound) {
            return;
        }
        Intent intent = new Intent().setComponent(new ComponentName(SERVICE_PACKAGE, SERVICE_CLASS));
        try {
            mBound = mContext.bindServiceAsUser(intent, mConnection, Context.BIND_AUTO_CREATE,
                    UserHandle.CURRENT);
        } catch (SecurityException e) {
            Log.w(TAG, "Not allowed to bind the device authentication service", e);
        }
        if (!mBound) {
            Log.w(TAG, "Cannot bind the device authentication service");
        }
    }

    synchronized void unbind() {
        if (mBound) {
            try {
                mContext.unbindService(mConnection);
            } catch (IllegalArgumentException e) {
                // Not bound.
            }
            mBound = false;
        }
        mService = null;
    }

    /**
     * One round of the check. {@code mcu2022} selects the older exchange of the
     * 2022 connector, which ends after the cover's token has been verified.
     */
    int check(boolean first, boolean mcu2022) {
        if (first) {
            mTransferErrors = 0;
            mInternalErrors = 0;
        }
        if (mTransferErrors > TRANSFER_ERROR_LIMIT) {
            return RESULT_TRANSFER_ERROR;
        }
        if (mInternalErrors > INTERNAL_ERROR_LIMIT) {
            return RESULT_INTERNAL_ERROR;
        }

        byte[] hello = mLink.request(KeyboardProtocol.authStart());
        if (hello == null || hello.length < 33 || hello[5] != 26
                || KeyboardProtocol.sum(hello, 0, 32) != hello[32]) {
            mTransferErrors++;
            Log.w(TAG, "Bad answer to the identity request");
            return RESULT_AGAIN;
        }
        byte[] uid = Arrays.copyOfRange(hello, 8, 24);
        byte[] keyMeta1 = Arrays.copyOfRange(hello, 24, 28);
        byte[] keyMeta2 = Arrays.copyOfRange(hello, 28, 32);

        bind();
        IMiDevAuthInterface service = mService;
        if (service == null) {
            Log.w(TAG, "The device authentication service is not available");
            return RESULT_AGAIN;
        }
        try {
            byte[] keyMeta = service.chooseKey(uid, keyMeta1, keyMeta2);
            if (keyMeta == null || keyMeta.length == 0) {
                return RESULT_REJECT;
            }
            if (keyMeta.length != 4) {
                mInternalErrors++;
                return RESULT_AGAIN;
            }
            byte[] challenge = service.getChallenge(16);
            if (challenge == null || challenge.length != 16) {
                mInternalErrors++;
                return RESULT_AGAIN;
            }
            byte[] answer = mLink.request(KeyboardProtocol.authStep3(keyMeta, challenge));
            if (answer == null || answer.length < 23) {
                mTransferErrors++;
                Log.w(TAG, "No token from the cover");
                return RESULT_AGAIN;
            }
            boolean shortAnswer = answer[5] == 16;
            if (mcu2022) {
                if (!shortAnswer || KeyboardProtocol.sum(answer, 0, 22) != answer[22]) {
                    mTransferErrors++;
                    return RESULT_AGAIN;
                }
            } else {
                if (shortAnswer) {
                    // Firmware 120 answers with the short form and cannot be
                    // checked further; stock accepts it.
                    return RESULT_OK;
                }
                if (answer[5] != 32 || answer.length < 39
                        || KeyboardProtocol.sum(answer, 0, 38) != answer[38]) {
                    mTransferErrors++;
                    return RESULT_AGAIN;
                }
            }
            byte[] token = Arrays.copyOfRange(answer, 6, 22);
            int verdict = service.tokenVerify(1, uid, keyMeta, challenge, token);
            if (verdict == 3) {
                mTransferErrors = 0;
                mInternalErrors++;
                return RESULT_AGAIN;
            }
            if (verdict == -1) {
                // The token is wrong: not a genuine cover.
                mInternalErrors = INTERNAL_ERROR_LIMIT + 1;
                mTransferErrors = TRANSFER_ERROR_LIMIT + 1;
                return RESULT_REJECT;
            }
            if (mcu2022) {
                if (verdict == 1) {
                    mTransferErrors = 0;
                    mInternalErrors = 0;
                    return RESULT_OK;
                }
                // Verified with an offline key (2) is checked again later.
                return verdict == 2 ? RESULT_AGAIN : RESULT_REJECT;
            }
            // Prove the tablet to the cover with the challenge it sent.
            byte[] coverChallenge = Arrays.copyOfRange(answer, 22, 38);
            byte[] padToken = service.tokenGet(1, uid, keyMeta, coverChallenge);
            if (padToken == null || padToken.length != 16) {
                mInternalErrors++;
                return RESULT_AGAIN;
            }
            mLink.request(KeyboardProtocol.authStep5(padToken));
            mTransferErrors = 0;
            mInternalErrors = 0;
            return RESULT_OK;
        } catch (RemoteException | RuntimeException e) {
            mInternalErrors++;
            Log.w(TAG, "The device authentication service failed", e);
            mService = null;
            return RESULT_AGAIN;
        }
    }
}
