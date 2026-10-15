/* SPDX-License-Identifier: Apache-2.0 */
package org.lineageos.pianoparts.stylus.firmware;

/**
 * Offline-testable stock V2 query/start/requested-block/end sequence.
 * Not connected to GATT or the firmware repository. The future transport must
 * fragment a frame for the negotiated MTU and call onWrite only after all its
 * fragments succeed. Caller serializes every callback and owns the timeout.
 */
public final class MippV2Session {
    public enum State { NEW, QUERY, BEGIN, DATA, END, VERIFY_REVISION, COMPLETE, FAILED, CANCELLED }
    public interface Transport { void writeFrame(byte[] frame); }
    public interface Listener { void progress(int percent); void finished(State state, String reason); }
    private final byte[] image;
    private final String targetVersion;
    private final Transport transport;
    private final Listener listener;
    private State state = State.NEW;
    private MippV2Protocol.Plan plan;
    private boolean writePending;
    private byte[] earlyReply;
    private int sequence, budget;

    public MippV2Session(int vendor, int product, byte[] image, String targetVersion,
            Transport transport, Listener listener) {
        if (!MippV2Protocol.supports(vendor, product) || image == null || image.length == 0
                || image.length > FirmwareImage.MAX_BYTES || targetVersion == null
                || !targetVersion.matches("[0-9]+\\.[0-9]+\\.[0-9]+")
                || transport == null || listener == null) throw new IllegalArgumentException("Invalid V2 session");
        this.image = image.clone(); this.targetVersion = targetVersion;
        this.transport = transport; this.listener = listener;
    }
    public State state() { return state; }
    public void start() {
        if (state != State.NEW) throw new IllegalStateException("Already started");
        state = State.QUERY; send(MippV2Protocol.query());
    }
    private void send(byte[] frame) {
        if (writePending) throw new IllegalStateException("Overlapping frame writes");
        writePending = true;
        try { transport.writeFrame(frame); }
        catch (RuntimeException failure) { fail("Transport rejected frame"); }
    }
    public void onWrite(boolean success) {
        if (terminal() || !writePending) return;
        writePending = false;
        if (!success) { fail("Frame write failed"); return; }
        byte[] reply = earlyReply; earlyReply = null;
        if (reply != null) onReply(reply);
    }
    public void onReply(byte[] bytes) {
        if (terminal() || state == State.NEW || state == State.VERIFY_REVISION) return;
        final MippV2Protocol.Reply reply;
        try { reply = MippV2Protocol.parse(bytes); }
        catch (IllegalArgumentException malformed) { fail("Malformed V2 reply"); return; }
        if (writePending) {
            if (earlyReply != null) { fail("Multiple replies before write completion"); return; }
            earlyReply = bytes.clone(); return;
        }
        byte[] payload = reply.payload();
        switch (state) {
            case QUERY:
                if (reply.opcode != 1 || payload.length != 5 || payload[3] < 1 || payload[3] > 3) {
                    fail("Update mode not ready or invalid query reply"); return;
                }
                try { plan = new MippV2Protocol.Plan(image, payload[4] & 255); }
                catch (IllegalArgumentException unsupported) { fail("Unsupported negotiated block plan"); return; }
                budget = plan.packetCount * 4 + 16;
                state = State.BEGIN; send(plan.begin()); break;
            case BEGIN:
                if (reply.opcode != 2 || payload.length != 1 || payload[0] != 0) {
                    fail("Firmware start rejected"); return;
                }
                state = State.DATA; packet(1); break;
            case DATA:
                // Stock uses response opcode4 even for the 512/other-size request opcodes.
                if (reply.opcode != 4 || payload.length != 3) { fail("Invalid block reply"); return; }
                int requested = ((payload[0] & 255) << 8) | (payload[1] & 255);
                int status = payload[2] & 255;
                if ((status != 0 && status != 2) || requested < 1 || requested > plan.packetCount
                        || requested > sequence + 1 || (status == 2 && requested > sequence)) {
                    fail("Rejected CRC/status/sequence"); return;
                }
                if (requested == plan.packetCount && sequence == plan.packetCount && status == 0) {
                    state = State.END; send(MippV2Protocol.finish());
                } else packet(requested);
                break;
            case END:
                if (reply.opcode != 3 || payload.length != 1 || payload[0] != 0) {
                    fail("Completion not acknowledged"); return;
                }
                state = State.VERIFY_REVISION;
                listener.finished(state, "Reconnect and verify target revision"); break;
            default: fail("Unexpected reply state");
        }
    }
    private void packet(int requested) {
        if (--budget < 0) { fail("Retransmission budget exhausted"); return; }
        sequence = requested;
        listener.progress((int) ((long) (sequence - 1) * 99 / plan.packetCount));
        send(plan.packet(sequence));
    }
    public void verifyRevision(String version) {
        if (state != State.VERIFY_REVISION) throw new IllegalStateException("Transfer not acknowledged");
        if (!targetVersion.equals(version)) { fail("Post-update revision mismatch"); return; }
        state = State.COMPLETE; listener.progress(100); listener.finished(state, "Target revision verified");
    }
    public void timeout() { if (!terminal()) fail("Firmware operation timed out"); }
    public void cancel() {
        if (terminal()) return;
        state = State.CANCELLED; writePending = false; earlyReply = null;
        listener.finished(state, "Cancelled; reconnect before retry");
    }
    private void fail(String reason) {
        if (terminal()) return;
        state = State.FAILED; writePending = false; earlyReply = null;
        listener.finished(state, reason);
    }
    private boolean terminal() { return state == State.COMPLETE || state == State.FAILED || state == State.CANCELLED; }
}
