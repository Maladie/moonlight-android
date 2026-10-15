package com.limelight.stream;

import com.limelight.SourceText;
import android.content.SharedPreferences;

import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BackgroundStreamPreferencesTest {
    @Test public void absentPreferenceDefaultsToThirtyMinutesWithoutBeingPersisted() {
        Map<String, Integer> values = new HashMap<>();

        assertEquals(30, BackgroundStreamPreferences.readMinutes(preferences(values)));
        assertFalse(values.containsKey(BackgroundStreamPreferences.KEY_RETENTION_MINUTES));
    }

    @Test public void upgradePreservesEveryExplicitValueIncludingOff() {
        for (int value : new int[]{0, 5, 10, 30, 60, BackgroundStreamPreferences.NEVER}) {
            Map<String, Integer> values = new HashMap<>();
            values.put(BackgroundStreamPreferences.KEY_RETENTION_MINUTES, value);

            assertEquals(value, BackgroundStreamPreferences.readMinutes(preferences(values)));
        }
    }

    @Test public void policyOwnerWritesExplicitValues() {
        Map<String, Integer> values = new HashMap<>();
        SharedPreferences preferences = preferences(values);

        BackgroundStreamPreferences.writeMinutes(preferences, 0);
        assertEquals(0, BackgroundStreamPreferences.readMinutes(preferences));
        BackgroundStreamPreferences.writeMinutes(preferences, BackgroundStreamPreferences.NEVER);
        assertEquals(BackgroundStreamPreferences.NEVER,
                BackgroundStreamPreferences.readMinutes(preferences));
    }

    @Test public void productionCallersUseTheSinglePreferenceOwner() throws IOException {
        String service = source("src/main/java/com/limelight/stream/BackgroundStreamService.java");
        String game = source("src/main/java/com/limelight/Game.java");
        String console = source("src/main/java/com/limelight/console/ConsoleActivity.java");

        assertTrue(service.contains("BackgroundStreamPreferences.DEFAULT_RETENTION_MINUTES"));
        String runtime = source("src/main/java/com/limelight/stream/AndroidStreamRuntime.java");
        assertEquals(1, occurrences(service, "BackgroundStreamPreferences.readMinutes(this)"));
        assertTrue(runtime.contains("return BackgroundStreamPreferences.readMinutes(app);"));
        assertTrue(game.contains("BackgroundStreamPreferences.readMinutes(this)"));
        assertFalse(game.contains("BackgroundStreamService.park("));
        assertTrue(console.contains("BackgroundStreamPreferences.writeMinutes(this, minutes)"));
        assertFalse(console.contains("putInt(BackgroundStreamPreferences.KEY_RETENTION_MINUTES"));
    }

    @Test public void homeAndHostPickerRetentionRemainUntimed() throws IOException {
        String game = source("src/main/java/com/limelight/Game.java");
        String openHome = between(game, "private boolean openConsoleHome()",
                "private void openPreparingConsoleHome()");
        String retained = source("src/main/java/com/limelight/session/SessionModel.java");
        String console = source("src/main/java/com/limelight/console/ConsoleActivity.java");
        String hostPicker = between(console, "private void showHostSelection(String focusUuid)",
                "private void renderHostSelection()");

        assertTrue(openHome.contains("new SessionEvent.EnterHome("));
        assertFalse(openHome.contains("BackgroundStreamPreferences"));
        assertFalse(openHome.contains("BackgroundStreamService"));
        assertFalse(retained.contains("BackgroundStreamPreferences"));
        assertFalse(retained.contains("timeout"));
        assertFalse(hostPicker.contains("BackgroundStreamPreferences"));
        assertFalse(hostPicker.contains("postDelayed"));
    }

    private static SharedPreferences preferences(Map<String, Integer> values) {
        SharedPreferences.Editor editor = (SharedPreferences.Editor) Proxy.newProxyInstance(
                SharedPreferences.Editor.class.getClassLoader(),
                new Class<?>[]{SharedPreferences.Editor.class}, (proxy, method, args) -> {
                    if ("putInt".equals(method.getName())) {
                        values.put((String) args[0], (Integer) args[1]);
                        return proxy;
                    }
                    if ("apply".equals(method.getName())) return null;
                    return proxy;
                });
        return (SharedPreferences) Proxy.newProxyInstance(
                SharedPreferences.class.getClassLoader(),
                new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
                    if ("contains".equals(method.getName())) return values.containsKey(args[0]);
                    if ("getInt".equals(method.getName())) {
                        return values.getOrDefault((String) args[0], (Integer) args[1]);
                    }
                    if ("edit".equals(method.getName())) return editor;
                    return null;
                });
    }

    private static String source(String relative) throws IOException {
        Path path = Paths.get(relative);
        return SourceText.read(path);
    }

    private static String between(String source, String start, String end) {
        return source.substring(source.indexOf(start), source.indexOf(end, source.indexOf(start)));
    }

    private static int occurrences(String text, String needle) {
        return (text.length() - text.replace(needle, "").length()) / needle.length();
    }
}
