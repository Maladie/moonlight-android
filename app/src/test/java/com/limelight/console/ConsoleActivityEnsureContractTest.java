package com.limelight.console;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ConsoleActivityEnsureContractTest {
    @Test public void successfulEnsurePollsBeforeAnyRenderOfTheOldAppList() throws IOException {
        Path source = Paths.get("src/main/java/com/limelight/console/ConsoleActivity.java");
        if (!Files.exists(source)) {
            source = Paths.get("app/src/main/java/com/limelight/console/ConsoleActivity.java");
        }
        String method = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
        method = method.substring(method.indexOf("private void ensureVibepolloAfterInstall"),
                method.indexOf("private static String playniteInstallNotificationKey"));

        assertFalse(method.contains("setGameTarget("));
        assertTrue(method.contains("if (ensured != null) {\n"
                + "                    if (appListPoller != null) appListPoller.pollNow();\n"
                + "                    return;\n"
                + "                }"));
    }
}
