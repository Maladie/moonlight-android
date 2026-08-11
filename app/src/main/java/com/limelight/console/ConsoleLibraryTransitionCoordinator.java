package com.limelight.console;

import android.os.Handler;

/** Coordinates library presentation so stale delayed stages cannot update a newer selection. */
final class ConsoleLibraryTransitionCoordinator {
    private static final long METADATA_DELAY_MS = 90L;
    private static final long DESCRIPTION_DELAY_MS = 155L;

    interface Scheduler {
        void postDelayed(Runnable action, long delayMs);
    }

    private final Scheduler scheduler;
    private int generation;
    private boolean reducedMotion;
    private String selectedGameId = "";
    private boolean selectionPresentationActive;

    ConsoleLibraryTransitionCoordinator(Handler handler, boolean reducedMotion) {
        this((action, delayMs) -> handler.postDelayed(action, delayMs), reducedMotion);
    }

    ConsoleLibraryTransitionCoordinator(Scheduler scheduler, boolean reducedMotion) {
        this.scheduler = scheduler;
        this.reducedMotion = reducedMotion;
    }

    void setReducedMotion(boolean value) {
        reducedMotion = value;
        generation++;
        selectionPresentationActive = false;
    }

    int beginSelection(String gameId, Runnable artwork,
                       Runnable metadata, Runnable description) {
        String normalizedGameId = gameId == null ? "" : gameId;
        if (selectionPresentationActive && selectedGameId.equals(normalizedGameId)) {
            return generation;
        }
        int token = ++generation;
        selectedGameId = normalizedGameId;
        selectionPresentationActive = true;
        artwork.run();
        post(token, reducedMotion ? 0L : METADATA_DELAY_MS, metadata);
        post(token, reducedMotion ? 0L : DESCRIPTION_DELAY_MS, description);
        return token;
    }

    int beginTransition(String gameId) {
        selectedGameId = gameId == null ? "" : gameId;
        selectionPresentationActive = false;
        return ++generation;
    }

    void post(int token, long delayMs, Runnable action) {
        scheduler.postDelayed(() -> {
            if (token == generation) action.run();
        }, reducedMotion ? 0L : Math.max(0L, delayMs));
    }

    boolean isCurrent(int token, String gameId) {
        return token == generation && selectedGameId.equals(gameId == null ? "" : gameId);
    }

    void cancel() {
        generation++;
        selectedGameId = "";
        selectionPresentationActive = false;
    }
}
