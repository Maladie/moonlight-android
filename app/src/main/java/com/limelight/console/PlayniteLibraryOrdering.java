package com.limelight.console;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.GregorianCalendar;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic five-recent-first ordering for the dashboard. */
final class PlayniteLibraryOrdering {
    private static final int RECENT_LIMIT = 5;
    private static final Pattern ISO_DATE = Pattern.compile(
            "^(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2}):(\\d{2})(?:\\.(\\d+))?(Z|[+-]\\d{2}:?\\d{2})?.*$");

    static List<PlayniteLibraryGame> order(List<PlayniteLibraryGame> source,
                                           boolean installedOnly, Locale locale) {
        List<PlayniteLibraryGame> visible = new ArrayList<>();
        for (PlayniteLibraryGame game : source) {
            if (game.hidden || (installedOnly && !game.installed)) continue;
            visible.add(game);
        }

        Comparator<PlayniteLibraryGame> alphabetic = alphabetic(locale);
        List<PlayniteLibraryGame> played = new ArrayList<>();
        for (PlayniteLibraryGame game : visible) {
            if (activityEpoch(game.lastActivity) != Long.MIN_VALUE) played.add(game);
        }
        played.sort((left, right) -> {
            int byDate = Long.compare(activityEpoch(right.lastActivity),
                    activityEpoch(left.lastActivity));
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
