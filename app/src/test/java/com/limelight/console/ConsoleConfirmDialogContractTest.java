package com.limelight.console;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertTrue;

public class ConsoleConfirmDialogContractTest {
    @Test public void delayedOverloadLocksActionsThenFocusesSafeChoice() throws IOException {
        String source = source();

        assertTrue(source.contains("cancel.setEnabled(!delayed)"));
        assertTrue(source.contains("confirm.setEnabled(!delayed)"));
        assertTrue(source.contains("cancel.setAlpha(delayed ? .45f : 1f)"));
        assertTrue(source.contains("isActionKey(keyCode) && !actionsEnabled[0]"));
        assertTrue(source.contains("blockedKeys.contains(keyCode)"));
        assertTrue(source.contains("card.postDelayed("));
        assertTrue(source.contains("actionsEnabled[0] = true"));
        assertTrue(source.contains("cancel.requestFocus()"));
        assertTrue(source.contains("dialog.setOnDismissListener"));
    }

    @Test public void existingOverloadKeepsImmediateConfirmFocus() throws IOException {
        String source = source();

        assertTrue(source.contains("0L, false, null"));
        assertTrue(source.contains("else {\n            confirm.requestFocus();"));
    }

    private static String source() throws IOException {
        Path path = Paths.get("src/main/java/com/limelight/console/ConsoleConfirmDialog.java");
        if (!Files.exists(path)) path = Paths.get("app").resolve(path);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
                .replace("\r\n", "\n");
    }
}
