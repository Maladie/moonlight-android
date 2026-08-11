package com.limelight.console;

/** Resolves the action shown by a Playnite tile from one consistent session snapshot. */
final class PlayniteSessionPresentation {
    enum State {
        READY,
        RESUME_ACTIVE,
        RESUME_SUSPENDED
    }

    static State resolve(boolean consoleUi, boolean recentlyEnded,
                         String itemGameId, Integer itemSunshineAppId,
                         String storedGameId, int storedSunshineAppId,
                         long storedResumedAt, int runningGameId,
                         String liveGameId) {
        if (!consoleUi || recentlyEnded) return State.READY;

        boolean hasStoredGameId = storedGameId != null && !storedGameId.isEmpty();
        boolean storedMatches = hasStoredGameId
                ? storedGameId.equalsIgnoreCase(itemGameId)
                : storedSunshineAppId != 0 && itemSunshineAppId != null
                && itemSunshineAppId == storedSunshineAppId;
        if (storedMatches && storedResumedAt == 0L) {
            return State.RESUME_SUSPENDED;
        }
        if (storedMatches && storedResumedAt > 0L && runningGameId != 0
                && runningGameId == storedSunshineAppId) {
            return State.RESUME_ACTIVE;
        }
        if (runningGameId != 0 && liveGameId != null
                && liveGameId.equalsIgnoreCase(itemGameId)) {
            return State.RESUME_ACTIVE;
        }
        return State.READY;
    }

    private PlayniteSessionPresentation() { }
}
