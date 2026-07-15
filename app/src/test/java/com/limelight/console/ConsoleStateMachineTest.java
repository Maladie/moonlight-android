package com.limelight.console;

import org.junit.Test;
import static org.junit.Assert.*;

public class ConsoleStateMachineTest {
    @Test public void homeBackRequestsConfirmationWithoutFinishingState() {
        ConsoleStateMachine machine = new ConsoleStateMachine();
        ConsoleStateMachine.Transition result = machine.dispatch(ConsoleStateMachine.Event.BACK);
        assertEquals(ConsoleStateMachine.State.HOME, result.current);
        assertEquals(ConsoleStateMachine.Effect.SHOW_EXIT_CONFIRMATION, result.effect);
    }

    @Test public void consoleCoverNeverChangesSessionToConnecting() {
        ConsoleStateMachine machine = connectedMachine();
        assertEquals(ConsoleStateMachine.State.CONSOLE_OVER_STREAM,
                machine.dispatch(ConsoleStateMachine.Event.OPEN_CONSOLE).current);
        ConsoleStateMachine.Transition returned = machine.dispatch(ConsoleStateMachine.Event.RETURN_TO_STREAM);
        assertEquals(ConsoleStateMachine.State.STREAM, returned.current);
        assertTrue(returned.capturesGameplayInput());
    }

    @Test public void overlayAndHomeReleaseGameplayCapture() {
        ConsoleStateMachine machine = connectedMachine();
        assertFalse(machine.dispatch(ConsoleStateMachine.Event.OPEN_OVERLAY).capturesGameplayInput());
        assertEquals(ConsoleStateMachine.InputTarget.OVERLAY,
                ConsoleStateMachine.inputTarget(machine.getState()));
        assertEquals(ConsoleStateMachine.State.CONSOLE_OVER_STREAM,
                machine.dispatch(ConsoleStateMachine.Event.OPEN_CONSOLE).current);
    }

    @Test public void disconnectIsDistinctAndCompletesAtHome() {
        ConsoleStateMachine machine = connectedMachine();
        assertEquals(ConsoleStateMachine.State.DISCONNECTING,
                machine.dispatch(ConsoleStateMachine.Event.DISCONNECT).current);
        assertEquals(ConsoleStateMachine.State.HOME,
                machine.dispatch(ConsoleStateMachine.Event.DISCONNECTED).current);
    }

    @Test public void recoveryHomePreservesActiveSession() {
        ConsoleStateMachine machine = connectedMachine();
        machine.dispatch(ConsoleStateMachine.Event.CONNECTION_FAILED);
        assertEquals(ConsoleStateMachine.State.CONSOLE_OVER_STREAM,
                machine.dispatch(ConsoleStateMachine.Event.HOME).current);
    }

    private static ConsoleStateMachine connectedMachine() {
        ConsoleStateMachine machine = new ConsoleStateMachine();
        machine.dispatch(ConsoleStateMachine.Event.LAUNCH);
        machine.dispatch(ConsoleStateMachine.Event.CONNECTED);
        return machine;
    }
}
