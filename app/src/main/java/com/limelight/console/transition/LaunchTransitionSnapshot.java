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
    /**
     * The cover is up and readiness (automatic or manual) still needs a frame rendered after
     * the latest readiness edge: the presentation must arm one, or "Reveal now" never appears.
     */
    public final boolean awaitingVideoFrame;

    LaunchTransitionSnapshot(LaunchTransitionSpec spec, LaunchTransitionState state,
                             int step, boolean overlayVisible, boolean inputBlocked,
                             boolean operationAuthorized, boolean revealAuthorized,
                             boolean manualRevealAvailable, boolean uncertain, String detail,
                             boolean startTakingLonger, boolean revealPreferred,
                             String notice, boolean manuallyRevealed,
                             boolean awaitingVideoFrame) {
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
        this.awaitingVideoFrame = awaitingVideoFrame;
    }

    /**
     * The cover is the passive "returning to the library" screen, without any action. Never a
     * failure: a cover holding the input there always offers its way out (Cancel, Retry, an
     * available "Reveal now"), even over a neutral stream that settles back to the dashboard.
     */
    public boolean closingPresentation() {
        if (state == LaunchTransitionState.ERROR || state == LaunchTransitionState.TIMED_OUT) {
            return false;
        }
        return state == LaunchTransitionState.GAME_STOPPING
                || state == LaunchTransitionState.PLAYNITE_RETURNING
                || state == LaunchTransitionState.PLAYNITE_STOPPING
                || state == LaunchTransitionState.CLOSING_STREAM
                || state == LaunchTransitionState.RETURNING_TO_DASHBOARD
                || spec.type == LaunchTransitionType.GAME_CONNECTION
                && spec.playniteGameId.isEmpty();
    }
}
