package com.limelight.console;

/** Immutable session truth shared by host and game-tile presentation. */
final class ActiveSessionSnapshot {
    final String hostId;
    final int runningAppId;
    final String playniteGameId;
    final boolean retainedTransport;

    ActiveSessionSnapshot(String hostId, int runningAppId, String playniteGameId,
                          boolean retainedTransport) {
        this.hostId = normalize(hostId);
        this.runningAppId = runningAppId;
        this.playniteGameId = normalize(playniteGameId);
        this.retainedTransport = retainedTransport;
    }

    boolean isActive() {
        return runningAppId != 0;
    }

    boolean matches(String itemGameId, Integer itemAppId) {
        if (!isActive()) return false;
        if (!playniteGameId.isEmpty()) {
            return playniteGameId.equalsIgnoreCase(normalize(itemGameId));
        }
        return itemAppId != null && itemAppId == runningAppId;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
