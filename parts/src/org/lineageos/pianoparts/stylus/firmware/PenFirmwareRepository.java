/* SPDX-License-Identifier: Apache-2.0 */
package org.lineageos.pianoparts.stylus.firmware;

import org.json.JSONObject;
import java.nio.file.Files;
import java.nio.file.Path;

/** Only ROM-bundled release metadata is trusted; never accepts app-supplied image paths/digests. */
public final class PenFirmwareRepository {
    private static final Path ROOT = Path.of("/system_ext/etc/piano_pen");
    public static FirmwareImage find(int vendor, int product, String installedVersion) throws Exception {
        return find(ROOT, vendor, product, installedVersion);
    }
    public static FirmwareImage find(Path root, int vendor, int product, String installedVersion) throws Exception {
        if (vendor != 34 || product != 20609 || installedVersion == null) return null;
        Path manifestPath = root.resolve("manifest.json");
        if (!Files.isRegularFile(manifestPath)) return null;
        if (Files.size(manifestPath) > 8192) throw new IllegalArgumentException("Firmware metadata too large");
        JSONObject json = new JSONObject(Files.readString(manifestPath));
        String file = json.getString("file");
        if (!file.matches("[A-Za-z0-9_.-]+\\.img") || file.contains(".."))
            throw new IllegalArgumentException("Invalid firmware filename");
        FirmwareImage.Manifest manifest = new FirmwareImage.Manifest(json.getInt("vendorId"),
                json.getInt("productId"), json.getString("profile"),
                json.getString("targetVersion"), json.getString("sha256"));
        if (!newer(manifest.targetVersion, installedVersion)) return null;
        Path image = root.resolve(file);
        if (!Files.isRegularFile(image) || Files.isSymbolicLink(image) ||
                Files.size(image) > FirmwareImage.MAX_BYTES) throw new IllegalArgumentException("Invalid firmware image");
        return FirmwareImage.validate(manifest, Files.readAllBytes(image), vendor, product);
    }
    private static boolean newer(String target, String current) {
        if (!target.matches("[0-9]+\\.[0-9]+\\.[0-9]+") ||
                !current.matches("[0-9]+\\.[0-9]+\\.[0-9]+")) return false;
        String[] a = target.split("\\."), b = current.split("\\.");
        for (int i = 0; i < 3; i++) {
            int comparison = new java.math.BigInteger(a[i]).compareTo(new java.math.BigInteger(b[i]));
            if (comparison != 0) return comparison > 0;
        }
        return false;
    }
}
