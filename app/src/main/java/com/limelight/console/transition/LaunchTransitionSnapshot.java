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
    /** A readiness failure or window loss after a reveal, shown without covering the stream. */
    public final String notice;
    public final boolean uncertain;
    public final String detail;
    /** The game start passed the local soft threshold while still not ready; never a failure. */
    public final boolean startTakingLonger;
    /**
     * The cover is up and no frame rendered after the latest readiness edge proves the target:
     * the presentation arms one for {@link #frameEdge} (the first one also proves the transport).
     */
    public final boolean awaitingVideoFrame;
    /** The readiness edge an armed frame must carry to count as fresh. */
    public final long frameEdge;
    /** The cover a user-started operation holds over this transition (NONE: its own). */
    public final LaunchTransitionController.UserCover userCover;
    /**
     * That user cover was released over an already revealed stream and only waits for its
     * fresh frame (or that frame's deadline): nothing on it is a launch to cancel.
     */
    public final boolean userCoverReleased;
    /** Progress (0-100) of the game update this start waits on; -1 when the host has no bytes. */
    public final int updateProgress;
    /** The update is past its download: Steam patches (stages and commits) the files. */
    public final boolean updatePatching;
    /** The controller's publish order; a later publish carries a larger value. */
    public final long sequence;

    /**
     * Applies snapshots in publish order on one thread: a worker's snapshot posted to that
     * thread can arrive after a newer one published on it directly, and is then dropped.
     */
    public static final class InOrder {
        private long applied = Long.MIN_VALUE;

        /** Whether {@code snapshot} is not older than the last one admitted. */
        public boolean admit(LaunchTransitionSnapshot snapshot) {
            if (snapshot.sequence < applied) return false;
            applied = snapshot.sequence;
            return true;
        }
    }

    LaunchTransitionSnapshot(LaunchTransitionSpec spec, LaunchTransitionState state,
                             int step, boolean overlayVisible, boolean inputBlocked,
                             boolean operationAuthorized, boolean revealAuthorized,
                             boolean manualRevealAvailable, boolean uncertain, String detail,
                             boolean startTakingLonger, boolean revealPreferred,
                             String notice, boolean manuallyRevealed,
                             boolean awaitingVideoFrame, long frameEdge,
                             LaunchTransitionController.UserCover userCover,
                             boolean userCoverReleased, int updateProgress,
                             boolean updatePatching, long sequence) {
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
        this.frameEdge = frameEdge;
        this.userCover = userCover;
        this.userCoverReleased = userCoverReleased;
        this.updateProgress = updateProgress;
        this.updatePatching = updatePatching;
        this.sequence = sequence;
    }

    /**
     * The cover is the passive "returning to the library" screen, without any action. Never a
     * failure: a cover holding the input there always offers its way out (Cancel, Retry, an
     * available "Reveal now"), even over a neutral stream that settles back to the dashboard.
     */
    public boolean closingPresentation() {
        // A switch's cover presents the next game, never the closing of the previous one.
        if (userCover == LaunchTransitionController.UserCover.SWITCH
                || state == LaunchTransitionState.ERROR || state == LaunchTransitionState.TIMED_OUT) {
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
