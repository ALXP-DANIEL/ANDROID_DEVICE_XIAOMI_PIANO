package com.android.server.input;
public final class PianoPenLaserLeaseTest {
    static void check(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] args) {
        check(PianoPenLaserLease.accepts(true,7,7,100,100));
        check(PianoPenLaserLease.accepts(true,7,7,5100,100));
        check(!PianoPenLaserLease.accepts(true,7,7,5101,100));
        check(!PianoPenLaserLease.accepts(false,7,7,100,100));
        check(!PianoPenLaserLease.accepts(true,7,8,100,100));
        check(!PianoPenLaserLease.accepts(true,-1,-1,100,100));
        check(!PianoPenLaserLease.accepts(true,7,7,99,100));
        check(PianoPenLaserLease.suppressesRecentlyStopped(7,7,100,100));
        check(PianoPenLaserLease.suppressesRecentlyStopped(7,7,1600,100));
        check(!PianoPenLaserLease.suppressesRecentlyStopped(7,7,1601,100));
        check(!PianoPenLaserLease.suppressesRecentlyStopped(7,8,100,100));
        check(!PianoPenLaserLease.suppressesRecentlyStopped(-1,7,100,100));
        check(!PianoPenLaserLease.suppressesRecentlyStopped(7,7,99,100));
        System.out.println("Native laser lease: expiry, teardown, wrong device and clock guards passed");
    }
}
