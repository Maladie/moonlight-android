package com.limelight.console;

public final class PlayniteIdentityResolutionPolicy {
    enum Action {
        REQUEST,
        CLEAR,
        PRESERVE
    }

    private PlayniteIdentityResolutionPolicy() {
    }

    static Action decide(boolean online, boolean paired, SessionSnapshot.State state) {
        if (state == SessionSnapshot.State.TERMINATING) return Action.CLEAR;
        if (online && paired) return Action.REQUEST;
        if (state == SessionSnapshot.State.NONE) return Action.CLEAR;
        return Action.PRESERVE;
    }

    static boolean acceptsResponse(String expectedHost, int expectedGeneration,
                                   int expectedAppId, String actualHost,
                                   int currentGeneration, int actualAppId,
                                   boolean online, boolean paired) {
        return online && paired && expectedGeneration == currentGeneration
                && expectedAppId == actualAppId
                && SessionSnapshot.normalize(expectedHost).equals(
                        SessionSnapshot.normalize(actualHost));
    }

    static boolean isFreshObservation(int expectedAppId, int observedAppId,
                                      long observedAt, long now, long ttl) {
        return expectedAppId == observedAppId && observedAt > 0L
                && now >= observedAt && now - observedAt <= ttl;
    }

    public static String verifiedStopTarget(String expectedGameId, String currentState,
                                            String currentGameId) {
        String state = SessionSnapshot.normalize(currentState);
        String expected = SessionSnapshot.normalize(expectedGameId);
        String current = currentGameId == null ? "" : currentGameId.trim();
        String comparableCurrent = SessionSnapshot.normalize(current);
        if ("idle".equals(state)) return "";
        if (!("starting".equals(state) || "running".equals(state)
                || "stopping".equals(state))
                || !HostGatewayClient.isPlayniteId(current)
                || (!expected.isEmpty() && !expected.equals(comparableCurrent))) {
            return null;
        }
        return current;
    }

    static boolean allowsPendingReconnect(String bridgeState, String pendingGameId) {
        return !("idle".equals(SessionSnapshot.normalize(bridgeState))
                && !SessionSnapshot.normalize(pendingGameId).isEmpty());
    }
}
