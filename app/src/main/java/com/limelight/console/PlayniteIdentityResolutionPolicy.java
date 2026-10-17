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

    /** What a switch of a retained stream carrying no game does with the host's current game. */
    public enum GamelessSwitch {
        /** Nothing to stop: the new game's attempt begins (its actor's host gates decide). */
        START,
        /** The host already runs exactly the new game for this same parent: the stream observes it. */
        ADOPT_RUNNING,
        /**
         * The host runs (or may run) a game: never stopped by a switch, and never taken over by
         * another actor (a child or another parent), not even when it is the new game.
         */
        MISMATCH
    }

    /**
     * A stream carrying no game holds no process of its own; the host reauthorizes it only over
     * an idle provider. A verified running {@code newGameId} is therefore never stopped and never
     * restarted: the same parent observes it, any other actor is refused before its allowance.
     */
    public static GamelessSwitch gamelessSwitch(String newGameId, boolean sameParentActor,
                                                String currentState, String currentGameId,
                                                boolean verifiedProcess) {
        String state = SessionSnapshot.normalize(currentState);
        if ("idle".equals(state)) return GamelessSwitch.START;
        if (!sameParentActor || !"running".equals(state) || !verifiedProcess
                || SessionSnapshot.normalize(newGameId).isEmpty()
                || !SessionSnapshot.normalize(newGameId).equals(
                SessionSnapshot.normalize(currentGameId))) {
            return GamelessSwitch.MISMATCH;
        }
        return GamelessSwitch.ADOPT_RUNNING;
    }
}
