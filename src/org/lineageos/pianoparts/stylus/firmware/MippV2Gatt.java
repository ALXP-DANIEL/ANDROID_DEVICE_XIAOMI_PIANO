/* SPDX-License-Identifier: Apache-2.0 */
package org.lineageos.pianoparts.stylus.firmware;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.os.Handler;
import android.os.SystemClock;
import java.util.UUID;

/** Stock V2 notification subscription and whole characteristic writes (OOB j.h/n). */
public final class MippV2Gatt implements PenFirmwareGatt, MippV2Session.Transport {
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private final BluetoothGatt gatt;
    private final Handler handler;
    private final FirmwareImage image;
    private final Listener listener;
    private final Runnable timeout = () -> fail("V2 firmware operation timed out");
    private final Runnable beginQuery = this::query;
    private MippV2Session session;
    private boolean active, prepared, descriptorDone, used, failed, writePending;
    private Runnable delayedFrame;
    private long started;

    public MippV2Gatt(BluetoothGatt gatt, Handler handler, FirmwareImage image, Listener listener) {
        this.gatt=gatt; this.handler=handler; this.image=image; this.listener=listener;
    }
    public void prepare(int vendor, int product, int battery, boolean exclusive) {
        if (used) throw new IllegalStateException("Adapter already used");
        used=true;
        if (!exclusive || !MippV2Protocol.supports(vendor,product) || battery<20 || battery>100
                || image.manifest.vendorId!=vendor || image.manifest.productId!=product
                || !FirmwareImage.PROFILE_V2.equals(image.manifest.profile)) {
            throw new IllegalArgumentException("Verified matching V2 image, fresh battery and exclusive GATT required");
        }
        BluetoothGattService service=gatt.getService(MippV2Protocol.SERVICE);
        if (service==null) { fail("V2 service absent"); return; }
        BluetoothGattCharacteristic write=service.getCharacteristic(MippV2Protocol.WRITE);
        BluetoothGattCharacteristic notify=service.getCharacteristic(MippV2Protocol.NOTIFY);
        if (write==null || notify==null
                || (write.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE)==0
                || (notify.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY)==0) {
            fail("V2 characteristic absent or incompatible"); return;
        }
        BluetoothGattDescriptor descriptor=notify.getDescriptor(CCCD);
        if (descriptor==null) { fail("V2 notification descriptor absent"); return; }
        active=true; started=SystemClock.elapsedRealtime();
        session=new MippV2Session(vendor,product,image.rawBytes(),image.manifest.targetVersion,this,
                new MippV2Session.Listener() {
            public void progress(int percent) { listener.progress(percent); }
            public void finished(MippV2Session.State state,String reason) {
                active=false; writePending=false; cleanup();
                if (state==MippV2Session.State.FAILED) failed=true;
                listener.finished(state==MippV2Session.State.VERIFY_REVISION ? State.VERIFY_REVISION
                        : state==MippV2Session.State.COMPLETE ? State.COMPLETE
                        : state==MippV2Session.State.CANCELLED ? State.CANCELLED : State.FAILED,reason);
            }
        });
        try {
            if (!gatt.setCharacteristicNotification(notify,true)) { fail("V2 notifications rejected"); return; }
            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            arm();
            if (active && !gatt.writeDescriptor(descriptor)) fail("V2 descriptor write rejected");
        } catch (RuntimeException error) { fail("V2 notification setup failed"); }
    }
    public void onDescriptorWrite(UUID id,int result) {
        if (!active || descriptorDone || !MippV2Protocol.NOTIFY.equals(id)) return;
        if (result!=BluetoothGatt.GATT_SUCCESS) { fail("V2 notification setup failed"); return; }
        prepared=true; descriptorDone=true; handler.removeCallbacks(timeout);
    }
    public boolean isPrepared() { return active && prepared && !failed && session.state()==MippV2Session.State.NEW; }
    public void start() {
        if (!isPrepared()) throw new IllegalStateException("Not prepared");
        prepared=false; started=SystemClock.elapsedRealtime(); arm();
        // Stock n(): query one second after notification setup.
        if (active && !handler.postDelayed(beginQuery,1000)) fail("Could not schedule V2 query");
    }
    private void query() { if (active && session.state()==MippV2Session.State.NEW) session.start(); }
    public void writeFrame(byte[] frame) {
        if (!active || writePending) throw new IllegalStateException("V2 GATT not writable");
        if (session.state()==MippV2Session.State.BEGIN) {
            writePending=true; arm();
            delayedFrame=() -> { delayedFrame=null; writePending=false; if (active) writeNow(frame); };
            if (active && !handler.postDelayed(delayedFrame,100)) fail("Could not schedule V2 start");
        } else writeNow(frame);
    }
    private void writeNow(byte[] frame) {
        BluetoothGattCharacteristic characteristic=gatt.getService(MippV2Protocol.SERVICE)
                .getCharacteristic(MippV2Protocol.WRITE);
        // Match stock j.h: setValue + default whole-value write. No invented framing/fragmentation.
        characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        characteristic.setValue(frame);
        writePending=true; arm();
        try {
            if (active && !gatt.writeCharacteristic(characteristic)) {
                writePending=false; session.onWrite(false);
            }
        } catch (RuntimeException rejected) { writePending=false; session.onWrite(false); }
    }
    public void onCharacteristicWrite(UUID id,int result) {
        if (!active || !writePending || delayedFrame!=null || !MippV2Protocol.WRITE.equals(id)) return;
        writePending=false;
        session.onWrite(result==BluetoothGatt.GATT_SUCCESS);
    }
    public void onNotification(UUID id,byte[] value) {
        if (!active || !MippV2Protocol.NOTIFY.equals(id) || session.state()==MippV2Session.State.NEW) return;
        arm(); if (active) session.onReply(value);
    }
    public void onCharacteristicRead(UUID id,byte[] value,int result) { }
    public void onMtuChanged(int mtu,int result) { }
    public void verifyRevision(String revision) { if (session!=null) session.verifyRevision(revision); }
    public void disconnected() { if (active) fail("Disconnected during V2 firmware operation"); }
    public void cancel() { if (session!=null) session.cancel(); else if (!failed) { failed=true; cleanup(); listener.finished(State.CANCELLED,"V2 preparation cancelled"); } }
    private void arm() {
        handler.removeCallbacks(timeout);
        long left=120000-(SystemClock.elapsedRealtime()-started);
        if (left<=0) { fail("V2 firmware operation timed out"); return; }
        if (!handler.postDelayed(timeout,Math.min(15000,left))) fail("Could not arm V2 timeout");
    }
    private void fail(String reason) {
        if (failed) return;
        failed=true; active=false; writePending=false; cleanup();
        if (session!=null) session.transportFailure(reason);
        else listener.finished(State.FAILED,reason);
    }
    private void cleanup() {
        handler.removeCallbacks(timeout); handler.removeCallbacks(beginQuery);
        if (delayedFrame!=null) handler.removeCallbacks(delayedFrame);
        delayedFrame=null;
        try { gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED); }
        catch (RuntimeException ignored) { }
    }
}
