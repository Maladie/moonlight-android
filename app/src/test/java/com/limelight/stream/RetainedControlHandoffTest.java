package com.limelight.stream;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class RetainedControlHandoffTest {
    private static class Handoff implements RetainedControlHandoff.Actions {
        List<String> calls = new ArrayList<>();
        boolean current = true, commit = true, cancelled;
        String fail = "";
        long hostGeneration = 5, localGeneration = 4;
        void record(String stage) { calls.add(stage); if (fail.equals(stage)) throw new IllegalStateException(stage); }
        public boolean current() { record("current"); return current; }
        public void commitBinding(java.util.function.Consumer<Boolean> done) { record("commit"); if (commit) localGeneration = hostGeneration; done.accept(commit); }
        public void publishBinding() { record("publish"); }
        public boolean cancelled() { record("cancelled"); return cancelled; }
        public void retainNeutral() { record("neutral"); }
        public void startGame() { record("start"); }
        public void compensate() {
            record("compensate");
            if (hostGeneration == 5) hostGeneration = 0;
            if (localGeneration == 4 || localGeneration == 5) localGeneration = 0;
        }
    }

    @Test public void cancellationAfterHostAckConvergesBeforeRetainingNeutral() {
        Handoff handoff = new Handoff(); handoff.cancelled = true;
        RetainedControlHandoff.finish(handoff);
        assertEquals(List.of("current", "commit", "current", "publish", "cancelled", "neutral"), handoff.calls);
        assertEquals(handoff.hostGeneration, handoff.localGeneration);
        assertEquals(5, handoff.localGeneration);
    }

    @Test public void cancellationArrivingDuringPublicationStillConverges() {
        Handoff handoff = new Handoff() {
            @Override public void publishBinding() { super.publishBinding(); cancelled = true; }
        };
        RetainedControlHandoff.finish(handoff);
        assertEquals("neutral", handoff.calls.get(handoff.calls.size() - 1));
        assertEquals(5, handoff.localGeneration);
    }

    @Test public void commitResultForANoLongerCurrentSwitchCompensatesWithoutPublishing() {
        Handoff handoff = new Handoff() {
            @Override public void commitBinding(java.util.function.Consumer<Boolean> done) {
                super.commitBinding(committed -> { current = false; done.accept(committed); });
            }
        };
        RetainedControlHandoff.finish(handoff);
        assertEquals(List.of("current", "commit", "current", "compensate"), handoff.calls);
        assertEquals(0, handoff.localGeneration);
    }

    @Test public void liveHandoffStartsOnlyAfterBothBindingsArePublished() {
        Handoff handoff = new Handoff();
        RetainedControlHandoff.finish(handoff);
        assertEquals(List.of("current", "commit", "current", "publish", "cancelled", "start"), handoff.calls);
    }

    @Test public void everyPostAckFailureCompensatesInsteadOfLeavingOldLocalGeneration() {
        for (String stage : List.of("current", "commit", "publish", "cancelled", "neutral", "start")) {
            Handoff handoff = new Handoff(); handoff.fail = stage; handoff.cancelled = stage.equals("neutral");
            RetainedControlHandoff.finish(handoff);
            assertEquals(stage, "compensate", handoff.calls.get(handoff.calls.size() - 1));
            assertEquals(stage, 0, handoff.hostGeneration);
            assertEquals(stage, 0, handoff.localGeneration);
        }
        for (boolean stale : new boolean[] {false, true}) {
            Handoff handoff = new Handoff(); handoff.current = !stale; handoff.commit = false;
            RetainedControlHandoff.finish(handoff);
            assertEquals(List.of("current", "compensate"), stale ? handoff.calls
                    : List.of(handoff.calls.get(0), handoff.calls.get(2)));
            assertEquals(0, handoff.localGeneration);
        }
    }

    @Test public void staleChildCompensationCannotStopNewerParentGeneration() {
        Handoff handoff = new Handoff(); handoff.current = false;
        handoff.hostGeneration = 6; handoff.localGeneration = 6;
        RetainedControlHandoff.finish(handoff);
        assertEquals(List.of("current", "compensate"), handoff.calls);
        assertEquals(6, handoff.hostGeneration);
        assertEquals(6, handoff.localGeneration);
    }
}
