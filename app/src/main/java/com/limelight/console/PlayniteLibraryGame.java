package com.limelight.console;

import java.util.Objects;

/** Immutable library metadata. It deliberately contains no streaming state. */
final class PlayniteLibraryGame {
    final String playniteGameId;
    final String name;
    final boolean installed;
    final boolean installing;
    final boolean hidden;
    final long playtimeSeconds;
    final String lastActivity;
    final long activityEpoch;
    final String coverKey;
    final String backgroundKey;
    final String description;
    final int playCount;
    final String source;
    final String provider;
    final String providerGameId;
    final String metadataPlayniteGameId;
    final String libraryKey;
    final String libraryName;
    final boolean canLaunch;
    final boolean canInstall;
    final boolean canUninstall;
    final String genres;
    final boolean installRequiresAttention;
    final String installAttentionReason;
    final String installWindowTitle;
    final String installLauncher;
    final String operationState;
    final int operationProgress;
    final boolean uninstalling;
    final String vibepolloState;

    PlayniteLibraryGame(String playniteGameId, String name, boolean installed,
                        boolean hidden, long playtimeSeconds, String lastActivity,
                        String coverKey, String backgroundKey, String source) {
        this(playniteGameId, name, installed, hidden, playtimeSeconds, lastActivity,
                coverKey, backgroundKey, "", 0, source);
    }

    PlayniteLibraryGame(String playniteGameId, String name, boolean installed,
                        boolean hidden, long playtimeSeconds, String lastActivity,
                        String coverKey, String backgroundKey, String description,
                        String source) {
        this(playniteGameId, name, installed, hidden, playtimeSeconds, lastActivity,
                coverKey, backgroundKey, description, 0, source);
    }

    PlayniteLibraryGame(String playniteGameId, String name, boolean installed,
                        boolean hidden, long playtimeSeconds, String lastActivity,
                        String coverKey, String backgroundKey, String description,
                        int playCount, String source) {
        this(playniteGameId, name, installed, false, hidden, playtimeSeconds, lastActivity,
                coverKey, backgroundKey, description, playCount, source);
    }

    PlayniteLibraryGame(String playniteGameId, String name, boolean installed,
                        boolean installing, boolean hidden, long playtimeSeconds,
                        String lastActivity, String coverKey, String backgroundKey,
                        String description, int playCount, String source) {
        this(playniteGameId, name, installed, installing, hidden, playtimeSeconds,
                lastActivity, coverKey, backgroundKey, description, playCount, source, "");
    }

    PlayniteLibraryGame(String playniteGameId, String name, boolean installed,
                        boolean installing, boolean hidden, long playtimeSeconds,
                        String lastActivity, String coverKey, String backgroundKey,
                        String description, int playCount, String source, String genres) {
        this(playniteGameId, name, installed, installing, hidden, playtimeSeconds,
                lastActivity, coverKey, backgroundKey, description, playCount, source,
                genres, false, "", "", "");
    }

    PlayniteLibraryGame(String playniteGameId, String name, boolean installed,
                        boolean installing, boolean hidden, long playtimeSeconds,
                        String lastActivity, String coverKey, String backgroundKey,
                        String description, int playCount, String source, String genres,
                        boolean installRequiresAttention, String installAttentionReason,
                        String installWindowTitle, String installLauncher) {
        this(playniteGameId, name, installed, installing, hidden, playtimeSeconds,
                lastActivity, coverKey, backgroundKey, description, playCount, source,
                genres, installRequiresAttention, installAttentionReason,
                installWindowTitle, installLauncher, "", -1, false);
    }

    PlayniteLibraryGame(String playniteGameId, String name, boolean installed,
                        boolean installing, boolean hidden, long playtimeSeconds,
                        String lastActivity, String coverKey, String backgroundKey,
                        String description, int playCount, String source, String genres,
                        boolean installRequiresAttention, String installAttentionReason,
                        String installWindowTitle, String installLauncher,
                        String operationState, int operationProgress, boolean uninstalling) {
        this(playniteGameId, name, installed, installing, hidden, playtimeSeconds,
                lastActivity, coverKey, backgroundKey, description, playCount, source,
                genres, installRequiresAttention, installAttentionReason, installWindowTitle,
                installLauncher, operationState, operationProgress, uninstalling, "");
    }

    PlayniteLibraryGame(String playniteGameId, String name, boolean installed,
                        boolean installing, boolean hidden, long playtimeSeconds,
                        String lastActivity, String coverKey, String backgroundKey,
                        String description, int playCount, String source, String genres,
                        boolean installRequiresAttention, String installAttentionReason,
                        String installWindowTitle, String installLauncher,
                        String operationState, int operationProgress, boolean uninstalling,
                        String vibepolloState) {
        this(playniteGameId, name, installed, installing, hidden, playtimeSeconds,
                lastActivity, coverKey, backgroundKey, description, playCount, source,
                genres, installRequiresAttention, installAttentionReason, installWindowTitle,
                installLauncher, operationState, operationProgress, uninstalling,
                vibepolloState, providerFrom(source), "",
                isGuid(playniteGameId) ? playniteGameId : "",
                sourceKey(source), text(source).isEmpty() ? "Playnite" : source,
                true, true, true);
    }

    PlayniteLibraryGame(String playniteGameId, String name, boolean installed,
                        boolean installing, boolean hidden, long playtimeSeconds,
                        String lastActivity, String coverKey, String backgroundKey,
                        String description, int playCount, String source, String genres,
                        boolean installRequiresAttention, String installAttentionReason,
                        String installWindowTitle, String installLauncher,
                        String operationState, int operationProgress, boolean uninstalling,
                        String vibepolloState, String provider, String providerGameId,
                        String metadataPlayniteGameId, String libraryKey, String libraryName,
                        boolean canLaunch, boolean canInstall, boolean canUninstall) {
        this.playniteGameId = playniteGameId;
        this.name = name;
        this.installed = installed;
        this.installing = installing;
        this.hidden = hidden;
        this.playtimeSeconds = Math.max(0L, playtimeSeconds);
        this.lastActivity = text(lastActivity);
        this.activityEpoch = PlayniteLibraryOrdering.activityEpoch(this.lastActivity);
        this.coverKey = text(coverKey);
        this.backgroundKey = text(backgroundKey);
        this.description = descriptionText(description);
        this.playCount = Math.max(0, playCount);
        this.source = text(source);
        this.provider = text(provider).toLowerCase(java.util.Locale.ROOT);
        this.providerGameId = text(providerGameId);
        this.metadataPlayniteGameId = text(metadataPlayniteGameId).toLowerCase(
                java.util.Locale.ROOT);
        this.libraryKey = text(libraryKey).toLowerCase(java.util.Locale.ROOT);
        this.libraryName = text(libraryName);
        this.canLaunch = canLaunch;
        this.canInstall = canInstall;
        this.canUninstall = canUninstall;
        this.genres = text(genres);
        this.installRequiresAttention = installRequiresAttention;
        this.installAttentionReason = text(installAttentionReason);
        this.installWindowTitle = text(installWindowTitle);
        this.installLauncher = text(installLauncher);
        this.operationState = text(operationState);
        this.operationProgress = operationProgress;
        this.uninstalling = uninstalling;
        this.vibepolloState = text(vibepolloState);
    }

    @Override public boolean equals(Object value) {
        if (this == value) return true;
        if (!(value instanceof PlayniteLibraryGame)) return false;
        PlayniteLibraryGame game = (PlayniteLibraryGame) value;
        return installed == game.installed && installing == game.installing &&
                uninstalling == game.uninstalling && operationProgress == game.operationProgress &&
                installRequiresAttention == game.installRequiresAttention &&
                hidden == game.hidden &&
                playtimeSeconds == game.playtimeSeconds &&
                playniteGameId.equals(game.playniteGameId) && name.equals(game.name) &&
                lastActivity.equals(game.lastActivity) && coverKey.equals(game.coverKey) &&
                backgroundKey.equals(game.backgroundKey) &&
                description.equals(game.description) && playCount == game.playCount &&
                source.equals(game.source) && genres.equals(game.genres) &&
                provider.equals(game.provider) && providerGameId.equals(game.providerGameId) &&
                metadataPlayniteGameId.equals(game.metadataPlayniteGameId) &&
                libraryKey.equals(game.libraryKey) && libraryName.equals(game.libraryName) &&
                canLaunch == game.canLaunch && canInstall == game.canInstall &&
                canUninstall == game.canUninstall &&
                installAttentionReason.equals(game.installAttentionReason) &&
                installWindowTitle.equals(game.installWindowTitle) &&
                installLauncher.equals(game.installLauncher) &&
                operationState.equals(game.operationState) &&
                vibepolloState.equals(game.vibepolloState);
    }

    @Override public int hashCode() {
        return Objects.hash(playniteGameId, name, installed, installing, hidden, playtimeSeconds,
                lastActivity, coverKey, backgroundKey, description, playCount, source, genres,
                installRequiresAttention, installAttentionReason, installWindowTitle,
                installLauncher, operationState, operationProgress, uninstalling,
                vibepolloState, provider, providerGameId, metadataPlayniteGameId,
                libraryKey, libraryName, canLaunch, canInstall, canUninstall);
    }

    private static String text(String value) { return value == null ? "" : value.trim(); }

    private static boolean isGuid(String value) {
        return text(value).matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-"
                + "[0-9a-f]{4}-[0-9a-f]{12}");
    }

    private static String providerFrom(String source) {
        String normalized = text(source).toLowerCase(java.util.Locale.ROOT);
        return "steam".equals(normalized) || "epic".equals(normalized)
                ? normalized : "playnite";
    }

    private static String sourceKey(String source) {
        String normalized = text(source).toLowerCase(java.util.Locale.ROOT);
        return normalized.isEmpty() ? "playnite" : normalized;
    }

    private static String descriptionText(String value) {
        return text(value).replaceAll("(?:\\r?\\n[ \\t]*){3,}", "\n\n");
    }
}
