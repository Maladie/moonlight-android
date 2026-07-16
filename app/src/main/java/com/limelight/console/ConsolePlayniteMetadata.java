package com.limelight.console;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Builds compact Playnite metadata that remains readable on TV game cards. */
final class ConsolePlayniteMetadata {
    private ConsolePlayniteMetadata() { }

    static String format(long playtimeMinutes, String lastPlayed, boolean installed) {
        List<String> values = new ArrayList<>();
        long minutes = Math.max(0L, playtimeMinutes);
        if (minutes > 0L) {
            long hours = minutes / 60L;
            long remainder = minutes % 60L;
            values.add(hours > 0L ? String.format(Locale.US, "%dH %02dM", hours, remainder) :
                    String.format(Locale.US, "%dM", remainder));
        }
        String date = compactDate(lastPlayed);
        if (!date.isEmpty()) values.add("LAST " + date);
        if (!installed) values.add("NOT INSTALLED");
        if (values.isEmpty()) values.add("NOT PLAYED");
        return String.join("  ·  ", values);
    }

    private static String compactDate(String value) {
        if (value == null) return "";
        String text = value.trim();
        if (text.length() >= 10 && text.charAt(4) == '-' && text.charAt(7) == '-') {
            return text.substring(8, 10) + "." + text.substring(5, 7) + "." +
                    text.substring(2, 4);
        }
        return "";
    }
}
