package com.limelight.console;

import com.limelight.console.transition.LaunchTransitionType;
import com.limelight.gateway.GatewayConnection;

import java.util.Objects;

/** Immutable session destination. */
final class PlayIntent {
    enum Kind { SUNSHINE_APP, PLAYNITE_GAME, PLAYNITE_FULLSCREEN, AUTO_WARM_UP }

    final HostProfileKey profileKey;
    final String hostId;
    final String profileId;
    final Kind kind;
    final int sunshineAppId;
    final String appName;
    final boolean hdrSupported;
    final String playniteGameId;
    final String quickLaunchId;
    final String loadingArtworkGameId;
    final boolean requiresConnector;
    final boolean neutralStream;
    final boolean startBeforeStream;
    final String calibrationAppKey;
    final int runtimeWidth;
    final int runtimeHeight;
    final int runtimeFps;
    final int runtimeBitrateKbps;

    private PlayIntent(String hostId, String profileId, Kind kind, int sunshineAppId, String appName,
                       boolean hdrSupported, String playniteGameId,
                       String quickLaunchId, String loadingArtworkGameId,
                       boolean requiresConnector, boolean neutralStream,
                       boolean startBeforeStream) {
        this(hostId, profileId, kind, sunshineAppId, appName, hdrSupported, playniteGameId,
                quickLaunchId, loadingArtworkGameId, requiresConnector, neutralStream,
                startBeforeStream, "", 0, 0, 0, 0);
    }

    private PlayIntent(String hostId, String profileId, Kind kind, int sunshineAppId, String appName,
                       boolean hdrSupported, String playniteGameId,
                       String quickLaunchId, String loadingArtworkGameId,
                       boolean requiresConnector, boolean neutralStream,
                       boolean startBeforeStream, String calibrationAppKey,
                       int runtimeWidth, int runtimeHeight, int runtimeFps,
                       int runtimeBitrateKbps) {
        this.profileKey = new HostProfileKey(hostId, profileId);
        this.hostId = profileKey.hostId;
        this.profileId = profileKey.profileId;
        this.kind = Objects.requireNonNull(kind, "kind");
        this.sunshineAppId = sunshineAppId;
        this.appName = appName == null ? "" : appName.trim();
        this.hdrSupported = hdrSupported;
        this.playniteGameId = playniteGameId == null ? "" : playniteGameId.trim();
        this.quickLaunchId = quickLaunchId == null ? "" : quickLaunchId.trim();
        this.loadingArtworkGameId = loadingArtworkGameId == null
                ? "" : loadingArtworkGameId.trim();
        this.requiresConnector = requiresConnector;
        this.neutralStream = neutralStream;
        this.startBeforeStream = startBeforeStream;
        this.calibrationAppKey = calibrationAppKey == null ? "" : calibrationAppKey.trim();
        this.runtimeWidth = runtimeWidth;
        this.runtimeHeight = runtimeHeight;
        this.runtimeFps = runtimeFps;
        this.runtimeBitrateKbps = runtimeBitrateKbps;
        boolean targetMayBePrepared = (kind == Kind.PLAYNITE_GAME
                || kind == Kind.AUTO_WARM_UP) && sunshineAppId == 0;
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
        if (!this.calibrationAppKey.isEmpty() && (runtimeWidth <= 0 || runtimeHeight <= 0
                || runtimeFps <= 0 || runtimeBitrateKbps < 500)) {
            throw new IllegalArgumentException("Calibration runtime settings are invalid");
        }
    }

    static PlayIntent sunshineApp(String hostId, int appId, String appName,
                                  boolean hdrSupported, String quickLaunchId) {
        return sunshineApp(hostId, GatewayConnection.DEFAULT_PROFILE_ID, appId, appName,
                hdrSupported, quickLaunchId, "");
    }

    static PlayIntent sunshineApp(String hostId, String profileId, int appId,
                                  String appName, boolean hdrSupported,
                                  String quickLaunchId) {
        return sunshineApp(hostId, profileId, appId, appName, hdrSupported,
                quickLaunchId, "");
    }

    static PlayIntent sunshineApp(String hostId, int appId, String appName,
                                  boolean hdrSupported, String quickLaunchId,
                                  String loadingArtworkGameId) {
        return sunshineApp(hostId, GatewayConnection.DEFAULT_PROFILE_ID, appId, appName,
                hdrSupported, quickLaunchId, loadingArtworkGameId);
    }

    static PlayIntent sunshineApp(String hostId, String profileId, int appId, String appName,
                                  boolean hdrSupported, String quickLaunchId,
                                  String loadingArtworkGameId) {
        return new PlayIntent(hostId, profileId, Kind.SUNSHINE_APP, appId, appName,
                hdrSupported, "", quickLaunchId, loadingArtworkGameId,
                false, false, false);
    }

    static PlayIntent playniteGame(String hostId, int appId, String appName,
                                   boolean hdrSupported, String gameId,
                                   String loadingArtworkGameId) {
        return playniteGame(hostId, GatewayConnection.DEFAULT_PROFILE_ID, appId, appName,
                hdrSupported, gameId,
                loadingArtworkGameId, "");
    }

    static PlayIntent playniteGame(String hostId, String profileId, int appId, String appName,
                                   boolean hdrSupported, String gameId,
                                   String loadingArtworkGameId) {
        return playniteGame(hostId, profileId, appId, appName, hdrSupported, gameId,
                loadingArtworkGameId, "");
    }

    static PlayIntent playniteGame(String hostId, int appId, String appName,
                                   boolean hdrSupported, String gameId,
                                   String loadingArtworkGameId,
                                   boolean requiresConnector, boolean neutralStream) {
        return playniteGame(hostId, GatewayConnection.DEFAULT_PROFILE_ID, appId, appName,
                hdrSupported, gameId,
                loadingArtworkGameId, "", requiresConnector, neutralStream);
    }

    static PlayIntent playniteGame(String hostId, String profileId, int appId, String appName,
                                   boolean hdrSupported, String gameId,
                                   String loadingArtworkGameId,
                                   boolean requiresConnector, boolean neutralStream) {
        return playniteGame(hostId, profileId, appId, appName, hdrSupported, gameId,
                loadingArtworkGameId, "", requiresConnector, neutralStream);
    }

    static PlayIntent playniteGame(String hostId, int appId, String appName,
                                   boolean hdrSupported, String gameId,
                                   String loadingArtworkGameId, String streamSettingsKey) {
        return playniteGame(hostId, GatewayConnection.DEFAULT_PROFILE_ID, appId, appName,
                hdrSupported, gameId,
                loadingArtworkGameId, streamSettingsKey, true, false);
    }

    static PlayIntent playniteGame(String hostId, String profileId, int appId, String appName,
                                   boolean hdrSupported, String gameId,
                                   String loadingArtworkGameId, String streamSettingsKey) {
        return playniteGame(hostId, profileId, appId, appName, hdrSupported, gameId,
                loadingArtworkGameId, streamSettingsKey, true, false);
    }

    static PlayIntent playniteGame(String hostId, int appId, String appName,
                                   boolean hdrSupported, String gameId,
                                   String loadingArtworkGameId, String streamSettingsKey,
                                   boolean requiresConnector, boolean neutralStream) {
        return playniteGame(hostId, GatewayConnection.DEFAULT_PROFILE_ID, appId, appName,
                hdrSupported, gameId,
                loadingArtworkGameId, streamSettingsKey, requiresConnector,
                neutralStream, false);
    }

    static PlayIntent playniteGame(String hostId, String profileId, int appId, String appName,
                                   boolean hdrSupported, String gameId,
                                   String loadingArtworkGameId, String streamSettingsKey,
                                   boolean requiresConnector, boolean neutralStream) {
        return playniteGame(hostId, profileId, appId, appName, hdrSupported, gameId,
                loadingArtworkGameId, streamSettingsKey, requiresConnector,
                neutralStream, false);
    }

    static PlayIntent playniteGame(String hostId, int appId, String appName,
                                   boolean hdrSupported, String gameId,
                                   String loadingArtworkGameId, String streamSettingsKey,
                                   boolean requiresConnector, boolean neutralStream,
                                   boolean startBeforeStream) {
        return playniteGame(hostId, GatewayConnection.DEFAULT_PROFILE_ID, appId, appName,
                hdrSupported, gameId, loadingArtworkGameId, streamSettingsKey,
                requiresConnector, neutralStream, startBeforeStream);
    }

    static PlayIntent playniteGame(String hostId, String profileId, int appId, String appName,
                                   boolean hdrSupported, String gameId,
                                   String loadingArtworkGameId, String streamSettingsKey,
                                   boolean requiresConnector, boolean neutralStream,
                                   boolean startBeforeStream) {
        return new PlayIntent(hostId, profileId, Kind.PLAYNITE_GAME, appId, appName,
                hdrSupported, gameId, streamSettingsKey, loadingArtworkGameId,
                requiresConnector, neutralStream, startBeforeStream);
    }

    static PlayIntent providerGame(String hostId, int appId, String appName,
                                   boolean hdrSupported, PlayniteLibraryGame game,
                                   String streamSettingsKey) {
        return providerGame(hostId, GatewayConnection.DEFAULT_PROFILE_ID, appId, appName,
                hdrSupported, game, streamSettingsKey);
    }

    static PlayIntent providerGame(String hostId, String profileId, int appId, String appName,
                                   boolean hdrSupported, PlayniteLibraryGame game,
                                   String streamSettingsKey) {
        if (game == null) throw new IllegalArgumentException("Game metadata is required");
        return playniteGame(hostId, profileId, appId, appName, hdrSupported,
                game.playniteGameId, game.playniteGameId, streamSettingsKey,
                game.requiresConnector, game.usesNeutralStream(), game.startBeforeStream);
    }

    static PlayIntent playniteFullscreen(String hostId, int appId, String appName,
                                         boolean hdrSupported) {
        return playniteFullscreen(hostId, GatewayConnection.DEFAULT_PROFILE_ID,
                appId, appName, hdrSupported);
    }

    static PlayIntent playniteFullscreen(String hostId, String profileId, int appId,
                                         String appName, boolean hdrSupported) {
        return new PlayIntent(hostId, profileId, Kind.PLAYNITE_FULLSCREEN, appId, appName,
                hdrSupported, "", "", "", false, false, false);
    }

    static PlayIntent autoWarmUp(String hostId) {
        return autoWarmUp(hostId, GatewayConnection.DEFAULT_PROFILE_ID);
    }

    static PlayIntent autoWarmUp(String hostId, String profileId) {
        return new PlayIntent(hostId, profileId, Kind.AUTO_WARM_UP, 0,
                PlayniteTargetResolver.MOONWAKER_STREAM_NAME,
                false, "", "", "", false, true, false);
    }

    PlayIntent withCalibration(String appKey, int width, int height, int fps,
                               int bitrateKbps) {
        if (appKey == null || appKey.trim().isEmpty()) {
            throw new IllegalArgumentException("Calibration app key is required");
        }
        return new PlayIntent(hostId, profileId, kind, sunshineAppId, appName, hdrSupported,
                playniteGameId, quickLaunchId, loadingArtworkGameId, requiresConnector,
                neutralStream, startBeforeStream, appKey, width, height, fps, bitrateKbps);
    }

    boolean isCalibration() {
        return !calibrationAppKey.isEmpty();
    }

    String transitionGameId() {
        return kind == Kind.PLAYNITE_GAME ? playniteGameId : "";
    }

    LaunchTransitionType transitionType(LaunchTransitionType requestedType) {
        return kind == Kind.PLAYNITE_GAME && requestedType == LaunchTransitionType.GENERIC
                ? LaunchTransitionType.GAME_CONNECTION : requestedType;
    }

    boolean matches(SessionSnapshot snapshot) {
        return snapshot != null && profileKey.equals(snapshot.profileKey)
                && matches(snapshot.hostId, snapshot.profileId, snapshot.hostGameAppId,
                snapshot.playniteGameId);
    }

    boolean matches(String currentHostId, int currentAppId, String currentGameId) {
        return matches(currentHostId, GatewayConnection.DEFAULT_PROFILE_ID,
                currentAppId, currentGameId);
    }

    boolean matches(String currentHostId, String currentProfileId,
                    int currentAppId, String currentGameId) {
        if (!SessionSnapshot.normalize(hostId).equals(
                SessionSnapshot.normalize(currentHostId))
                || !profileId.equals(GatewayConnection.normalizeProfileId(
                currentProfileId))) return false;
        switch (kind) {
            case PLAYNITE_GAME:
                return SessionSnapshot.normalize(playniteGameId).equals(
                        SessionSnapshot.normalize(currentGameId));
            case PLAYNITE_FULLSCREEN:
                return sunshineAppId == currentAppId
                        && SessionSnapshot.normalize(currentGameId).isEmpty();
            case AUTO_WARM_UP:
                return false;
            default:
                return sunshineAppId == currentAppId;
        }
    }
}
