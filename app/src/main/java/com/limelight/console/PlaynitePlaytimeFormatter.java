package com.limelight.console;

/** Formats Playnite seconds with labels supplied by localized Android resources. */
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
        long totalMinutes = Math.max(1L, seconds / 60L);
        long hours = totalMinutes / 60L;
        long minutes = totalMinutes % 60L;
        if (hours == 0L) return totalMinutes + " " + labels.minutes;
        if (minutes == 0L) return hours + " " + labels.hours;
        return hours + " " + labels.hours + " " + minutes + " " + labels.minutes;
    }

    private PlaynitePlaytimeFormatter() { }
}
