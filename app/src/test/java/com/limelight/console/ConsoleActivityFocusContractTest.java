package com.limelight.console;

import com.limelight.SourceText;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertTrue;

public class ConsoleActivityFocusContractTest {
    @Test public void carouselDiffOnlyRestoresFocusOwnedByCarousel() throws IOException {
        Path source = Paths.get("src/main/java/com/limelight/console/ConsoleActivity.java");
        String text = SourceText.read(source);
        String method = text.substring(text.indexOf("private void applyPlayniteDiff"),
                text.indexOf("private List<PlayniteDashboardItem> preserveOrderDuringFocus"));

        assertTrue(method.contains("isDescendant(appRow, currentFocus)"));
        assertTrue(method.contains("Object focusTag = carouselFocused"));
    }
}
