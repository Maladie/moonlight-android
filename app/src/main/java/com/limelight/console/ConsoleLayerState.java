package com.limelight.console;

/** Deterministic projection of console state onto persistent layers and input routing. */
final class ConsoleLayerState {
    final boolean homeVisible;
    final boolean privacyVisible;
    final boolean overlayVisible;
    final InputRouter.Region inputRegion;

    private ConsoleLayerState(boolean homeVisible, boolean privacyVisible,
                              boolean overlayVisible, InputRouter.Region inputRegion) {
        this.homeVisible = homeVisible;
        this.privacyVisible = privacyVisible;
        this.overlayVisible = overlayVisible;
        this.inputRegion = inputRegion;
    }

    static ConsoleLayerState from(ConsoleStateMachine.State state) {
        boolean home = state == ConsoleStateMachine.State.HOME ||
                state == ConsoleStateMachine.State.CONSOLE_OVER_STREAM;
        boolean privacy = state == ConsoleStateMachine.State.CONNECTING ||
                state == ConsoleStateMachine.State.DISCONNECTING;
        boolean overlay = state == ConsoleStateMachine.State.OVERLAY;
        return new ConsoleLayerState(home, privacy, overlay,
                inputRegion(ConsoleStateMachine.inputTarget(state)));
    }

    private static InputRouter.Region inputRegion(ConsoleStateMachine.InputTarget target) {
        switch (target) {
            case HOME: return InputRouter.Region.HOME;
            case GAMEPLAY: return InputRouter.Region.GAMEPLAY;
            case OVERLAY: return InputRouter.Region.OVERLAY;
            case RECOVERY: return InputRouter.Region.MODAL;
            default: return InputRouter.Region.NONE;
        }
    }
}
