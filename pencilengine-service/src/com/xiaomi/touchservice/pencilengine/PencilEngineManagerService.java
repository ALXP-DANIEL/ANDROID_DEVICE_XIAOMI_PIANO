/* SPDX-License-Identifier: Apache-2.0 */
package com.xiaomi.touchservice.pencilengine;

import android.app.Service;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.IBinder;
import org.lineageos.pianoparts.pencilengine.PencilEngineBinder;

/** AOSP implementation of the stock endpoint; currently implements color picking only. */
public final class PencilEngineManagerService extends Service {
    private PencilEngineBinder transport;
    private ScreenColorPicker picker;

    @Override public void onCreate() {
        super.onCreate();
        picker = new ScreenColorPicker(this);
        transport = new PencilEngineBinder(getPackageManager(), picker);
        picker.setTransport(transport);
    }

    @Override public IBinder onBind(Intent intent) { return transport; }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        picker.cancel();
    }

    @Override public void onDestroy() {
        transport.close();
        picker.close();
        super.onDestroy();
    }
}
