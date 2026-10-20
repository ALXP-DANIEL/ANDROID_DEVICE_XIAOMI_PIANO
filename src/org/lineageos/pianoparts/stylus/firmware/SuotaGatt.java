/* SPDX-License-Identifier: Apache-2.0 */
package org.lineageos.pianoparts.stylus.firmware;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothStatusCodes;
import android.os.Handler;
import java.util.UUID;

/** Exclusive GATT adapter: owner forwards callbacks on its handler and suspends normal MIPP. */
public final class SuotaGatt implements SuotaV1.Transport {
    public static final UUID PATCH_SIZE = UUID.fromString("42c3dfdd-77be-4d9c-8454-8f875267fb3b");
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private final BluetoothGatt gatt;
    private final Handler handler;
    private final FirmwareImage image;
    private final SuotaV1.Listener listener;
    private final Runnable timeout = this::onTimeout;
    private SuotaV1 transfer;
    private Runnable pendingReboot;
    private int patchSize;
    private boolean active, prepared, failed;
    private long startedAt;
    public SuotaGatt(BluetoothGatt gatt, Handler handler, FirmwareImage image, SuotaV1.Listener listener) {
        this.gatt = gatt; this.handler = handler; this.image = image; this.listener = listener;
    }
    /** PNP/battery must come from this connected session, never cached accessory name. */
    public void prepare(int vendorId, int productId, int battery, boolean exclusiveGatt) {
        if (active || prepared || failed) throw new IllegalStateException("Adapter already used");
        if (!exclusiveGatt || vendorId != 34 || productId != 20609 || battery < 20 || battery > 100)
            throw new IllegalArgumentException("Exclusive connected P81C with battery >=20 required");
        BluetoothGattService service = gatt.getService(SuotaV1.SERVICE);
        if (service == null) { abort("P81C SUOTA service absent"); return; }
        for (UUID id : new UUID[]{SuotaV1.STATUS, SuotaV1.MEMORY, SuotaV1.GPIO,
                SuotaV1.LENGTH, SuotaV1.DATA, PATCH_SIZE}) {
            if (service.getCharacteristic(id) == null) { abort("Required SUOTA characteristic absent"); return; }
        }
        if ((service.getCharacteristic(SuotaV1.STATUS).getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) == 0 ||
                (service.getCharacteristic(SuotaV1.DATA).getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) == 0) {
            abort("Unsupported SUOTA characteristic properties"); return;
        }
        for (UUID id : new UUID[]{SuotaV1.MEMORY, SuotaV1.GPIO, SuotaV1.LENGTH}) {
            if ((service.getCharacteristic(id).getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE) == 0) {
                abort("SUOTA control characteristic cannot be written"); return;
            }
        }
        BluetoothGattCharacteristic size = service.getCharacteristic(PATCH_SIZE);
        if ((size.getProperties() & BluetoothGattCharacteristic.PROPERTY_READ) == 0) {
            abort("SUOTA patch size cannot be read"); return;
        }
        active = true; startedAt = android.os.SystemClock.elapsedRealtime();
        arm(); if (!gatt.readCharacteristic(size)) abort("Could not read SUOTA patch size");
    }
    public void onCharacteristicRead(UUID id, byte[] value, int result) {
        if (!active || !PATCH_SIZE.equals(id)) return;
        if (result != BluetoothGatt.GATT_SUCCESS || value == null || value.length < 2) { abort("Invalid patch size"); return; }
        patchSize = (value[0] & 255) | ((value[1] & 255) << 8);
        if (patchSize < 1 || patchSize > 512) { abort("Unsupported patch size"); return; }
        arm(); if (!gatt.requestMtu(Math.max(23, patchSize + 3))) abort("Could not negotiate SUOTA MTU");
    }
    public void onMtuChanged(int mtu, int result) {
        if (!active || prepared || transfer != null || patchSize == 0) return;
        if (result != BluetoothGatt.GATT_SUCCESS || mtu < 23) { abort("Invalid SUOTA MTU"); return; }
        transfer = new SuotaV1(image, patchSize, mtu, this, new SuotaV1.Listener() {
            public void progress(int percent) { listener.progress(percent); }
            public void finished(SuotaV1.State state, String reason) {
                active = false; clearPendingReboot(); handler.removeCallbacks(timeout); restorePriority();
                listener.finished(state, reason);
            }
        });
        BluetoothGattCharacteristic status = characteristic(SuotaV1.STATUS);
        BluetoothGattDescriptor descriptor = status.getDescriptor(CCCD);
        if (descriptor == null || !gatt.setCharacteristicNotification(status, true)) { abort("SUOTA status notifications unavailable"); return; }
        arm(); if (gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) != BluetoothStatusCodes.SUCCESS)
            abort("SUOTA status descriptor write rejected");
    }
    public void onDescriptorWrite(UUID characteristic, int result) {
        if (!active || !SuotaV1.STATUS.equals(characteristic) || prepared) return;
        if (result != BluetoothGatt.GATT_SUCCESS) { abort("SUOTA notifications rejected"); return; }
        prepared = true;
        // Preparation performs no firmware write. Explicit caller action starts the transfer.
        handler.removeCallbacks(timeout);
    }
    public boolean isPrepared() { return prepared && active && !failed; }
    public SuotaV1.State state() { return transfer == null ? SuotaV1.State.NEW : transfer.state(); }
    public void start() {
        if (!isPrepared() || transfer.state() != SuotaV1.State.NEW) throw new IllegalStateException("Not prepared");
        startedAt = android.os.SystemClock.elapsedRealtime(); gatt.requestConnectionPriority(1); transfer.start();
    }
    public void write(UUID id, byte[] bytes, boolean withoutResponse) {
        if (!active) return;
        // Stock .44 waits 500 ms after the final end acknowledgement before issuing reboot.
        if (SuotaV1.MEMORY.equals(id) && transfer.state() == SuotaV1.State.REBOOT) {
            arm();
            pendingReboot = () -> {
                pendingReboot = null;
                if (active && transfer.state() == SuotaV1.State.REBOOT) writeNow(id, bytes, withoutResponse);
            };
            if (!handler.postDelayed(pendingReboot, 500L)) transfer.transportFailure("Could not schedule SUOTA reboot");
            return;
        }
        writeNow(id, bytes, withoutResponse);
    }
    private void writeNow(UUID id, byte[] bytes, boolean withoutResponse) {
        if (!active) return;
        arm();
        int type = withoutResponse ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
        try {
            if (gatt.writeCharacteristic(characteristic(id), bytes, type) != BluetoothStatusCodes.SUCCESS)
                transfer.onWrite(id, false);
        } catch (RuntimeException exception) {
            transfer.transportFailure("SUOTA GATT write threw " + exception.getClass().getSimpleName());
        }
    }
    public void onCharacteristicWrite(UUID id, int result) {
        if (active && transfer != null && !(pendingReboot != null && SuotaV1.MEMORY.equals(id)))
            transfer.onWrite(id, result == BluetoothGatt.GATT_SUCCESS);
    }
    public void onNotification(UUID id, byte[] value) {
        if (active && transfer != null && prepared && transfer.state() != SuotaV1.State.NEW && SuotaV1.STATUS.equals(id)) transfer.onStatus(value);
    }
    public void verifyRevision(String revision) { if (transfer != null) transfer.verifyRevision(revision); }
    public void disconnected() { if (active) abort("Disconnected during firmware operation"); }
    public void cancel() {
        clearPendingReboot();
        if (transfer != null && transfer.state() != SuotaV1.State.NEW) transfer.cancel();
        else if (active) { active = false; handler.removeCallbacks(timeout); listener.finished(SuotaV1.State.CANCELLED, "Preparation cancelled"); }
    }
    private BluetoothGattCharacteristic characteristic(UUID id) { return gatt.getService(SuotaV1.SERVICE).getCharacteristic(id); }
    private void arm() {
        handler.removeCallbacks(timeout);
        long remaining = 180_000L - (android.os.SystemClock.elapsedRealtime() - startedAt);
        handler.postDelayed(timeout, Math.max(1L, Math.min(15_000L, remaining)));
    }
    private void onTimeout() { if (transfer != null) transfer.timeout(); else abort("SUOTA preparation timed out"); }
    private void abort(String reason) {
        if (failed) return;
        failed = true; active = false; clearPendingReboot(); handler.removeCallbacks(timeout); restorePriority();
        if (transfer != null) transfer.transportFailure(reason);
        else listener.finished(SuotaV1.State.FAILED, reason);
    }
    private void clearPendingReboot() {
        if (pendingReboot != null) handler.removeCallbacks(pendingReboot);
        pendingReboot = null;
    }
    private void restorePriority() {
        // Revision verification occurs after the old transfer GATT has been closed.
        try { gatt.requestConnectionPriority(0); } catch (RuntimeException ignored) { }
    }
}
