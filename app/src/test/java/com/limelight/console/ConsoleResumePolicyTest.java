package com.limelight.console;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;

public class ConsoleResumePolicyTest {
    @Test public void connectingSessionIsAttachedThenCoveredByHome() {
        assertEquals(Arrays.asList(ConsoleStateMachine.Event.CONNECTED,
                        ConsoleStateMachine.Event.OPEN_CONSOLE),
                ConsoleResumePolicy.eventsFor(
                        ConsoleStateMachine.State.CONNECTING, true));
    }

    @Test public void visibleStreamIsCoveredWithoutReconnect() {
        assertEquals(Collections.singletonList(ConsoleStateMachine.Event.OPEN_CONSOLE),
                ConsoleResumePolicy.eventsFor(ConsoleStateMachine.State.STREAM, true));
    }

    @Test public void homeLearnsAboutLiveSessionWithoutOpeningSecondConnection() {
        assertEquals(Collections.singletonList(ConsoleStateMachine.Event.CONNECTED),
                ConsoleResumePolicy.eventsFor(ConsoleStateMachine.State.HOME, true));
    }

    @Test public void deadOrAlreadyCoveredSessionNeedsNoLifecycleEvent() {
        assertEquals(Collections.emptyList(), ConsoleResumePolicy.eventsFor(
                ConsoleStateMachine.State.CONNECTING, false));
        assertEquals(Collections.emptyList(), ConsoleResumePolicy.eventsFor(
                ConsoleStateMachine.State.CONSOLE_OVER_STREAM, true));
    }
}
