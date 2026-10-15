/* SPDX-License-Identifier: Apache-2.0 */
package org.lineageos.pianoparts.stylus.firmware;

import java.util.Arrays;
import java.util.UUID;

/** Serialized .44 P81C SUOTA exchange. Caller confines callbacks to one handler. */
public final class SuotaV1 {
    public static final UUID SERVICE = UUID.fromString("0000fef5-0000-1000-8000-00805f9b34fb");
    public static final UUID STATUS = UUID.fromString("5f78df94-798c-46f5-990a-b3eb6a065c88");
    public static final UUID MEMORY = UUID.fromString("8082caa8-41a6-4021-91c6-56f9b954cc34");
    public static final UUID GPIO = UUID.fromString("724249f0-5ec3-4b5f-8804-42345af08651");
    public static final UUID LENGTH = UUID.fromString("9d84b9a3-000c-49d8-9183-855b673fda31");
    public static final UUID DATA = UUID.fromString("457871e8-d516-4ca1-9116-57d0b17b9cb2");
    public interface Transport { void write(UUID characteristic, byte[] data, boolean withoutResponse); }
    public interface Listener { void progress(int percent); void finished(State state, String reason); }
    public enum State { NEW, MEMORY, GPIO, LENGTH, DATA, BLOCK_ACK, END, REBOOT, VERIFY_REVISION, COMPLETE, FAILED, CANCELLED }
    private final Transport transport;
    private final Listener listener;
    private final byte[] data;
    private final String targetVersion;
    private final int chunk, block;
    private State state = State.NEW;
    private boolean writePending, memoryAck, statusReady, blockAck;
    private UUID pending;
    private int offset, blockEnd, lastLength;
    public SuotaV1(FirmwareImage image, int patchDataSize, int mtu, Transport transport, Listener listener) {
        if (patchDataSize < 1 || mtu < 23) throw new IllegalArgumentException("Invalid negotiated transport");
        this.chunk = Math.min(Math.min(patchDataSize, mtu - 3), 512);
        this.block = Math.min(chunk * 8, 65535);
        this.data = image.transferBytes(); this.targetVersion = image.manifest.targetVersion;
        this.transport = transport; this.listener = listener;
    }
    public State state() { return state; }
    public void start() {
        if (state != State.NEW) throw new IllegalStateException("Already started");
        state = State.MEMORY; send(MEMORY, le32(0x13000000), false);
    }
    private void send(UUID uuid, byte[] bytes, boolean noResponse) {
        if (writePending) throw new IllegalStateException("Overlapping GATT write");
        pending = uuid; writePending = true; transport.write(uuid, bytes, noResponse);
    }
    public void onWrite(UUID uuid, boolean success) {
        if (!writePending || !uuid.equals(pending) || terminal()) return;
        writePending = false;
        if (!success) { fail("GATT write failed"); return; }
        switch (state) {
            case MEMORY: memoryAck = true; ready(); break;
            case GPIO: beginBlock(); break;
            case LENGTH: state = State.DATA; sendChunk(); break;
            case DATA:
                if (offset < blockEnd) sendChunk();
                else { state = State.BLOCK_ACK; if (blockAck) finishBlock(); }
                break;
            case END: if (blockAck) reboot(); break;
            case REBOOT: state = State.VERIFY_REVISION; listener.finished(state, "Reconnect and verify target revision"); break;
            default: fail("Unexpected write state");
        }
    }
    /** Stock status16 completes memory initialization; status2 acknowledges block/end. */
    public void onStatus(byte[] bytes) {
        if (terminal() || bytes == null || bytes.length != 1) { if (!terminal()) fail("Malformed SUOTA status"); return; }
        int value = bytes[0] & 255;
        if (state == State.MEMORY && value == 16) { statusReady = true; ready(); }
        else if ((state == State.DATA || state == State.BLOCK_ACK || state == State.END) && value == 2) {
            if (state == State.DATA && offset < blockEnd) { fail("Premature block acknowledgement"); return; }
            blockAck = true;
            if (!writePending) { if (state == State.END) reboot(); else if (state == State.BLOCK_ACK) finishBlock(); }
        } else fail("Unexpected SUOTA status " + value);
    }
    private void ready() {
        if (memoryAck && statusReady) { state = State.GPIO; send(GPIO, le32(0x05060300), false); }
    }
    private void beginBlock() {
        blockAck = false; blockEnd = Math.min(data.length, offset + block);
        int size = blockEnd - offset;
        if (size != lastLength) {
            lastLength = size; state = State.LENGTH;
            send(LENGTH, new byte[]{(byte) size, (byte) (size >>> 8)}, false);
        } else { state = State.DATA; sendChunk(); }
    }
    private void sendChunk() {
        int end = Math.min(blockEnd, offset + chunk);
        byte[] value = Arrays.copyOfRange(data, offset, end); offset = end;
        send(DATA, value, true);
    }
    private void finishBlock() {
        listener.progress((int) ((long) offset * 100 / data.length));
        if (offset < data.length) beginBlock();
        else { blockAck = false; state = State.END; send(MEMORY, le32(0xfe000000), false); }
    }
    private void reboot() { state = State.REBOOT; send(MEMORY, le32(0xfd000000), false); }
    public void verifyRevision(String revision) {
        if (state != State.VERIFY_REVISION) throw new IllegalStateException("Transfer not verified");
        if (!targetVersion.equals(revision)) { fail("Post-update revision mismatch"); return; }
        state = State.COMPLETE; listener.finished(state, "Target revision verified");
    }
    public void timeout() { if (!terminal()) fail("Firmware operation timed out"); }
    public void transportFailure(String reason) { if (!terminal()) fail(reason); }
    public void cancel() { if (!terminal()) { state = State.CANCELLED; writePending = false; listener.finished(state, "Cancelled; reconnect before retry"); } }
    private void fail(String reason) { state = State.FAILED; writePending = false; listener.finished(state, reason); }
    private boolean terminal() { return state == State.COMPLETE || state == State.FAILED || state == State.CANCELLED; }
    private static byte[] le32(int value) { return new byte[]{(byte)value,(byte)(value>>>8),(byte)(value>>>16),(byte)(value>>>24)}; }
}
