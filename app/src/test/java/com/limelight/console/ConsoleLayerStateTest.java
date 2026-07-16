package com.limelight.console;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ConsoleLayerStateTest {
    @Test public void homeCoversPersistentStreamAndOwnsInput() {
        ConsoleLayerState state = ConsoleLayerState.from(
                ConsoleStateMachine.State.CONSOLE_OVER_STREAM);

        assertTrue(state.homeVisible);
        assertFalse(state.privacyVisible);
        assertFalse(state.overlayVisible);
        assertEquals(InputRouter.Region.HOME, state.inputRegion);
    }

    @Test public void connectingIsOpaqueAndAcceptsNoInput() {
        ConsoleLayerState state = ConsoleLayerState.from(
                ConsoleStateMachine.State.CONNECTING);

        assertFalse(state.homeVisible);
        assertTrue(state.privacyVisible);
        assertEquals(InputRouter.Region.NONE, state.inputRegion);
    }

    @Test public void streamAndOverlayHaveExclusiveInputRegions() {
        assertEquals(InputRouter.Region.GAMEPLAY, ConsoleLayerState.from(
                ConsoleStateMachine.State.STREAM).inputRegion);
        ConsoleLayerState overlay = ConsoleLayerState.from(ConsoleStateMachine.State.OVERLAY);
        assertTrue(overlay.overlayVisible);
        assertEquals(InputRouter.Region.OVERLAY, overlay.inputRegion);
    }

    @Test public void recoveryRoutesOnlyToModalControls() {
        ConsoleLayerState state = ConsoleLayerState.from(ConsoleStateMachine.State.RECOVERY);

        assertFalse(state.homeVisible);
        assertTrue(state.privacyVisible);
        assertEquals(InputRouter.Region.MODAL, state.inputRegion);
    }
}
