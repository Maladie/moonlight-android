package com.limelight.console;

import com.limelight.R;

/**
 * Host tile visuals per presentation state (UX_MENU_PL.md section 3.7, mockups 10 and 11):
 * status dot, the badge on the tile, the waking ring, dimming and the status line.
 */
final class ConsoleHostTileLook {
    enum Badge { NONE, LIVE, ASLEEP, PAIR, ATTENTION }

    /** Mockup 11 palette. */
    static final int ONLINE = 0xFF30D694;
    static final int LIVE_RED = 0xFFFF5460;
    static final int AMBER = 0xFFFFC454;
    static final int ASLEEP = 0xFF8FA6BC;
    static final int OFFLINE_RED = 0xFFFF6464;

    final Badge badge;
    final int dot;
    final boolean wakingRing;
    final boolean dimmed;
    /** String replacing the host status text, or 0 to show that text. */
    final int label;
    /** The dot is a turning arc: the host has not been polled yet. */
    final boolean spinner;

    private ConsoleHostTileLook(Badge badge, int dot, boolean wakingRing, boolean dimmed,
                                int label) {
        this(badge, dot, wakingRing, dimmed, label, false);
    }

    private ConsoleHostTileLook(Badge badge, int dot, boolean wakingRing, boolean dimmed,
                                int label, boolean spinner) {
        this.badge = badge;
        this.dot = dot;
        this.wakingRing = wakingRing;
        this.dimmed = dimmed;
        this.label = label;
        this.spinner = spinner;
    }

    /** {@code wakingTimed}: this client started the wake, so its seconds can be counted. */
    static ConsoleHostTileLook of(ConsoleHostPresentation.State state,
                                  ConsoleHostPresentation.ProfileState profile,
                                  boolean wakingTimed) {
        switch (state) {
            case ACTIVE_SESSION:
                return new ConsoleHostTileLook(Badge.LIVE, LIVE_RED, false, false, 0);
            case PROFILE_ATTENTION:
                return new ConsoleHostTileLook(Badge.ATTENTION, AMBER, false, false,
                        profile == ConsoleHostPresentation.ProfileState.SIGN_IN_REQUIRED
                                ? R.string.console_host_tile_sign_in : 0);
            case WAKING:
                return new ConsoleHostTileLook(Badge.NONE, AMBER, true, false,
                        wakingTimed ? R.string.console_host_tile_waking : 0);
            case CONNECTING:
                return new ConsoleHostTileLook(Badge.NONE, AMBER, false, false, 0);
            case ASLEEP:
                return new ConsoleHostTileLook(Badge.ASLEEP, ASLEEP, false, false, 0);
            case UNREACHABLE:
            case OFFLINE:
                return new ConsoleHostTileLook(Badge.NONE, OFFLINE_RED, false, true,
                        R.string.console_host_tile_unavailable);
            case UNPAIRED:
                return new ConsoleHostTileLook(Badge.PAIR, AMBER, false, false,
                        R.string.console_host_tile_pair);
            case CHECKING:
                return new ConsoleHostTileLook(Badge.NONE, ASLEEP, false, false,
                        R.string.console_status_checking, true);
            case ONLINE:
            default:
                return new ConsoleHostTileLook(Badge.NONE, ONLINE, false, false, 0);
        }
    }
}
