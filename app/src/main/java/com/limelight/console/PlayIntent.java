package com.limelight.console;

import java.util.Objects;

/** Immutable destination selected by the user. */
final class PlayIntent {
    enum Kind { SUNSHINE_APP, PLAYNITE_GAME, PLAYNITE_FULLSCREEN }

    final String hostId;
    final Kind kind;
    final int sunshineAppId;
    final String appName;
    final boolean hdrSupported;
    final String playniteGameId;
    final String quickLaunchId;
    final String loadingArtworkGameId;

    private PlayIntent(String hostId, Kind kind, int sunshineAppId, String appName,
                       boolean hdrSupported, String playniteGameId,
                       String quickLaunchId, String loadingArtworkGameId) {
        this.hostId = SessionSnapshot.normalize(hostId);
        this.kind = Objects.requireNonNull(kind, "kind");
        this.sunshineAppId = sunshineAppId;
        this.appName = appName == null ? "" : appName.trim();
        this.hdrSupported = hdrSupported;
        this.playniteGameId = SessionSnapshot.normalize(playniteGameId);
        this.quickLaunchId = quickLaunchId == null ? "" : quickLaunchId.trim();
        this.loadingArtworkGameId = SessionSnapshot.normalize(loadingArtworkGameId);
        if (this.hostId.isEmpty() || sunshineAppId <= 0 || this.appName.isEmpty()) {
            throw new IllegalArgumentException("Host, Sunshine app ID, and app name are required");
        }
        if (kind == Kind.PLAYNITE_GAME && this.playniteGameId.isEmpty()) {
            throw new IllegalArgumentException("Playnite game ID is required");
        }
        if (kind != Kind.PLAYNITE_GAME && !this.playniteGameId.isEmpty()) {
            throw new IllegalArgumentException("Only Playnite game targets have a game ID");
        }
    }

    static PlayIntent sunshineApp(String hostId, int appId, String appName,
                                  boolean hdrSupported, String quickLaunchId) {
        return new PlayIntent(hostId, Kind.SUNSHINE_APP, appId, appName,
                hdrSupported, "", quickLaunchId, "");
    }

    static PlayIntent playniteGame(String hostId, int appId, String appName,
                                   boolean hdrSupported, String gameId,
                                   String loadingArtworkGameId) {
        return new PlayIntent(hostId, Kind.PLAYNITE_GAME, appId, appName,
                hdrSupported, gameId, "", loadingArtworkGameId);
    }

    static PlayIntent playniteFullscreen(String hostId, int appId, String appName,
                                         boolean hdrSupported) {
        return new PlayIntent(hostId, Kind.PLAYNITE_FULLSCREEN, appId, appName,
                hdrSupported, "", "", "");
    }

    boolean matches(SessionSnapshot snapshot) {
        return snapshot != null && matches(snapshot.hostId, snapshot.hostGameAppId,
                snapshot.playniteGameId);
    }

    boolean matches(String currentHostId, int currentAppId, String currentGameId) {
        if (!hostId.equals(SessionSnapshot.normalize(currentHostId))) return false;
        switch (kind) {
            case PLAYNITE_GAME:
                return playniteGameId.equals(SessionSnapshot.normalize(currentGameId));
            case PLAYNITE_FULLSCREEN:
                return sunshineAppId == currentAppId
                        && SessionSnapshot.normalize(currentGameId).isEmpty();
            default:
                return sunshineAppId == currentAppId;
        }
    }
}
