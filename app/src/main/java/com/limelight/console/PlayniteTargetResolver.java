package com.limelight.console;

import com.limelight.nvstream.http.NvApp;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Resolves the Sunshine apps MoonWaker streams through. */
public final class PlayniteTargetResolver {
    static final String MOONWAKER_STREAM_NAME = "MoonWaker Stream";
    static final String MOONWAKER_STREAM_UUID =
            "6d6f6f6e-7761-4b65-9273-747265616d00";

    /**
     * Games start only through the neutral stream. A list that is not authoritative (host
     * offline) proves nothing about it: the game stays launchable and preflight decides.
     */
    static PlayniteDashboardItem resolve(PlayniteLibraryGame game, List<NvApp> apps,
                                         boolean appListAuthoritative) {
        if (!game.installed) {
            return new PlayniteDashboardItem(game, null,
                    PlayniteDashboardItem.MappingState.NOT_INSTALLED);
        }
        NvApp stream = resolveNeutralStream(apps);
        if (stream != null) {
            return new PlayniteDashboardItem(game, stream.getAppId(),
                    PlayniteDashboardItem.MappingState.MAPPED);
        }
        return new PlayniteDashboardItem(game, null, appListAuthoritative
                ? PlayniteDashboardItem.MappingState.MISSING
                : PlayniteDashboardItem.MappingState.MAPPED);
    }

    static NvApp resolvePlayniteFullscreen(List<NvApp> apps) {
        List<NvApp> candidates = new ArrayList<>();
        for (NvApp app : apps) {
            String name = normalize(app.getAppName());
            if ("playnite".equals(name) || "playnite fullscreen".equals(name)) {
                candidates.add(app);
            }
        }
        return candidates.size() == 1 ? candidates.get(0) : null;
    }

    static NvApp resolveInstallationStream(List<NvApp> apps) {
        NvApp neutral = resolveNeutralStream(apps);
        if (neutral == null) neutral = resolveLegacyProviderStream(apps);
        return neutral != null ? neutral : resolvePlayniteFullscreen(apps);
    }

    static NvApp findById(List<NvApp> apps, Integer id) {
        if (id == null) return null;
        for (NvApp app : apps) if (app.getAppId() == id) return app;
        return null;
    }

    static String playniteGameId(NvApp app) {
        String uuid = app == null ? "" : normalizeUuid(app.getAppUuid());
        return HostGatewayClient.isPlayniteId(uuid) ? uuid : "";
    }

    static NvApp resolveNeutralStream(List<NvApp> apps) {
        NvApp candidate = preferredEquivalent(exactName(apps, MOONWAKER_STREAM_NAME));
        return isNeutralStream(candidate) ? candidate : null;
    }

    static boolean isNeutralStream(NvApp app) {
        return app != null && normalize(MOONWAKER_STREAM_NAME).equals(
                normalize(app.getAppName()))
                && MOONWAKER_STREAM_UUID.equals(normalizeUuid(app.getAppUuid()));
    }

    /**
     * The host names its desktop stream "Desktop"; the UI shows {@code desktopLabel}. Display
     * only: keys, comparisons and extras keep the host's name.
     */
    public static String appDisplayName(String appName, String desktopLabel) {
        return "Desktop".equals(appName) ? desktopLabel : appName;
    }

    /**
     * The cover title of a game's transition: its library name, never the name of the stream
     * target it runs through. Empty when the game's own name is unknown.
     */
    public static String gameCoverTitle(String gameName, String streamTargetName) {
        String name = gameName == null ? "" : gameName.trim();
        boolean streamName = normalize(name).equals(normalize(MOONWAKER_STREAM_NAME))
                || (streamTargetName != null
                && normalize(name).equals(normalize(streamTargetName)));
        return streamName ? "" : name;
    }

    static NvApp resolveLegacyProviderStream(List<NvApp> apps) {
        return preferredEquivalent(exactName(apps, "Desktop"));
    }

    static NvApp findPlayableExactName(List<NvApp> apps, String name) {
        return preferredEquivalent(exactName(apps, name));
    }

    private static NvApp preferredEquivalent(List<NvApp> candidates) {
        if (candidates.isEmpty()) return null;
        if (candidates.size() == 1) return candidates.get(0);
        String uuid = normalizeUuid(candidates.get(0).getAppUuid());
        if (uuid.isEmpty()) return null;
        NvApp preferred = candidates.get(0);
        for (NvApp candidate : candidates) {
            if (!uuid.equals(normalizeUuid(candidate.getAppUuid()))) return null;
            if (artScore(candidate) > artScore(preferred)) preferred = candidate;
        }
        return preferred;
    }

    private static int artScore(NvApp app) {
        String version = normalize(app.getArtVersion());
        return version.isEmpty() || "default".equals(version) ? 0 : 1;
    }

    private static String normalizeUuid(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static List<NvApp> exactName(List<NvApp> apps, String name) {
        List<NvApp> result = new ArrayList<>();
        String expected = normalize(name);
        for (NvApp app : apps) {
            if (expected.equals(normalize(app.getAppName()))) result.add(app);
        }
        return result;
    }

    private static final java.util.regex.Pattern WHITESPACE_RUN =
            java.util.regex.Pattern.compile("\\s+");

    private static String normalize(String value) {
        return value == null ? "" : WHITESPACE_RUN.matcher(
                value.trim().toLowerCase(Locale.ROOT)).replaceAll(" ");
    }

    private PlayniteTargetResolver() { }
}
