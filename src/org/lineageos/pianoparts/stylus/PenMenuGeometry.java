/* SPDX-License-Identifier: Apache-2.0 */
package org.lineageos.pianoparts.stylus;

/** Stock arc geometry, cropped to its actual visual bounds. Values are density-independent. */
final class PenMenuGeometry {
    final float radius = 197;
    final float iconRadius = 196;
    final float stroke = 65;
    final float start;
    final float sweep;
    final float left, top, width, height;
    final float[] x, y;

    PenMenuGeometry(int count) {
        if (count < 2 || count > 6) throw new IllegalArgumentException("Menu entries");
        start = count <= 4 ? 210 : (count - 1) * 20 + 150;
        sweep = -20 * (count - 1);
        x = new float[count]; y = new float[count];
        float minX = Float.MAX_VALUE, minY = minX, maxX = -minX, maxY = maxX;
        for (int i = 0; i < count; i++) {
            double angle = Math.toRadians(start - 20 * i);
            x[i] = (float) Math.cos(angle) * iconRadius;
            y[i] = (float) Math.sin(angle) * iconRadius;
            minX = Math.min(minX, x[i]); maxX = Math.max(maxX, x[i]);
            minY = Math.min(minY, y[i]); maxY = Math.max(maxY, y[i]);
        }
        // Include the leftmost arc between icons and its round caps/shadow.
        if (start >= 180 && start + sweep <= 180) minX = -radius;
        float margin = stroke / 2 + 6;
        left = minX - margin; top = minY - margin;
        width = maxX - minX + 2 * margin; height = maxY - minY + 2 * margin;
        for (int i = 0; i < count; i++) { x[i] -= left; y[i] -= top; }
    }

    static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(value, Math.max(min, max)));
    }
}
