package com.limelight.console;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class ConsoleHostSelectionControllerTest {
    @Test public void rememberedAppProducesFocusIndexWithoutRenderedViews() {
        ConsoleHostSelectionController.Selection selection =
                ConsoleHostSelectionController.Selection.create(host(),
                        Arrays.asList(app(2), app(8), app(12)), 8);

        assertEquals(1, selection.focusAppIndex);
    }

    @Test public void missingRememberedAppFallsBackToFirst() {
        ConsoleHostSelectionController.Selection selection =
                ConsoleHostSelectionController.Selection.create(host(),
                        Arrays.asList(app(2), app(8)), 99);

        assertEquals(0, selection.focusAppIndex);
    }

    @Test public void selectionDefensivelyCopiesAppRow() {
        List<ConsoleDataRepository.App> apps = new ArrayList<>();
        apps.add(app(2));
        ConsoleHostSelectionController.Selection selection =
                ConsoleHostSelectionController.Selection.create(host(), apps, -1);
        apps.clear();

        assertEquals(1, selection.apps.size());
        assertEquals(-1, ConsoleHostSelectionController.Selection.create(
                host(), Collections.emptyList(), -1).focusAppIndex);
    }

    private static ConsoleDataRepository.Host host() {
        return new ConsoleDataRepository.Host("host-a", "Host", null);
    }

    private static ConsoleDataRepository.App app(int id) {
        return new ConsoleDataRepository.App(id, "App " + id, null);
    }
}
