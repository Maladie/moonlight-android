package com.limelight.ui;

import com.limelight.console.transition.LaunchTransitionController;
import com.limelight.console.transition.LaunchTransitionSnapshot;
import com.limelight.console.transition.LaunchTransitionState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The loading cover's one information card: kicker, game title (and the game a switch leaves),
 * five step segments, the current real stage with the time since the transition began, an
 * optional notice (sharing one area with the rotating message) and the actions on offer. Pure state; the view only renders it, in the same
 * place with and without artwork.
 */
public final class LoadingCoverModel {
    public enum Phase { LOADING, ERROR, LAUNCHER, WARNING, CANCELLING, CLOSING }
    /** The small uppercase line above the title. */
    public enum Kicker { STARTING, RUNNING, SWITCHING, CLOSING, FAILED }
    /** {@link #BACK} is the Cancel slot labelled "Back"; that slot always answers the Back key. */
    public enum Action { RETRY, REVEAL, CANCEL, BACK }
    /** A step segment: done is solid, the current one sweeps, attention is amber. */
    public enum StepState { DONE, CURRENT, ATTENTION, PENDING }

    public static final int STEPS = 5;

    private String title;
    private String from = "";
    private Phase phase = Phase.LOADING;
    private int step = 1;
    private String stage = "";
    private String notice = "";
    /** How much of the current step is done (0-1); -1 when unknown, the step then sweeps. */
    private float fraction = -1f;
    private String reasonCode = "";
    private boolean retry;
    private boolean reveal;
    /** A user cover that is no launch: no action (see {@link #follow}). */
    private boolean passive;
    /** The cover is over a game this transition revealed: only then "Game running". */
    private boolean running;
    private long epochMillis;

    public LoadingCoverModel(String title, long epochMillis) {
        this.title = title == null ? "" : title.trim();
        this.epochMillis = epochMillis;
    }

    public String title() { return title; }
    /** The game a switch leaves; empty otherwise. */
    public String from() { return from; }
    public Phase phase() { return phase; }
    public int step() { return step; }
    public float stepFraction() { return fraction; }
    public String stage() { return stage; }
    /** One attention line under the stage (amber dot); empty when there is none. */
    public String notice() { return notice; }
    public String reasonCode() { return reasonCode; }

    public boolean setTitle(String value) {
        if (value == null || value.trim().isEmpty()) return false;
        title = value.trim();
        return true;
    }

    /** The game a switch leaves; blank clears it. Returns whether it changed. */
    public boolean setFrom(String value) {
        String next = value == null ? "" : value.trim();
        if (next.equals(from)) return false;
        from = next;
        return true;
    }

    /** A new presentation of the cover: its time counts from {@code nowMillis}, its steps anew. */
    public void newPresentation(long nowMillis) {
        epochMillis = nowMillis;
        step = 1;
    }

    /**
     * The transition this cover shows began at {@code startedAtMillis} (the user's action, on
     * {@link android.os.SystemClock#elapsedRealtime}), possibly in another window: a switch
     * chosen on the Home, a warm-up's Play. Steps stay as they are.
     */
    public void startedAt(long startedAtMillis) {
        epochMillis = startedAtMillis;
    }

    /**
     * Progress to {@code value}: the stage is the real status, never a decorative message.
     * Within one presentation progress never goes back (TV 2026-10-16: the console's step 2 and
     * the stream's PREPARING_SESSION snapshots bounced the first segment between sweep and fill).
     */
    public boolean step(int value, String status, String attention) {
        return step(value, status, attention, -1);
    }

    /** {@code percent} 0-100 fills the current step in proportion; below 0 it sweeps. */
    public boolean step(int value, String status, String attention, int percent) {
        if (phase == Phase.CLOSING) return false;
        fraction = percent < 0 ? -1f : Math.min(100, percent) / 100f;
        phase = Phase.LOADING;
        step = Math.max(step, Math.min(STEPS, value));
        stage = status == null ? "" : status.trim();
        // The status shows once: a notice repeating the stage (the host's "waiting for the
        // game window" under the same stage) is no notice.
        notice = attention == null || attention.trim().equals(stage) ? "" : attention.trim();
        reasonCode = "";
        retry = false;
        return true;
    }

    /** A connection stage while loading; it never overwrites a failure's text. */
    public boolean connectionStage(String status, int progress) {
        if (phase != Phase.LOADING) return false;
        stage = status == null ? "" : status.trim();
        step = Math.max(step, Math.min(STEPS, progress));
        return true;
    }

    public boolean error(String title, String details, String reason, boolean allowReveal,
                         boolean allowRetry) {
        if (phase == Phase.CLOSING) return false;
        attention(Phase.ERROR, title, details, allowReveal);
        reasonCode = reason == null ? "" : reason;
        retry = allowRetry;
        return true;
    }

    public boolean launcher(String title, String details, boolean allowReveal) {
        if (phase == Phase.CLOSING) return false;
        attention(Phase.LAUNCHER, title, details, allowReveal);
        return true;
    }

    public boolean warning(String title, String details, boolean allowReveal) {
        if (phase == Phase.CLOSING) return false;
        attention(Phase.WARNING, title, details, allowReveal);
        return true;
    }

    public void cancelling(String status) {
        phase = Phase.CANCELLING;
        stage = status == null ? "" : status;
        notice = "";
        reasonCode = "";
        retry = false;
        reveal = false;
    }

    /** The quiet closing card: title and stage stay, no steps, no notice, no action. */
    public void closing(String status) {
        phase = Phase.CLOSING;
        stage = status == null ? "" : status;
        notice = "";
        reasonCode = "";
        retry = false;
        reveal = false;
    }

    /** The closing screen gives way (a failure after it brings its actions back). */
    public void leaveClosing() {
        if (phase == Phase.CLOSING) phase = Phase.LOADING;
    }

    /**
     * Follows the controller's snapshot, so this layer never blocks what it allows: the closing
     * screen lasts only while the controller presents one, and a Home return's cover or a
     * released user cover over an already revealed stream offers no action (Back there is the
     * Home, never a Cancel). Returns whether anything changed.
     */
    public boolean follow(LaunchTransitionSnapshot snapshot) {
        Phase before = phase;
        boolean wasPassive = passive;
        boolean wasRunning = running;
        running = snapshot.state == LaunchTransitionState.GAME_RUNNING;
        passive = snapshot.userCover == LaunchTransitionController.UserCover.HOME_RETURN
                || snapshot.userCoverReleased;
        if (phase == Phase.CLOSING && !snapshot.closingPresentation()) phase = Phase.LOADING;
        return phase != before || passive != wasPassive || running != wasRunning;
    }

    /** "Reveal now" while loading; a failure decides its own offer. */
    public boolean manualReveal(boolean available) {
        if (phase != Phase.LOADING || reveal == available) return false;
        reveal = available;
        return true;
    }

    /** The offered Retry was chosen: it goes away until the next failure offers it again. */
    public boolean takeRetry() {
        if (!actions().contains(Action.RETRY)) return false;
        retry = false;
        return true;
    }

    /** In display order: the primary action first, the Back-key slot last. */
    public List<Action> actions() {
        if (passive || phase == Phase.CANCELLING || phase == Phase.CLOSING) {
            return Collections.emptyList();
        }
        List<Action> actions = new ArrayList<>(3);
        if (retry && phase == Phase.ERROR) actions.add(Action.RETRY);
        if (reveal) actions.add(Action.REVEAL);
        actions.add(phase == Phase.LAUNCHER || phase == Phase.WARNING ? Action.BACK
                : Action.CANCEL);
        return Collections.unmodifiableList(actions);
    }

    public Kicker kicker() {
        if (phase == Phase.CLOSING) return Kicker.CLOSING;
        if (phase == Phase.ERROR) return Kicker.FAILED;
        if (!from.isEmpty()) return Kicker.SWITCHING;
        // "Reveal now" on offer is still a start: the game runs once it is revealed.
        return running ? Kicker.RUNNING : Kicker.STARTING;
    }

    public boolean failed() {
        return phase == Phase.ERROR || phase == Phase.LAUNCHER || phase == Phase.WARNING;
    }

    public boolean closing() { return phase == Phase.CLOSING; }

    public boolean stepsVisible() { return phase != Phase.CLOSING; }

    /** What the card's one area under the stage says. */
    public enum Line { NOTICE, NOTICE_AND_MESSAGE, MESSAGE, NONE }

    /**
     * The one area below the stage, in the same place in every stepped state; never empty. A
     * failure's notice takes it alone; while loading a notice ("taking longer", the host's
     * reason) sits above the rotating message, which never goes away. The closing card has its
     * own layout without it.
     */
    public Line line() {
        if (!stepsVisible()) return Line.NONE;
        if (notice.isEmpty() && reasonCode.isEmpty()) return Line.MESSAGE;
        return phase == Phase.LOADING ? Line.NOTICE_AND_MESSAGE : Line.NOTICE;
    }

    /** Whether the rotating message is on the card. */
    public boolean messageShown() {
        Line line = line();
        return line == Line.MESSAGE || line == Line.NOTICE_AND_MESSAGE;
    }

    /** The action row is present in every stepped state (empty is only invisible). */
    public enum Slot { SHOWN, RESERVED, NONE }

    public Slot actionsSlot() {
        return !stepsVisible() ? Slot.NONE : actions().isEmpty() ? Slot.RESERVED : Slot.SHOWN;
    }

    public StepState stepState(int index) {
        int number = index + 1;
        if (number < step) return StepState.DONE;
        if (number > step) return StepState.PENDING;
        return failed() ? StepState.ATTENTION : StepState.CURRENT;
    }

    /** "m:ss" (or "h:mm:ss") since the transition began. */
    public String elapsed(long nowMillis) {
        long seconds = Math.max(0L, nowMillis - epochMillis) / 1000L;
        long hours = seconds / 3600L;
        return hours > 0L
                ? String.format(Locale.ROOT, "%d:%02d:%02d", hours, seconds / 60L % 60L,
                seconds % 60L)
                : String.format(Locale.ROOT, "%d:%02d", seconds / 60L, seconds % 60L);
    }

    private void attention(Phase next, String title, String details, boolean allowReveal) {
        phase = next;
        stage = title == null ? "" : title;
        notice = details == null ? "" : details;
        reasonCode = "";
        retry = false;
        reveal = allowReveal;
    }
}
