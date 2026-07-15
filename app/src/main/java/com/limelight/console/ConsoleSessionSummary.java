package com.limelight.console;

import java.util.Locale;

/** Pure Home presentation model for the current streaming session. */
final class ConsoleSessionSummary {
    final String label;
    final String title;
    final String details;
    final boolean alive;

    private ConsoleSessionSummary(String label, boolean alive) {
        this(label, "Active stream", "Moonlight is streaming to this TV.", alive);
    }

    private ConsoleSessionSummary(String label, String title, String details, boolean alive) {
        this.label = label;
        this.title = title;
        this.details = details;
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
        String title = session.app != null && !session.app.isEmpty() ?
                session.app : "Active stream";
        StringBuilder details = new StringBuilder();
        if (session.host != null && !session.host.isEmpty()) details.append(session.host);
        if (session.width > 0) {
            if (details.length() > 0) details.append(" \u00B7 ");
            details.append(session.width).append('\u00D7').append(session.height);
            if (session.fps > 0) details.append(" @ ").append(session.fps).append(" FPS");
        }
        return new ConsoleSessionSummary(text.toString(), title,
                details.length() > 0 ? details.toString() :
                        "Moonlight is streaming to this TV.", session.alive);
    }
}
