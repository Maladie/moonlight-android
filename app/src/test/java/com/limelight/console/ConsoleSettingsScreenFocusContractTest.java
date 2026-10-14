package com.limelight.console;

import com.limelight.SourceText;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Removing the focused row while a screen is rebuilt hands the focus to the first category;
 * that stray focus must not select it, or Back from Settings › Streaming › Stream lands on
 * the first category instead of the row that opened the nested screen.
 */
public class ConsoleSettingsScreenFocusContractTest {
    private final ConsoleSettingsScreen.Rebuild rebuild = new ConsoleSettingsScreen.Rebuild();
    private int selected = 2;

    /** The category row's focus listener. */
    private void categoryFocused(int position) {
        if (!rebuild.active()) selected = position;
    }

    @Test public void strayFocusDuringRebuildKeepsTheSelection() {
        rebuild.run(() -> categoryFocused(0));
        assertEquals(2, selected);
        categoryFocused(1);
        assertEquals(1, selected);
    }

    @Test public void nestedRenderKeepsTheOuterRebuildActive() {
        rebuild.run(() -> {
            rebuild.run(() -> { });
            assertTrue(rebuild.active());
            categoryFocused(0);
        });
        assertFalse(rebuild.active());
        assertEquals(2, selected);
    }

    @Test public void failedRebuildStillEnds() {
        try {
            rebuild.run(() -> { throw new IllegalStateException("render failed"); });
            fail("Expected the render failure");
        } catch (IllegalStateException expected) {
            assertFalse(rebuild.active());
        }
    }

    @Test public void screenRoutesFocusAndRowReplacementThroughTheRebuild() throws IOException {
        Path source = Paths.get("src/main/java/com/limelight/console/ConsoleSettingsScreen.java");
        String text = SourceText.read(source);
        assertTrue(text.contains("if (focused && !rebuild.active()) select(position);"));
        assertTrue(text.contains("rebuild.run(() -> replaceCategories(next, category));"));
        assertTrue(text.contains("rebuild.run(() -> panel.render(content,"));
    }
}
