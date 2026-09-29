package com.limelight.stream;

/** Transient correlation owned by the running foreground service: one session generation. */
final class BackgroundStreamCorrelation {
    private String streamSessionId = "";
    private long generation;
    private boolean parked;
    private boolean reconnectRequired;

    void establish(String sessionId, long transportGeneration) {
        streamSessionId = normalize(sessionId);
        generation = transportGeneration;
        parked = false;
        reconnectRequired = false;
    }

    boolean park(String sessionId, long transportGeneration) {
        if (!matches(sessionId, transportGeneration) || reconnectRequired) return false;
        parked = true;
        return true;
    }

    boolean resume(String sessionId, long transportGeneration) {
        if (!matches(sessionId, transportGeneration) || reconnectRequired) return false;
        parked = false;
        return true;
    }

    boolean transportLost(String sessionId, long transportGeneration) {
        if (!matches(sessionId, transportGeneration)) return false;
        parked = false;
        reconnectRequired = true;
        return true;
    }

    /** Ends only the exact generation; a stale runtime cannot stop a newer service. */
    boolean end(String sessionId, long transportGeneration) {
        if (!matches(sessionId, transportGeneration)) return false;
        clear();
        return true;
    }

    /** Ends a lost-transport notification for this session once it is no longer pending. */
    boolean dismiss(String sessionId) {
        if (!matchesSession(sessionId)) return false;
        clear();
        return true;
    }

    boolean matches(String sessionId, long transportGeneration) {
        return matchesSession(sessionId) && generation == transportGeneration;
    }

    boolean matchesSession(String sessionId) {
        String expected = normalize(sessionId);
        return !expected.isEmpty() && expected.equals(streamSessionId);
    }

    boolean parked() { return parked; }

    boolean reconnectRequired() { return reconnectRequired; }

    String streamSessionId() { return streamSessionId; }

    long generation() { return generation; }

    static String intentIdentity(String action, String sessionId, long transportGeneration) {
        return "moonwaker://background-stream/" + normalize(action) + "/"
                + normalize(sessionId) + "/" + transportGeneration;
    }

    private void clear() {
        streamSessionId = "";
        generation = 0L;
        parked = false;
        reconnectRequired = false;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
