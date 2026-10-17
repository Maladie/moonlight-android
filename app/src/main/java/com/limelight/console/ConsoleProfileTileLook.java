package com.limelight.console;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Profile tile visuals (UX_MENU_PL.md section 3.7, mockup 12): the badge cut into the avatar
 * edge, at most two status lines instead of a badge stack, and whether Options offers the
 * automatic-login switch.
 */
final class ConsoleProfileTileLook {
    enum Badge { NONE, LOCK, AUTOMATIC }

    enum Line { CHILD, REMAINING, ACTIVE }

    static final int MAX_LINES = 2;

    final Badge badge;
    final List<Line> lines;
    /** Only profiles that can be entered without a PIN or a parent may log in automatically. */
    final boolean automaticToggle;

    private ConsoleProfileTileLook(Badge badge, List<Line> lines, boolean automaticToggle) {
        this.badge = badge;
        this.lines = lines;
        this.automaticToggle = automaticToggle;
    }

    static ConsoleProfileTileLook of(HostGatewayClient.IntegrationProfile profile,
                                     boolean automatic) {
        List<Line> lines = new ArrayList<>();
        if (profile.isChild()) {
            lines.add(Line.CHILD);
            if (profile.remainingDailySeconds > 0L) lines.add(Line.REMAINING);
        }
        // A child with time left keeps its two child lines; "active" yields.
        if ("active".equals(profile.sessionState) && lines.size() < MAX_LINES) {
            lines.add(Line.ACTIVE);
        }
        Badge badge = profile.pinRequired ? Badge.LOCK
                : automatic ? Badge.AUTOMATIC : Badge.NONE;
        return new ConsoleProfileTileLook(badge, Collections.unmodifiableList(lines),
                !profile.pinRequired && !profile.isChild());
    }
}
