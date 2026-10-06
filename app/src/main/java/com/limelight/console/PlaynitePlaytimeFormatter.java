package com.limelight.console;

/** Formats durations as hours and minutes with labels from localized Android resources. */
final class PlaynitePlaytimeFormatter {
    static final class Labels {
        final String neverPlayed;
        final String hours;
        final String minutes;

        Labels(String neverPlayed, String hours, String minutes) {
            this.neverPlayed = neverPlayed;
            this.hours = hours;
            this.minutes = minutes;
        }
    }

    static String format(long seconds, Labels labels) {
        if (seconds <= 0L) return labels.neverPlayed;
        return minutes(Math.max(1L, seconds / 60L), labels);
    }

    /** "23 godz. 1 min", "45 min", "2 godz." for a non-negative minute count. */
    static String minutes(long totalMinutes, Labels labels) {
        long hours = totalMinutes / 60L;
        long minutes = totalMinutes % 60L;
        if (hours == 0L) return totalMinutes + " " + labels.minutes;
        if (minutes == 0L) return hours + " " + labels.hours;
        return hours + " " + labels.hours + " " + minutes + " " + labels.minutes;
    }

    private PlaynitePlaytimeFormatter() { }
}
