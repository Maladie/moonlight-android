package com.limelight.console;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Produces the console-facing Playnite order without changing Bridge data. */
final class ConsolePlayniteLibraryPresenter {
    private static final int RECENT_LIMIT = 5;

    static List<ConsoleDataRepository.App> prepare(
            List<ConsoleDataRepository.App> source, boolean installedOnly) {
        List<ConsoleDataRepository.App> visible = new ArrayList<>();
        for (ConsoleDataRepository.App app : source) {
            if (!installedOnly || app.installed) visible.add(app);
        }
        List<ConsoleDataRepository.App> played = new ArrayList<>();
        for (ConsoleDataRepository.App app : visible) {
            if (!app.lastPlayed.isEmpty()) played.add(app);
        }
        played.sort((left, right) -> right.lastPlayed.compareTo(left.lastPlayed));
        if (played.size() > RECENT_LIMIT) {
            played = new ArrayList<>(played.subList(0, RECENT_LIMIT));
        }
        Set<String> recentIds = new HashSet<>();
        for (ConsoleDataRepository.App app : played) recentIds.add(app.playniteGameGuid);
        List<ConsoleDataRepository.App> remaining = new ArrayList<>();
        for (ConsoleDataRepository.App app : visible) {
            if (!recentIds.contains(app.playniteGameGuid)) remaining.add(app);
        }
        remaining.sort(Comparator.comparing(
                app -> app.name.toLowerCase(Locale.ROOT)));
        played.addAll(remaining);
        return played;
    }

    private ConsolePlayniteLibraryPresenter() {}
}
