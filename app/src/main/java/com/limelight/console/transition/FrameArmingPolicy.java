package com.limelight.console.transition;

/**
 * Arms the fresh-frame proof a snapshot awaits. The decoder callback is armed again whenever
 * the controller still awaits a frame (a rejected frame republishes); the IDR request and the
 * fresh-frame deadline are issued once per (transition, readiness edge). Each arm carries a
 * token so a callback of a replaced decoder cannot clear the current arm. UI-thread confined.
 */
public final class FrameArmingPolicy {
    /** One decoder arm; {@link #issueIdrAndDeadline} is true once per readiness edge. */
    public static final class Arm {
        public final Object token = new Object();
        public final boolean issueIdrAndDeadline;

        Arm(boolean issueIdrAndDeadline) {
            this.issueIdrAndDeadline = issueIdrAndDeadline;
        }
    }

    private String issuedKey = "";
    private String armedKey = "";
    private Object armedToken;

    /** The arm to execute for {@code snapshot}, or null when nothing is to be armed. */
    public Arm arm(LaunchTransitionSnapshot snapshot, boolean hiddenWarmUp) {
        if (hiddenWarmUp || snapshot == null || snapshot.spec == null
                || !snapshot.overlayVisible || !snapshot.awaitingVideoFrame) return null;
        String key = snapshot.spec.id + '#' + snapshot.frameEdge;
        if (armedToken != null && key.equals(armedKey)) return null;
        boolean issue = snapshot.frameEdge > 0L && !key.equals(issuedKey);
        if (issue) issuedKey = key;
        Arm arm = new Arm(issue);
        armedKey = key;
        armedToken = arm.token;
        return arm;
    }

    /** The decoder delivered {@code token}'s frame; false when it is not the current arm. */
    public boolean rendered(Object token) {
        if (token == null || token != armedToken) return false;
        armedToken = null;
        armedKey = "";
        return true;
    }

    /** The decoder holding the arm was replaced; the edge's IDR and deadline stay issued. */
    public void decoderReplaced() {
        armedToken = null;
        armedKey = "";
    }
}
