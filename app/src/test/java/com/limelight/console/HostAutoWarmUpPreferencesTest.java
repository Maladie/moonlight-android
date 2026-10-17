package com.limelight.console;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;

import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

public class HostAutoWarmUpPreferencesTest {
    @Test public void defaultsToDisabledAndIsIsolatedByHostUuid() {
        Map<String, Boolean> values = new HashMap<>();
        SharedPreferences preferences = preferences(values);

        assertFalse(HostAutoWarmUpPreferences.isEnabled(preferences, "host-a"));

        HostAutoWarmUpPreferences.setEnabled(preferences, "HOST-A", true);

        assertTrue(HostAutoWarmUpPreferences.isEnabled(preferences, "host-a"));
        assertFalse(HostAutoWarmUpPreferences.isEnabled(preferences, "host-b"));
    }

    private static SharedPreferences preferences(Map<String, Boolean> values) {
        SharedPreferences.Editor editor = (SharedPreferences.Editor) Proxy.newProxyInstance(
                SharedPreferences.Editor.class.getClassLoader(),
                new Class<?>[]{SharedPreferences.Editor.class}, (proxy, method, args) -> {
                    if ("putBoolean".equals(method.getName())) {
                        values.put((String) args[0], (Boolean) args[1]);
                        return proxy;
                    }
                    return null;
                });
        return (SharedPreferences) Proxy.newProxyInstance(
                SharedPreferences.class.getClassLoader(),
                new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
                    if ("getBoolean".equals(method.getName())) {
                        return values.getOrDefault((String) args[0], (Boolean) args[1]);
                    }
                    if ("edit".equals(method.getName())) return editor;
                    return null;
                });
    }
}
