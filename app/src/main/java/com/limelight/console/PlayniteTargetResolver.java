package com.limelight.console;

import com.limelight.nvstream.http.NvApp;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Resolves explicit mappings and exact, unique names emitted by Vibepollo's Playnite sync. */
final class PlayniteTargetResolver {
    static final String MOONWAKER_STREAM_NAME = "MoonWaker Stream";
    static final String MOONWAKER_STREAM_UUID =
            "6d6f6f6e-7761-4b65-9273-747265616d00";

    static PlayniteDashboardItem resolve(String hostUuid, PlayniteLibraryGame game,
                                         List<NvApp> apps,
                                         PlayniteLaunchTargetStore store) {
        return resolve(hostUuid, game, apps, store, true);
    }

    static PlayniteDashboardItem resolve(String hostUuid, PlayniteLibraryGame game,
                                         List<NvApp> apps,
                                         PlayniteLaunchTargetStore store,
                                         boolean appListAuthoritative) {
        if (!game.installed) {
            return new PlayniteDashboardItem(game, null, "",
                    PlayniteDashboardItem.MappingState.NOT_INSTALLED);
        }
        if (isManagedGame(game)) {
            NvApp stream = appListAuthoritative ? resolveNeutralStream(apps) : null;
            if (stream == null && isDirectProvider(game) && appListAuthoritative) {
                stream = resolveLegacyProviderStream(apps);
            }
            if (stream != null) {
                if (store != null) {
                    store.setGameTarget(hostUuid, game.playniteGameId, stream.getAppId());
                }
                return new PlayniteDashboardItem(game, stream.getAppId(), stream.getAppName(),
                        PlayniteDashboardItem.MappingState.MAPPED);
            }
            Integer saved = store != null ? store.gameTarget(hostUuid, game.playniteGameId) : null;
            if (saved != null && !appListAuthoritative) {
                return new PlayniteDashboardItem(game, saved, game.name,
                        PlayniteDashboardItem.MappingState.MAPPED);
            }
            if (isDirectProvider(game) && saved != null && store != null) {
                store.clearGameTarget(hostUuid, game.playniteGameId);
            }
            if (isDirectProvider(game)) {
                return new PlayniteDashboardItem(game, null, "",
                        PlayniteDashboardItem.MappingState.MISSING);
            }
        }
        if (appListAuthoritative) {
            NvApp synchronizedApp = findByUuid(apps, streamIdentity(game));
            if (synchronizedApp != null) {
                if (store != null) {
                    store.setGameTarget(hostUuid, game.playniteGameId,
                            synchronizedApp.getAppId());
                }
                return new PlayniteDashboardItem(game, synchronizedApp.getAppId(),
                        synchronizedApp.getAppName(), PlayniteDashboardItem.MappingState.MAPPED);
            }
        }
        Integer saved = store != null ? store.gameTarget(hostUuid, game.playniteGameId) : null;
        NvApp mapped = findById(apps, saved);
        if (mapped != null) {
            return new PlayniteDashboardItem(game, mapped.getAppId(), mapped.getAppName(),
                    isPlayniteFullscreen(mapped)
                            ? PlayniteDashboardItem.MappingState.FALLBACK_PLAYNITE
                            : PlayniteDashboardItem.MappingState.MAPPED);
        }
        // An offline host cannot provide a current Sunshine app list. Keep the
        // persisted app ID so selection can enter the normal launch path, send
        // Wake-on-LAN, and validate the target after the host becomes ready.
        if (saved != null && !appListAuthoritative) {
            return new PlayniteDashboardItem(game, saved, game.name,
                    PlayniteDashboardItem.MappingState.MAPPED);
        }
        if (saved != null && store != null) store.clearGameTarget(hostUuid, game.playniteGameId);

        if (!"playnite".equals(game.provider)) {
            return new PlayniteDashboardItem(game, null, "",
                    PlayniteDashboardItem.MappingState.MISSING);
        }

        List<NvApp> exact = exactName(apps, game.name);
        NvApp synchronizedApp = preferredEquivalent(exact);
        if (synchronizedApp != null) {
            if (store != null) {
                store.setGameTarget(hostUuid, game.playniteGameId, synchronizedApp.getAppId());
            }
            return new PlayniteDashboardItem(game, synchronizedApp.getAppId(),
                    synchronizedApp.getAppName(), PlayniteDashboardItem.MappingState.MAPPED);
        }
        return new PlayniteDashboardItem(game, null, "", exact.size() > 1
                ? PlayniteDashboardItem.MappingState.AMBIGUOUS
                : PlayniteDashboardItem.MappingState.MISSING);
    }

    static NvApp resolvePlayniteFullscreen(String hostUuid, List<NvApp> apps,
                                           PlayniteLaunchTargetStore store) {
        Integer saved = store != null ? store.playniteTarget(hostUuid) : null;
        NvApp mapped = findById(apps, saved);
        if (mapped != null) return mapped;
        if (saved != null && store != null) store.clearPlayniteTarget(hostUuid);
        List<NvApp> candidates = new ArrayList<>();
        for (NvApp app : apps) {
            String name = normalize(app.getAppName());
            if ("playnite".equals(name) || "playnite fullscreen".equals(name)) {
                candidates.add(app);
            }
        }
        return candidates.size() == 1 ? candidates.get(0) : null;
    }

    static NvApp resolveInstallationStream(String hostUuid, List<NvApp> apps,
                                           PlayniteLaunchTargetStore store) {
        NvApp neutral = resolveNeutralStream(apps);
        if (neutral == null) neutral = resolveLegacyProviderStream(apps);
        return neutral != null ? neutral
                : resolvePlayniteFullscreen(hostUuid, apps, store);
    }

    static List<NvApp> playniteCandidates(List<NvApp> apps) {
        List<NvApp> result = new ArrayList<>();
        for (NvApp app : apps) {
            if (normalize(app.getAppName()).contains("playnite")) result.add(app);
        }
        return result;
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

    static NvApp launchTarget(PlayniteDashboardItem item, List<NvApp> apps,
                              boolean hostOnline) {
        if (item == null) return null;
        NvApp neutral = resolveNeutralStream(apps);
        if (neutral != null && isManagedGame(item.game)) return neutral;
        if (isDirectProvider(item.game)) {
            NvApp desktop = resolveLegacyProviderStream(apps);
            if (desktop != null) return desktop;
            if (item.sunshineAppId == null || hostOnline) return null;
            return new NvApp("Desktop", item.sunshineAppId, false);
        }
        NvApp current = findByUuid(apps, streamIdentity(item.game));
        if (current != null) return current;
        if (item.sunshineAppId == null) return null;
        current = findById(apps, item.sunshineAppId);
        if (current != null || hostOnline) return current;
        String name = item.sunshineAppName.isEmpty()
                ? item.game.name : item.sunshineAppName;
        return new NvApp(name, item.sunshineAppId, false);
    }

    static NvApp findByUuid(List<NvApp> apps, String uuid) {
        String expected = normalizeUuid(uuid);
        if (expected.isEmpty()) return null;
        NvApp preferred = null;
        for (NvApp app : apps) {
            if (!expected.equals(normalizeUuid(app.getAppUuid()))) continue;
            if (preferred == null || artScore(app) > artScore(preferred)) preferred = app;
        }
        return preferred;
    }

    static String streamIdentity(PlayniteLibraryGame game) {
        return game == null ? "" : game.playniteGameId;
    }

    static NvApp resolveProviderStream(List<NvApp> apps) {
        NvApp neutral = resolveNeutralStream(apps);
        return neutral != null ? neutral : resolveLegacyProviderStream(apps);
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

    static NvApp resolveLegacyProviderStream(List<NvApp> apps) {
        return preferredEquivalent(exactName(apps, "Desktop"));
    }

    static NvApp findUniqueExactName(List<NvApp> apps, String name) {
        List<NvApp> exact = exactName(apps, name);
        return exact.size() == 1 ? exact.get(0) : null;
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

    private static boolean isPlayniteFullscreen(NvApp app) {
        String name = normalize(app.getAppName());
        return "playnite".equals(name) || "playnite fullscreen".equals(name);
    }

    private static boolean isDirectProvider(PlayniteLibraryGame game) {
        return game != null && game.usesNeutralStream();
    }

    private static boolean isManagedGame(PlayniteLibraryGame game) {
        return game != null && !game.provider.isEmpty();
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

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ");
    }

    private PlayniteTargetResolver() { }
}
