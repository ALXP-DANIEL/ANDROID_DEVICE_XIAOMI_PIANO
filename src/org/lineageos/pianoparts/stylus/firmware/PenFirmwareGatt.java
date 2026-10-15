/* SPDX-License-Identifier: Apache-2.0 */
package org.lineageos.pianoparts.stylus.firmware;

import android.bluetooth.BluetoothGatt;
import android.os.Handler;
import java.util.UUID;

/** Exclusive updater callback boundary shared by the two stock transports. */
public interface PenFirmwareGatt {
    enum State { VERIFY_REVISION, COMPLETE, FAILED, CANCELLED }
    interface Listener {
        void progress(int percent);
        void finished(State state, String reason);
    }
    static PenFirmwareGatt create(BluetoothGatt gatt, Handler handler, FirmwareImage image, Listener listener) {
        if (FirmwareImage.PROFILE_V2.equals(image.manifest.profile)) {
            return new MippV2Gatt(gatt, handler, image, listener);
        }
        if (!FirmwareImage.PROFILE.equals(image.manifest.profile)) throw new IllegalArgumentException("Unknown firmware transport");
        return new SuotaGatt(gatt, handler, image, new SuotaV1.Listener() {
            public void progress(int percent) { listener.progress(percent); }
            public void finished(SuotaV1.State state, String reason) {
                listener.finished(state == SuotaV1.State.VERIFY_REVISION ? State.VERIFY_REVISION
                        : state == SuotaV1.State.COMPLETE ? State.COMPLETE
                        : state == SuotaV1.State.CANCELLED ? State.CANCELLED : State.FAILED, reason);
            }
        });
    }
    void prepare(int vendor, int product, int battery, boolean exclusive);
    void onCharacteristicRead(UUID id, byte[] value, int result);
    void onMtuChanged(int mtu, int result);
    void onDescriptorWrite(UUID id, int result);
    void onCharacteristicWrite(UUID id, int result);
    void onNotification(UUID id, byte[] value);
    boolean isPrepared();
    void start();
    void verifyRevision(String revision);
    void disconnected();
    void cancel();
}
