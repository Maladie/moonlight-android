package com.limelight.console;

import com.limelight.R;

/**
 * The status badge of a game tile (mockups/tile-badges-v2.html): which icon, colour and form
 * a game's state shows, its percent and progress bar, and the full words for the side panel.
 * A filled badge means something happens now or waits on the user; an outlined one is a
 * resting state. A ready game and a game blocked for a child show no badge.
 */
final class ConsoleTileBadge {
    static final int AMBER = 0xFFE4B34B;
    static final int RED = 0xFFE16D63;
    static final int BLUE = 0xFF73D7FF;
    static final int GREY = 0xFF9AA5AD;
    /** The outlined badge's own background. */
    static final int OUTLINED_FILL = 0xE00E1318;

    enum Kind {
        /** Blocked for a child: nothing on the tile and nothing in the panel. */
        NONE(0, 0, false, false, 0),
        /** Ready to launch: no badge; the words are for the spoken description only. */
        READY(0, 0, false, false, R.string.playnite_game_installed),
        UPDATE_REQUIRED(R.drawable.ic_console_badge_update, AMBER, false, false,
                R.string.playnite_update_required),
        UPDATING(R.drawable.ic_console_badge_update, AMBER, true, true,
                R.string.playnite_update_downloading),
        PATCHING(R.drawable.ic_console_badge_patch, AMBER, true, true,
                R.string.playnite_update_patching),
        INSTALLING(R.drawable.ic_console_badge_install, AMBER, true, true,
                R.string.playnite_installing),
        UNINSTALLING(R.drawable.ic_console_badge_uninstall, AMBER, true, true,
                R.string.playnite_uninstalling),
        QUEUED(R.drawable.ic_console_badge_queued, AMBER, false, false,
                R.string.console_queued),
        ATTENTION(R.drawable.ic_console_badge_attention, RED, true, false,
                R.string.playnite_install_attention),
        NOT_CONFIGURED(R.drawable.ic_console_badge_not_configured, RED, false, false,
                R.string.playnite_launch_not_configured),
        RESUME(R.drawable.ic_console_badge_play, BLUE, true, false,
                R.string.playnite_session_running),
        SUSPENDED(R.drawable.ic_console_badge_pause, BLUE, false, false,
                R.string.playnite_session_suspended),
        NOT_INSTALLED(R.drawable.ic_console_badge_cloud, GREY, false, false,
                R.string.playnite_not_installed);

        final int icon;
        final int color;
        final boolean filled;
        final boolean bar;
        final int label;

        Kind(int icon, int color, boolean filled, boolean bar, int label) {
            this.icon = icon;
            this.color = color;
            this.filled = filled;
            this.bar = bar;
            this.label = label;
        }
    }

    final Kind kind;
    /** 0-100, or -1 when the work has no known progress; only for kinds with a bar. */
    final int progress;

    private ConsoleTileBadge(Kind kind, int progress) {
        this.kind = kind;
        this.progress = kind.bar ? Math.max(-1, Math.min(100, progress)) : -1;
    }

    static ConsoleTileBadge of(PlayniteSessionPresentation.State session, boolean queued,
                               GameOperationsController.Presentation operation,
                               PlayniteLibraryGame game, boolean mapped, boolean childBlocked) {
        if (childBlocked) return new ConsoleTileBadge(Kind.NONE, -1);
        // A session needing recovery is still this game's session: it resumes.
        if (session == PlayniteSessionPresentation.State.RESUME_ACTIVE
                || session == PlayniteSessionPresentation.State.RECOVERY_REQUIRED) {
            return new ConsoleTileBadge(Kind.RESUME, -1);
        }
        if (session == PlayniteSessionPresentation.State.RESUME_SUSPENDED) {
            return new ConsoleTileBadge(Kind.SUSPENDED, -1);
        }
        if (queued) return new ConsoleTileBadge(Kind.QUEUED, -1);
        if (operation.attentionRequired) return new ConsoleTileBadge(Kind.ATTENTION, -1);
        if (operation.uninstallActive) return new ConsoleTileBadge(Kind.UNINSTALLING, -1);
        if (operation.installActive) {
            Kind kind = !game.installed ? Kind.INSTALLING
                    : operation.phase == GameOperationsController.Phase.PATCHING
                    ? Kind.PATCHING : Kind.UPDATING;
            return new ConsoleTileBadge(kind, operation.progress);
        }
        if (!game.installed) return new ConsoleTileBadge(Kind.NOT_INSTALLED, -1);
        if (game.updateActive()) {
            return new ConsoleTileBadge("patching".equals(game.updateState)
                    ? Kind.PATCHING : Kind.UPDATING, game.updateProgress);
        }
        if (game.updateRequired()) return new ConsoleTileBadge(Kind.UPDATE_REQUIRED, -1);
        return new ConsoleTileBadge(mapped ? Kind.READY : Kind.NOT_CONFIGURED, -1);
    }

    /** Whether the tile shows a badge and the side panel a status line. */
    boolean visible() {
        return kind.icon != 0;
    }

    /** The badge's text: its percent, or empty for an icon-only round badge. */
    String percent() {
        return progress >= 0 ? progress + "%" : "";
    }

    int fillColor() {
        return kind.filled ? kind.color : OUTLINED_FILL;
    }

    /** The icon and text colour on the badge. */
    int contentColor() {
        if (kind.filled) {
            switch (kind.color) {
                case AMBER: return 0xFF1C1405;
                case RED: return 0xFF1E0807;
                default: return 0xFF04161D;
            }
        }
        return kind.color == GREY ? 0xFFE6EBEF : kind.color;
    }

    /** The side panel's status text colour; the panel pill's outline is {@code kind.color}. */
    int panelTextColor() {
        switch (kind.color) {
            case AMBER: return 0xFFFFE2A8;
            case RED: return 0xFFFFC4BE;
            case BLUE: return 0xFFE1F7FF;
            default: return 0xFFF0F5F8;
        }
    }

    @Override public boolean equals(Object other) {
        return other instanceof ConsoleTileBadge && ((ConsoleTileBadge) other).kind == kind
                && ((ConsoleTileBadge) other).progress == progress;
    }

    @Override public int hashCode() {
        return kind.hashCode() * 101 + progress;
    }
}
