package com.limelight.console;

import android.content.SharedPreferences;

import com.limelight.console.transition.LaunchTransitionState;

/** The local "recently played" record: its keys, its writes and the rule for a played game. */
public final class PlayHistory {
    private PlayHistory() {}

    static String appKey(String hostId, int appId) {
        return "played_at.app." + hostId + "." + appId;
    }

    static String gameKey(String hostId, String gameId) {
        return "played_at.game." + hostId + "." + gameId;
    }

    /**
     * A game was played once the host confirmed its window ready, or the user revealed its
     * stream by hand, on a stream the user asked for. An offered retry, a refused start or a
     * merely accepted start request is not that.
     */
    public static boolean gamePlayed(LaunchTransitionState state, boolean manuallyRevealed,
                                     String gameId, boolean hiddenWarmUp) {
        return (state == LaunchTransitionState.GAME_READY || manuallyRevealed)
                && gameId != null && !gameId.isEmpty() && !hiddenWarmUp;
    }

    /** One write per transition: a played game not yet recorded for {@code transitionId}. */
    public static boolean recordsNow(String recordedTransitionId, String transitionId,
                                     boolean played) {
        return played && !transitionId.equals(recordedTransitionId);
    }

    /** A launched app or desktop target. A game is recorded by {@link #recordGame} only. */
    static void recordApp(SharedPreferences preferences, String hostId, int appId,
                          long playedAt) {
        preferences.edit().putLong(appKey(hostId, appId), playedAt).apply();
    }

    /** The game and the stream app that carries it, once the game counts as played. */
    public static void recordGame(SharedPreferences preferences, String hostId, int appId,
                                  String gameId, long playedAt) {
        preferences.edit().putLong(appKey(hostId, appId), playedAt)
                .putLong(gameKey(hostId, gameId), playedAt).apply();
    }
}
