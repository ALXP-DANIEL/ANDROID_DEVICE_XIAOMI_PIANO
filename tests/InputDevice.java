/* SPDX-License-Identifier: Apache-2.0 */
package android.view;

/** Host identity fixture, excluded from the production source glob. */
public final class InputDevice {
    private final int vendor;
    private final int product;
    public InputDevice(int vendor, int product) { this.vendor = vendor; this.product = product; }
    public int getVendorId() { return vendor; }
    public int getProductId() { return product; }
    public boolean isVirtual() { return false; }
}
