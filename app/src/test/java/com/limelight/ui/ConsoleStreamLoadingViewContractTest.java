package com.limelight.ui;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ConsoleStreamLoadingViewContractTest {
    @Test public void launcherFailureOffersOnlyRevealAndBack() throws IOException {
        Path source = Paths.get("src/main/java/com/limelight/ui/ConsoleStreamLoadingView.java");
        if (!Files.exists(source)) {
            source = Paths.get("app/src/main/java/com/limelight/ui/ConsoleStreamLoadingView.java");
        }
        String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
        String method = text.substring(text.indexOf("public void showLauncherInteraction"),
                text.indexOf("public void setManualRevealAvailable"));

        assertTrue(method.contains("retryView.setVisibility(GONE)"));
        assertTrue(method.contains("showAnywayView.setVisibility(allowReveal ? VISIBLE : GONE)"));
        assertTrue(method.contains("cancelView.setText(R.string.transition_back)"));
        assertFalse(method.contains("transition_retry"));
    }
}
