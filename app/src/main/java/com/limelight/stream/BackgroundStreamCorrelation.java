package com.limelight.stream;

/** Transient correlation owned by the running background service. */
final class BackgroundStreamCorrelation {
    private String streamSessionId = "";
    private boolean reconnectRequired;

    void park(String sessionId) {
        streamSessionId = normalize(sessionId);
        reconnectRequired = false;
    }

    boolean resume(String sessionId) {
        return finishIfMatches(sessionId);
    }

    boolean end(String sessionId) {
        return finishIfMatches(sessionId);
    }

    boolean expire(String sessionId) {
        return finishIfMatches(sessionId);
    }

    boolean transportLost(String sessionId) {
        if (!matches(sessionId)) return false;
        reconnectRequired = true;
        return true;
    }

    boolean matches(String sessionId) {
        String expected = normalize(sessionId);
        return !expected.isEmpty() && expected.equals(streamSessionId);
    }

    boolean reconnectRequired() {
        return reconnectRequired;
    }

    String streamSessionId() {
        return streamSessionId;
    }

    static String intentIdentity(String action, String sessionId) {
        return "moonwaker://background-stream/" + normalize(action) + "/"
                + normalize(sessionId);
    }

    private boolean finishIfMatches(String sessionId) {
        if (!matches(sessionId)) return false;
        streamSessionId = "";
        reconnectRequired = false;
        return true;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
