package com.limelight.console;

import com.limelight.console.transition.LaunchTransitionType;

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
        this.hostId = hostId == null ? "" : hostId.trim();
        this.kind = Objects.requireNonNull(kind, "kind");
        this.sunshineAppId = sunshineAppId;
        this.appName = appName == null ? "" : appName.trim();
        this.hdrSupported = hdrSupported;
        this.playniteGameId = playniteGameId == null ? "" : playniteGameId.trim();
        this.quickLaunchId = quickLaunchId == null ? "" : quickLaunchId.trim();
        this.loadingArtworkGameId = loadingArtworkGameId == null
                ? "" : loadingArtworkGameId.trim();
        boolean targetMayBePrepared = kind == Kind.PLAYNITE_GAME && sunshineAppId == 0;
        if (this.hostId.isEmpty() || (sunshineAppId <= 0 && !targetMayBePrepared)
                || this.appName.isEmpty()) {
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
        return sunshineApp(hostId, appId, appName, hdrSupported, quickLaunchId, "");
    }

    static PlayIntent sunshineApp(String hostId, int appId, String appName,
                                  boolean hdrSupported, String quickLaunchId,
                                  String loadingArtworkGameId) {
        return new PlayIntent(hostId, Kind.SUNSHINE_APP, appId, appName,
                hdrSupported, "", quickLaunchId, loadingArtworkGameId);
    }

    static PlayIntent playniteGame(String hostId, int appId, String appName,
                                   boolean hdrSupported, String gameId,
                                   String loadingArtworkGameId) {
        return playniteGame(hostId, appId, appName, hdrSupported, gameId,
                loadingArtworkGameId, "");
    }

    static PlayIntent playniteGame(String hostId, int appId, String appName,
                                   boolean hdrSupported, String gameId,
                                   String loadingArtworkGameId, String streamSettingsKey) {
        return new PlayIntent(hostId, Kind.PLAYNITE_GAME, appId, appName,
                hdrSupported, gameId, streamSettingsKey, loadingArtworkGameId);
    }

    static PlayIntent playniteFullscreen(String hostId, int appId, String appName,
                                         boolean hdrSupported) {
        return new PlayIntent(hostId, Kind.PLAYNITE_FULLSCREEN, appId, appName,
                hdrSupported, "", "", "" );
    }

    String transitionGameId() {
        return kind == Kind.PLAYNITE_GAME ? playniteGameId : "";
    }

    LaunchTransitionType transitionType(LaunchTransitionType requestedType) {
        return kind == Kind.PLAYNITE_GAME && requestedType == LaunchTransitionType.GENERIC
                ? LaunchTransitionType.GAME_CONNECTION : requestedType;
    }

    boolean matches(SessionSnapshot snapshot) {
        return snapshot != null && matches(snapshot.hostId, snapshot.hostGameAppId,
                snapshot.playniteGameId);
    }

    boolean matches(String currentHostId, int currentAppId, String currentGameId) {
        if (!SessionSnapshot.normalize(hostId).equals(
                SessionSnapshot.normalize(currentHostId))) return false;
        switch (kind) {
            case PLAYNITE_GAME:
                return SessionSnapshot.normalize(playniteGameId).equals(
                        SessionSnapshot.normalize(currentGameId));
            case PLAYNITE_FULLSCREEN:
                return sunshineAppId == currentAppId
                        && SessionSnapshot.normalize(currentGameId).isEmpty();
            default:
                return sunshineAppId == currentAppId;
        }
    }
}
