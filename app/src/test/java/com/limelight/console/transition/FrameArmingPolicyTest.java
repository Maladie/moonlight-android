package com.limelight.console.transition;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class FrameArmingPolicyTest {
    private static LaunchTransitionSnapshot awaiting(String id, long edge, boolean overlayVisible,
                                                     boolean awaitingVideoFrame) {
        LaunchTransitionSpec spec = new LaunchTransitionSpec(id, "host-1",
                LaunchTransitionType.GAME, 42, "11111111-2222-3333-4444-555555555555", 1000L);
        return new LaunchTransitionSnapshot(spec, LaunchTransitionState.GAME_READY, 4,
                overlayVisible, overlayVisible, false, false, false, false, "", false, false,
                "", false, awaitingVideoFrame, edge, LaunchTransitionController.UserCover.NONE,
                false, -1, false, 0L);
    }

    private static LaunchTransitionSnapshot awaiting(String id, long edge) {
        return awaiting(id, edge, true, true);
    }

    @Test
    public void readinessEdgeIssuesIdrAndDeadlineOnceWhileRejectedFramesReArmTheCallback() {
        FrameArmingPolicy policy = new FrameArmingPolicy();
        FrameArmingPolicy.Arm first = policy.arm(awaiting("t1", 1L), false);
        assertNotNull(first);
        assertTrue(first.issueIdrAndDeadline);
        assertNull("an armed edge republished arms nothing", policy.arm(awaiting("t1", 1L), false));

        // The controller rejects the frame and republishes: only the callback is armed again.
        assertTrue(policy.rendered(first.token));
        FrameArmingPolicy.Arm again = policy.arm(awaiting("t1", 1L), false);
        assertNotNull(again);
        assertFalse("no second IDR or deadline for the same edge", again.issueIdrAndDeadline);

        assertTrue(policy.rendered(again.token));
        assertTrue("a new edge issues again", policy.arm(awaiting("t1", 2L), false)
                .issueIdrAndDeadline);
        assertTrue("a new transition issues again", policy.arm(awaiting("t2", 1L), false)
                .issueIdrAndDeadline);
    }

    @Test
    public void transportEdgeArmsTheCallbackWithoutIdrOrDeadline() {
        FrameArmingPolicy.Arm arm = new FrameArmingPolicy().arm(awaiting("t1", 0L), false);
        assertNotNull(arm);
        assertFalse(arm.issueIdrAndDeadline);
    }

    @Test
    public void replacedDecoderCallbackCannotClearTheNewArm() {
        FrameArmingPolicy policy = new FrameArmingPolicy();
        FrameArmingPolicy.Arm old = policy.arm(awaiting("t1", 1L), false);
        policy.decoderReplaced();
        FrameArmingPolicy.Arm current = policy.arm(awaiting("t1", 1L), false);
        assertNotNull(current);
        assertFalse(current.issueIdrAndDeadline);

        assertFalse("the old decoder's frame is not the proof", policy.rendered(old.token));
        assertNull("the new arm still stands", policy.arm(awaiting("t1", 1L), false));
        assertTrue(policy.rendered(current.token));
    }

    @Test
    public void nothingArmsDuringHiddenWarmUpOrWithoutAnAwaitingCover() {
        FrameArmingPolicy policy = new FrameArmingPolicy();
        assertNull(policy.arm(awaiting("t1", 1L), true));
        assertNull("revealed", policy.arm(awaiting("t1", 1L, false, false), false));
        assertNull("not awaiting", policy.arm(awaiting("t1", 1L, true, false), false));
        assertNull(policy.arm(null, false));
        assertTrue("the edge was never issued", policy.arm(awaiting("t1", 1L), false)
                .issueIdrAndDeadline);
    }
}
