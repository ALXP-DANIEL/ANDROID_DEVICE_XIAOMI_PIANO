package android.os;

import java.util.Map;

public abstract class UEventObserver {
    public static final class UEvent {
        private final Map<String, String> values;
        public UEvent(Map<String, String> values) { this.values = values; }
        public String get(String key) { return values.get(key); }
    }
    public void startObserving(String match) {}
    public void stopObserving() {}
    public abstract void onUEvent(UEvent event);
}
