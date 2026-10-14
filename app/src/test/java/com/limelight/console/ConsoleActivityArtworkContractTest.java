package com.limelight.console;

import com.limelight.SourceText;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ConsoleActivityArtworkContractTest {
    @Test public void sunshineLaunchAddsOnlyThePresentationArtworkIdentity() throws IOException {
        Path source = Paths.get("src/main/java/com/limelight/console/ConsoleActivity.java");
        String text = SourceText.read(source);
        String method = text.substring(text.indexOf("private void playSunshineApp"),
                text.indexOf("private void playPlayniteGame"));

        assertTrue(method.contains("uniquePlayniteGameIdForRunningApp(host, app)"));
        assertTrue(method.contains("PlayIntent.sunshineApp"));
        assertFalse(method.contains("PlayIntent.playniteGame"));
    }
}
