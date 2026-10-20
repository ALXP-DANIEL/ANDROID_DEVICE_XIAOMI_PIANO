/* SPDX-License-Identifier: Apache-2.0 */
package org.lineageos.pianoparts.stylus.firmware;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** A digest proves identity against the supplied manifest, not publisher authenticity. */
public final class FirmwareImage {
    public static final String PROFILE = "p81c-suota-v1";
    public static final int MAX_BYTES = 16 * 1024 * 1024;
    public static final class Manifest {
        public final int vendorId, productId;
        public final String profile, targetVersion, sha256;
        public Manifest(int vendorId, int productId, String profile, String targetVersion, String sha256) {
            this.vendorId = vendorId; this.productId = productId; this.profile = profile;
            this.targetVersion = targetVersion; this.sha256 = sha256;
        }
    }
    public final Manifest manifest;
    private final byte[] image;
    private FirmwareImage(Manifest manifest, byte[] image) {
        this.manifest = manifest; this.image = image.clone();
    }
    public static FirmwareImage validate(Manifest manifest, byte[] image, int vendorId, int productId) {
        if (manifest == null || vendorId != 34 || productId != 20609 ||
                manifest.vendorId != vendorId || manifest.productId != productId ||
                !PROFILE.equals(manifest.profile)) throw new IllegalArgumentException("Unsupported pen/profile");
        if (manifest.targetVersion == null || !manifest.targetVersion.matches("[0-9]+\\.[0-9]+\\.[0-9]+"))
            throw new IllegalArgumentException("Missing target revision");
        if (manifest.sha256 == null || !manifest.sha256.matches("[0-9a-fA-F]{64}"))
            throw new IllegalArgumentException("Full SHA-256 required");
        if (image == null || image.length == 0 || image.length > MAX_BYTES)
            throw new IllegalArgumentException("Invalid image size");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(image);
            byte[] expected = new byte[32];
            for (int i = 0; i < 32; i++) expected[i] = (byte) Integer.parseInt(manifest.sha256.substring(i*2, i*2+2), 16);
            if (!MessageDigest.isEqual(digest, expected)) throw new IllegalArgumentException("Image digest mismatch");
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
        return new FirmwareImage(manifest, image);
    }
    public byte[] transferBytes() {
        byte[] data = java.util.Arrays.copyOf(image, image.length + 1);
        byte xor = 0; for (byte value : image) xor ^= value;
        data[image.length] = xor; return data;
    }
}
