/* SPDX-License-Identifier: Apache-2.0 */
package miui.util;

import android.app.ActivityThread;
import android.app.Application;
import android.util.Log;
import android.util.Xml;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import org.xmlpull.v1.XmlPullParser;

/** App-local, read-only access to pinned piano hardware metadata. */
public final class FeatureParser {
    public static final int TYPE_BOOL = 1, TYPE_INTEGER = 2, TYPE_STRING = 3,
            TYPE_STRING_ARRAY = 4, TYPE_INTEGER_ARRAY = 5, TYPE_FLOAT = 6;
    private static final Map<String, Object> VALUES = load();

    private FeatureParser() {}

    private static Map<String, Object> load() {
        Map<String, Object> values = new HashMap<>();
        Application app = ActivityThread.currentApplication();
        if (app == null || !"piano".equals(android.os.Build.DEVICE)) return values;
        try (InputStream input = app.getAssets().open("piano-device-features.xml")) {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(input, "UTF-8");
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.getEventType() != XmlPullParser.START_TAG) continue;
                String tag = parser.getName();
                String name = parser.getAttributeValue(null, "name");
                if (name == null) continue;
                switch (tag) {
                    case "bool": values.put(name, Boolean.valueOf(parser.nextText())); break;
                    case "integer": values.put(name, Integer.valueOf(parser.nextText())); break;
                    case "float": values.put(name, Float.valueOf(parser.nextText())); break;
                    case "string": values.put(name, parser.nextText()); break;
                    case "string-array":
                    case "integer-array": {
                        ArrayList<String> items = new ArrayList<>();
                        int depth = parser.getDepth();
                        while (parser.next() != XmlPullParser.END_DOCUMENT) {
                            if (parser.getEventType() == XmlPullParser.END_TAG
                                    && parser.getDepth() == depth) break;
                            if (parser.getEventType() == XmlPullParser.START_TAG
                                    && "item".equals(parser.getName())) items.add(parser.nextText());
                        }
                        if ("string-array".equals(tag)) {
                            values.put(name, items.toArray(new String[0]));
                        } else {
                            int[] ints = new int[items.size()];
                            for (int i = 0; i < ints.length; i++) ints[i] = Integer.parseInt(items.get(i));
                            values.put(name, ints);
                        }
                        break;
                    }
                    default: break;
                }
            }
        } catch (Exception failure) {
            // An incomplete file must not leave a partially enabled feature set.
            values.clear();
            Log.w("PianoNotesCompat", "Cannot load pinned piano feature metadata", failure);
        }
        return values;
    }

    public static boolean getBoolean(String name, boolean fallback) {
        Object value = VALUES.get(name);
        return value instanceof Boolean ? (Boolean) value : fallback;
    }
    public static int getInteger(String name, int fallback) {
        Object value = VALUES.get(name);
        return value instanceof Integer ? (Integer) value : fallback;
    }
    public static Float getFloat(String name, float fallback) {
        Object value = VALUES.get(name);
        return value instanceof Float ? (Float) value : fallback;
    }
    public static String getString(String name) {
        Object value = VALUES.get(name);
        return value instanceof String ? (String) value : null;
    }
    public static int[] getIntArray(String name) {
        Object value = VALUES.get(name);
        return value instanceof int[] ? ((int[]) value).clone() : null;
    }
    public static String[] getStringArray(String name) {
        Object value = VALUES.get(name);
        return value instanceof String[] ? ((String[]) value).clone() : null;
    }
    public static boolean hasFeature(String name, int type) {
        Object value = VALUES.get(name);
        switch (type) {
            case TYPE_BOOL: return value instanceof Boolean;
            case TYPE_INTEGER: return value instanceof Integer;
            case TYPE_STRING: return value instanceof String;
            case TYPE_STRING_ARRAY: return value instanceof String[];
            case TYPE_INTEGER_ARRAY: return value instanceof int[];
            case TYPE_FLOAT: return value instanceof Float;
            default: return false;
        }
    }
}
