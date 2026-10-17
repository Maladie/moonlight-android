package com.limelight.session;

import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** In-memory SharedPreferences for JVM tests of the model-owned stores. */
public final class MemoryPreferences implements SharedPreferences {
    final Map<String, Object> values = new HashMap<>();

    @Override public Map<String, ?> getAll() { return new HashMap<>(values); }
    @Override public String getString(String key, String fallback) {
        Object value = values.get(key);
        return value instanceof String ? (String) value : fallback;
    }
    @SuppressWarnings("unchecked")
    @Override public Set<String> getStringSet(String key, Set<String> fallback) {
        Object value = values.get(key);
        return value instanceof Set ? (Set<String>) value : fallback;
    }
    @Override public int getInt(String key, int fallback) {
        Object value = values.get(key);
        return value instanceof Integer ? (Integer) value : fallback;
    }
    @Override public long getLong(String key, long fallback) {
        Object value = values.get(key);
        return value instanceof Long ? (Long) value : fallback;
    }
    @Override public float getFloat(String key, float fallback) {
        Object value = values.get(key);
        return value instanceof Float ? (Float) value : fallback;
    }
    @Override public boolean getBoolean(String key, boolean fallback) {
        Object value = values.get(key);
        return value instanceof Boolean ? (Boolean) value : fallback;
    }
    @Override public boolean contains(String key) { return values.containsKey(key); }
    @Override public void registerOnSharedPreferenceChangeListener(
            OnSharedPreferenceChangeListener listener) { }
    @Override public void unregisterOnSharedPreferenceChangeListener(
            OnSharedPreferenceChangeListener listener) { }

    @Override public Editor edit() {
        Map<String, Object> pending = new HashMap<>();
        Set<String> removed = new HashSet<>();
        boolean[] clear = new boolean[1];
        return new Editor() {
            @Override public Editor putString(String key, String value) { pending.put(key, value); return this; }
            @Override public Editor putStringSet(String key, Set<String> value) { pending.put(key, value); return this; }
            @Override public Editor putInt(String key, int value) { pending.put(key, value); return this; }
            @Override public Editor putLong(String key, long value) { pending.put(key, value); return this; }
            @Override public Editor putFloat(String key, float value) { pending.put(key, value); return this; }
            @Override public Editor putBoolean(String key, boolean value) { pending.put(key, value); return this; }
            @Override public Editor remove(String key) { removed.add(key); return this; }
            @Override public Editor clear() { clear[0] = true; return this; }
            @Override public boolean commit() { apply(); return true; }
            @Override public void apply() {
                if (clear[0]) values.clear();
                for (String key : removed) values.remove(key);
                for (Map.Entry<String, Object> entry : pending.entrySet()) {
                    if (entry.getValue() == null) values.remove(entry.getKey());
                    else values.put(entry.getKey(), entry.getValue());
                }
            }
        };
    }
}
