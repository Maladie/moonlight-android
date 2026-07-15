package com.limelight.console;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Pure lifecycle policy for covering an already-live stream with Console Home. */
final class ConsoleResumePolicy {
    private ConsoleResumePolicy() { }

    static List<ConsoleStateMachine.Event> eventsFor(ConsoleStateMachine.State state,
                                                     boolean sessionAlive) {
        if (!sessionAlive || state == null) return Collections.emptyList();
        switch (state) {
            case CONNECTING:
                return Arrays.asList(ConsoleStateMachine.Event.CONNECTED,
                        ConsoleStateMachine.Event.OPEN_CONSOLE);
            case STREAM:
                return Collections.singletonList(ConsoleStateMachine.Event.OPEN_CONSOLE);
            case HOME:
                return Collections.singletonList(ConsoleStateMachine.Event.CONNECTED);
            default:
                return Collections.emptyList();
        }
    }
}
