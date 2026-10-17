package com.limelight.console;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Comparator;
import java.util.GregorianCalendar;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.function.ToLongFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic five-recent-first ordering for the dashboard. */
final class PlayniteLibraryOrdering {
    private static final int RECENT_LIMIT = 5;
    private static final Pattern ISO_DATE = Pattern.compile(
            "^(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2}):(\\d{2})(?:\\.(\\d+))?(Z|[+-]\\d{2}:?\\d{2})?.*$");

    static List<PlayniteLibraryGame> order(List<PlayniteLibraryGame> source,
                                           boolean installedOnly, Locale locale) {
        return order(source, installedOnly
                ? PlayniteLibraryFilter.INSTALLED : PlayniteLibraryFilter.ALL, locale);
    }

    static List<PlayniteLibraryGame> order(List<PlayniteLibraryGame> source,
                                           PlayniteLibraryFilter filter, Locale locale) {
        return order(source, filter, locale, null);
    }

    static List<PlayniteLibraryGame> order(List<PlayniteLibraryGame> source,
                                           PlayniteLibraryFilter filter, Locale locale,
                                           Map<String, Long> localActivity) {
        PlayniteLibraryFilter safeFilter = filter == null
                ? PlayniteLibraryFilter.ALL : filter;
        List<PlayniteLibraryGame> visible = new ArrayList<>();
        for (PlayniteLibraryGame game : source) {
            if (game.hidden || !matches(game, safeFilter, localActivity)) continue;
            visible.add(game);
        }

        Comparator<PlayniteLibraryGame> alphabetic = alphabetic(locale);
        if (safeFilter == PlayniteLibraryFilter.RECENTLY_PLAYED) {
            visible.sort(recentFirst(alphabetic, localActivity));
            return visible;
        }
        if (safeFilter == PlayniteLibraryFilter.MOST_LAUNCHED) {
            Comparator<PlayniteLibraryGame> recent = recentFirst(alphabetic, localActivity);
            visible.sort((left, right) -> {
                int byCount = Integer.compare(right.playCount, left.playCount);
                return byCount != 0 ? byCount : recent.compare(left, right);
            });
            return visible;
        }
        if (safeFilter == PlayniteLibraryFilter.NEVER_LAUNCHED) {
            visible.sort(alphabetic);
            return visible;
        }
        List<PlayniteLibraryGame> played = new ArrayList<>();
        for (PlayniteLibraryGame game : visible) {
            if (activityEpoch(game, localActivity) != Long.MIN_VALUE) played.add(game);
        }
        played.sort((left, right) -> {
            int byDate = Long.compare(activityEpoch(right, localActivity),
                    activityEpoch(left, localActivity));
            return byDate != 0 ? byDate : alphabetic.compare(left, right);
        });

        List<PlayniteLibraryGame> result = new ArrayList<>();
        Set<String> recentIds = new HashSet<>();
        for (int index = 0; index < Math.min(RECENT_LIMIT, played.size()); index++) {
            PlayniteLibraryGame game = played.get(index);
            result.add(game);
            recentIds.add(game.playniteGameId);
        }
        List<PlayniteLibraryGame> remaining = new ArrayList<>();
        for (PlayniteLibraryGame game : visible) {
            if (!recentIds.contains(game.playniteGameId)) remaining.add(game);
        }
        remaining.sort(alphabetic);
        result.addAll(remaining);
        return result;
    }

    /**
     * The dashboard carousel, the one rule for its order: the session's game ({@code
     * sessionGameId}, resumable or suspended, matched ignoring case), up to
     * three installs in progress, installed games by {@code activity} (newest first), then the
     * other installed games by name. {@code activity} is a game's recency (played or
     * installed); zero or less is none. Equal facts give the same order on every refresh.
     */
    static List<PlayniteDashboardItem> carousel(List<PlayniteDashboardItem> items, int limit,
                                                String sessionGameId, Set<String> installingIds,
                                                ToLongFunction<PlayniteDashboardItem> activity,
                                                Locale locale) {
        List<PlayniteDashboardItem> result = new ArrayList<>();
        if (items.isEmpty() || limit <= 0) return result;
        Comparator<PlayniteLibraryGame> alphabetic = alphabetic(locale);
        Comparator<PlayniteDashboardItem> byName = (left, right) ->
                alphabetic.compare(left.game, right.game);
        Comparator<PlayniteDashboardItem> recentFirst = Comparator
                .comparingLong(activity).reversed().thenComparing(byName);
        List<PlayniteDashboardItem> installing = new ArrayList<>();
        List<PlayniteDashboardItem> recent = new ArrayList<>();
        List<PlayniteDashboardItem> rest = new ArrayList<>();
        PlayniteDashboardItem session = null;
        for (PlayniteDashboardItem item : items) {
            if (session == null && !sessionGameId.isEmpty()
                    && sessionGameId.equalsIgnoreCase(item.stableId())) session = item;
            boolean installs = installingIds.contains(item.game.playniteGameId);
            if (installs) installing.add(item);
            if (!item.game.installed) continue;
            if (!installs && activity.applyAsLong(item) > 0L) recent.add(item);
            else rest.add(item);
        }
        installing.sort(recentFirst);
        recent.sort(recentFirst);
        rest.sort(byName);
        Set<String> included = new HashSet<>();
        if (session != null) {
            result.add(session);
            included.add(session.stableId());
        }
        for (PlayniteDashboardItem item : installing.subList(0, Math.min(3, installing.size()))) {
            if (result.size() < limit && included.add(item.stableId())) result.add(item);
        }
        for (List<PlayniteDashboardItem> group : Arrays.asList(recent, rest)) {
            for (PlayniteDashboardItem item : group) {
                if (result.size() >= limit) return result;
                if (included.add(item.stableId())) result.add(item);
            }
        }
        return result;
    }

    private static boolean matches(PlayniteLibraryGame game, PlayniteLibraryFilter filter,
                                   Map<String, Long> localActivity) {
        switch (filter) {
            case INSTALLED:
                return game.installed;
            case UNINSTALLED:
                return !game.installed;
            case RECENTLY_PLAYED:
                return activityEpoch(game, localActivity) != Long.MIN_VALUE;
            case MOST_LAUNCHED:
                return game.playCount > 0;
            case NEVER_LAUNCHED:
                return game.playCount == 0 &&
                        activityEpoch(game, localActivity) == Long.MIN_VALUE;
            case ALL:
            default:
                return true;
        }
    }

    private static Comparator<PlayniteLibraryGame> recentFirst(
            Comparator<PlayniteLibraryGame> alphabetic, Map<String, Long> localActivity) {
        return (left, right) -> {
            int byDate = Long.compare(activityEpoch(right, localActivity),
                    activityEpoch(left, localActivity));
            return byDate != 0 ? byDate : alphabetic.compare(left, right);
        };
    }

    private static long activityEpoch(PlayniteLibraryGame game,
                                      Map<String, Long> localActivity) {
        long playnite = game.activityEpoch;
        if (localActivity == null) return playnite;
        Long local = localActivity.get(game.playniteGameId);
        return local == null ? playnite : Math.max(playnite, local);
    }

    private static Comparator<PlayniteLibraryGame> alphabetic(Locale locale) {
        Collator collator = Collator.getInstance(locale == null ? Locale.getDefault() : locale);
        collator.setStrength(Collator.SECONDARY);
        return (left, right) -> {
            int byName = collator.compare(left.name, right.name);
            return byName != 0 ? byName : left.playniteGameId.compareTo(right.playniteGameId);
        };
    }

    static long activityEpoch(String value) {
        if (value == null) return Long.MIN_VALUE;
        Matcher match = ISO_DATE.matcher(value.trim());
        if (!match.matches()) return Long.MIN_VALUE;
        try {
            String zone = match.group(8);
            TimeZone timeZone = TimeZone.getTimeZone("UTC");
            int offset = 0;
            if (zone != null && !"Z".equalsIgnoreCase(zone)) {
                int sign = zone.charAt(0) == '-' ? -1 : 1;
                String compact = zone.substring(1).replace(":", "");
                offset = sign * (Integer.parseInt(compact.substring(0, 2)) * 60 +
                        Integer.parseInt(compact.substring(2, 4))) * 60 * 1000;
            }
            Calendar calendar = new GregorianCalendar(timeZone, Locale.US);
            calendar.clear();
            calendar.set(Integer.parseInt(match.group(1)), Integer.parseInt(match.group(2)) - 1,
                    Integer.parseInt(match.group(3)), Integer.parseInt(match.group(4)),
                    Integer.parseInt(match.group(5)), Integer.parseInt(match.group(6)));
            String fraction = match.group(7);
            int millis = fraction == null ? 0 : Integer.parseInt(
                    (fraction + "000").substring(0, 3));
            return calendar.getTimeInMillis() + millis - offset;
        } catch (RuntimeException invalidDate) {
            return Long.MIN_VALUE;
        }
    }

    private PlayniteLibraryOrdering() { }
}
