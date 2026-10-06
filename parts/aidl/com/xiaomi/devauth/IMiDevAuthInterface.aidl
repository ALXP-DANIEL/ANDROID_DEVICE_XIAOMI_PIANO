/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Client copy of the interface of the stock MiDevAuthService
 * (com.xiaomi.devauth), which the keyboard cover check binds to. Only the
 * first four calls are declared; their transaction order matches the service.
 */

package com.xiaomi.devauth;

interface IMiDevAuthInterface {
    byte[] getChallenge(int length);
    byte[] chooseKey(in byte[] uid, in byte[] keyMeta1, in byte[] keyMeta2);
    int tokenVerify(int type, in byte[] uid, in byte[] keyMeta, in byte[] challenge,
            in byte[] token);
    byte[] tokenGet(int type, in byte[] uid, in byte[] keyMeta, in byte[] challenge);
}
