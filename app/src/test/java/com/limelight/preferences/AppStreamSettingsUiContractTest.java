package com.limelight.preferences;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class AppStreamSettingsUiContractTest {
    @Test public void resolutionAndFpsUseStandardListsWithCustomEscapeHatches()
            throws IOException {
        String preferences = source("src/main/res/xml/app_preferences.xml",
                "app/src/main/res/xml/app_preferences.xml");
        String activity = source(
                "src/main/java/com/limelight/preferences/AppStreamSettings.java",
                "app/src/main/java/com/limelight/preferences/AppStreamSettings.java");

        assertTrue(preferences.contains("android:key=\"pref_app_resolution\""));
        assertTrue(preferences.contains("android:entries=\"@array/resolution_names\""));
        assertTrue(preferences.contains("android:key=\"text_app_fps\""));
        assertTrue(preferences.contains("android:entries=\"@array/fps_names\""));
        assertTrue(activity.contains("entries.add(getString(R.string.app_stream_custom_value))"));
        assertTrue(activity.contains("R.string.app_stream_saved_custom_value"));
        assertTrue(activity.contains("if (CUSTOM_VALUE.equals(value))"));
    }

    private static String source(String modulePath, String repositoryPath) throws IOException {
        Path source = Paths.get(modulePath);
        if (!Files.exists(source)) source = Paths.get(repositoryPath);
        return new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
    }
}
