package com.limelight.console.transition;

public final class LaunchTransitionSnapshot {
    public final LaunchTransitionSpec spec;
    public final LaunchTransitionState state;
    public final int step;
    public final boolean overlayVisible;
    public final boolean inputBlocked;
    public final boolean operationAuthorized;
    public final boolean revealAuthorized;
    /**
     * The stream has produced an input-ready frame while host readiness is pending, failed or
     * uncertain: the cosmetic cover can always be removed by the user.
     */
    public final boolean manualRevealAvailable;
    /** The failure is likely a launcher or off-screen window: "Reveal now" is the default focus. */
    public final boolean revealPreferred;
    /** The user chose "Reveal now" in this attempt; the cover does not return within it. */
    public final boolean manuallyRevealed;
    /** A readiness failure after a manual reveal, shown without covering the stream. */
    public final String notice;
    public final boolean uncertain;
    public final String detail;
    /** The game start passed the local soft threshold while still not ready; never a failure. */
    public final boolean startTakingLonger;

    LaunchTransitionSnapshot(LaunchTransitionSpec spec, LaunchTransitionState state,
                             int step, boolean overlayVisible, boolean inputBlocked,
                             boolean operationAuthorized, boolean revealAuthorized,
                             boolean manualRevealAvailable, boolean uncertain, String detail,
                             boolean startTakingLonger, boolean revealPreferred,
                             String notice, boolean manuallyRevealed) {
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
        this.startTakingLonger = startTakingLonger;
        this.revealPreferred = revealPreferred;
        this.notice = notice == null ? "" : notice;
        this.manuallyRevealed = manuallyRevealed;
    }
}
