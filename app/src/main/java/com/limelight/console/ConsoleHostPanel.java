package com.limelight.console;

import com.limelight.R;

/**
 * Host panel structure (UX_MENU_PL.md section 3.3): three main actions (Pulpit, Obudź or Uśpij,
 * Zamknij stream), a "Więcej" group, and the destructive actions last. The caller resolves the
 * facts from its stores and performs the chosen action.
 */
final class ConsoleHostPanel {
    enum Action {
        DESKTOP(R.drawable.ic_console_monitor), WAKE(R.drawable.ic_console_sun),
        SLEEP(R.drawable.ic_console_moon), CLOSE_STREAM(R.drawable.ic_console_close),
        TEST_CONNECTION(R.drawable.ic_console_pulse), HARD_TERMINATE(R.drawable.ic_console_power),
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
    }

    static ConsoleActionMenu<Action> menu(Facts facts) {
        ConsoleActionMenu<Action> menu = new ConsoleActionMenu<Action>()
                .add(Action.DESKTOP, R.string.console_launch_desktop, facts.online && facts.paired);
        if (facts.reachable) {
            menu.add(Action.SLEEP, R.string.console_sleep_host,
                    facts.online && facts.gatewayAvailable);
        } else {
            menu.add(Action.WAKE, R.string.console_wake_host, facts.canWake);
        }
        return menu.add(Action.CLOSE_STREAM, R.string.console_close_stream,
                        facts.online && facts.paired && facts.streamRunning)
                .group(R.string.console_section_more)
                .add(Action.TEST_CONNECTION, R.string.pcview_menu_test_network, true)
                .addDestructive(Action.HARD_TERMINATE, R.string.console_hard_terminate_session,
                        facts.online && facts.paired && facts.gatewayAvailable)
                .addDestructive(Action.UNPAIR, R.string.console_unpair_host,
                        facts.online && facts.paired)
                .done();
    }

    private ConsoleHostPanel() { }
}
