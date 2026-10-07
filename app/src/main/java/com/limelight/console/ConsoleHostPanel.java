package com.limelight.console;

import com.limelight.R;

/**
 * Host panel structure (UX_MENU_PL.md section 3.3): three main actions (Pulpit, Obudź or Uśpij,
 * Zamknij sesję; with an active session: Wróć do streamu, Zamknij sesję, Uśpij), a "Więcej"
 * group, and the destructive actions last. The caller resolves the facts from its stores and
 * performs the chosen action.
 */
final class ConsoleHostPanel {
    enum Action {
        RESUME(R.drawable.ic_console_play),
        DESKTOP(R.drawable.ic_console_monitor), WAKE(R.drawable.ic_console_sun),
        SLEEP(R.drawable.ic_console_moon), CLOSE_SESSION(R.drawable.ic_console_close),
        TEST_CONNECTION(R.drawable.ic_console_pulse), CHANGE_HOST(R.drawable.ic_console_leave_host),
        HARD_TERMINATE(R.drawable.ic_console_power),
        UNPAIR(R.drawable.ic_console_unlink);

        /** Line icon of the row (section 4.7). */
        final int icon;

        Action(int icon) {
            this.icon = icon;
        }
    }

    static final class Facts {
        /** The host reports ONLINE; power then offers sleep instead of wake. */
        boolean reachable;
        /** Online and not closing a session. */
        boolean online;
        boolean paired;
        boolean gatewayAvailable;
        /** Wake is possible right now (not terminating, wakeable, not already waking). */
        boolean canWake;
        boolean streamRunning;
        /** Opened from this host's home screen, so going back to host selection makes sense. */
        boolean inHost;
        /** A fresh decision read reports an active session on this host. */
        boolean sessionActive;
        /** That session can be resumed (the same check the resume action makes). */
        boolean resumeAvailable;
    }

    static ConsoleActionMenu<Action> menu(Facts facts) {
        ConsoleActionMenu<Action> menu = new ConsoleActionMenu<>();
        boolean closeSession = facts.online && facts.paired && facts.streamRunning;
        if (facts.sessionActive) {
            menu.add(Action.RESUME, R.string.console_return_to_stream, facts.resumeAvailable)
                    .add(Action.CLOSE_SESSION, R.string.console_close_session, closeSession);
            power(menu, facts);
        } else {
            menu.add(Action.DESKTOP, R.string.console_launch_desktop, facts.online && facts.paired);
            power(menu, facts);
            menu.add(Action.CLOSE_SESSION, R.string.console_close_session, closeSession);
        }
        menu.group(R.string.console_section_more)
                .add(Action.TEST_CONNECTION, R.string.pcview_menu_test_network, true);
        if (facts.inHost) menu.add(Action.CHANGE_HOST, R.string.console_change_host, true);
        return menu.addDestructive(Action.HARD_TERMINATE, R.string.console_hard_terminate_session,
                        facts.online && facts.paired && facts.gatewayAvailable)
                .addDestructive(Action.UNPAIR, R.string.console_unpair_host,
                        facts.online && facts.paired)
                .done();
    }

    private static void power(ConsoleActionMenu<Action> menu, Facts facts) {
        if (facts.reachable) {
            menu.add(Action.SLEEP, R.string.console_sleep_host,
                    facts.online && facts.gatewayAvailable);
        } else {
            menu.add(Action.WAKE, R.string.console_wake_host, facts.canWake);
        }
    }

    private ConsoleHostPanel() { }
}
