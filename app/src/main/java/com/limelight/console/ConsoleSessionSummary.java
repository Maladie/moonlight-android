package com.limelight.console;

import java.util.Locale;

/** Pure Home presentation model for the current streaming session. */
final class ConsoleSessionSummary {
    final String label;
    final boolean alive;

    private ConsoleSessionSummary(String label, boolean alive) {
        this.label = label;
        this.alive = alive;
    }

    static ConsoleSessionSummary from(ConsoleDataRepository.Session session) {
        if (session == null || session.state == null) {
            return new ConsoleSessionSummary("SESSION · STATUS UNAVAILABLE", false);
        }
        StringBuilder text = new StringBuilder("SESSION · ")
                .append(session.state.toUpperCase(Locale.ROOT));
        if (session.app != null && !session.app.isEmpty()) {
            text.append(" · ").append(session.app);
        }
        if (session.width > 0) {
            text.append(" · ").append(session.width).append('×').append(session.height)
                    .append(" @ ").append(session.fps);
        }
        return new ConsoleSessionSummary(text.toString(), session.alive);
    }
}
