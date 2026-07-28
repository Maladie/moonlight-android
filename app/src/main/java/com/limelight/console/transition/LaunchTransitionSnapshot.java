package com.limelight.console.transition;

public final class LaunchTransitionSnapshot {
    public final LaunchTransitionSpec spec;
    public final LaunchTransitionState state;
    public final int step;
    public final boolean overlayVisible;
    public final boolean inputBlocked;
    public final boolean operationAuthorized;
    public final boolean revealAuthorized;
    /** The stream has produced an input-ready frame but host readiness is still pending. */
    public final boolean manualRevealAvailable;
    public final boolean uncertain;
    public final String detail;

    LaunchTransitionSnapshot(LaunchTransitionSpec spec, LaunchTransitionState state,
                             int step, boolean overlayVisible, boolean inputBlocked,
                             boolean operationAuthorized, boolean revealAuthorized,
                             boolean manualRevealAvailable, boolean uncertain, String detail) {
        this.spec = spec;
        this.state = state;
        this.step = step;
        this.overlayVisible = overlayVisible;
        this.inputBlocked = inputBlocked;
        this.operationAuthorized = operationAuthorized;
        this.revealAuthorized = revealAuthorized;
        this.manualRevealAvailable = manualRevealAvailable;
        this.uncertain = uncertain;
        this.detail = detail == null ? "" : detail;
    }
}
