package com.limelight.console;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ConsoleLibraryTransitionCoordinatorTest {
    @Test
    public void staleSelectionStagesCannotReplaceNewSelection() {
        QueuedScheduler scheduler = new QueuedScheduler();
        ConsoleLibraryTransitionCoordinator coordinator =
                new ConsoleLibraryTransitionCoordinator(scheduler, false);
        List<String> events = new ArrayList<>();

        coordinator.beginSelection("first", () -> events.add("first-art"),
                () -> events.add("first-meta"), () -> events.add("first-description"));
        coordinator.beginSelection("second", () -> events.add("second-art"),
                () -> events.add("second-meta"), () -> events.add("second-description"));
        scheduler.runAll();

        assertEquals(Arrays.asList("first-art", "second-art", "second-meta", "second-description"),
                events);
    }

    @Test
    public void reducedMotionKeepsOrderWithoutVisualDelay() {
        QueuedScheduler scheduler = new QueuedScheduler();
        ConsoleLibraryTransitionCoordinator coordinator =
                new ConsoleLibraryTransitionCoordinator(scheduler, true);
        List<String> events = new ArrayList<>();

        coordinator.beginSelection("game", () -> events.add("art"),
                () -> events.add("meta"), () -> events.add("description"));
        scheduler.runAll();

        assertEquals(Arrays.asList("art", "meta", "description"), events);
        assertEquals(Arrays.asList(0L, 0L), scheduler.delays);
    }

    @Test
    public void duplicateFocusDoesNotReplayPresentation() {
        QueuedScheduler scheduler = new QueuedScheduler();
        ConsoleLibraryTransitionCoordinator coordinator =
                new ConsoleLibraryTransitionCoordinator(scheduler, false);
        List<String> events = new ArrayList<>();

        coordinator.beginSelection("game", () -> events.add("art"),
                () -> events.add("meta"), () -> events.add("description"));
        coordinator.beginSelection("game", () -> events.add("duplicate-art"),
                () -> events.add("duplicate-meta"), () -> events.add("duplicate-description"));
        scheduler.runAll();

        assertEquals(Arrays.asList("art", "meta", "description"), events);
    }

    private static final class QueuedScheduler
            implements ConsoleLibraryTransitionCoordinator.Scheduler {
        final List<Runnable> tasks = new ArrayList<>();
        final List<Long> delays = new ArrayList<>();

        @Override public void postDelayed(Runnable action, long delayMs) {
            tasks.add(action);
            delays.add(delayMs);
        }

        void runAll() {
            List<Runnable> queued = new ArrayList<>(tasks);
            tasks.clear();
            for (Runnable task : queued) task.run();
        }
    }
}
