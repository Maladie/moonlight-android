package com.limelight.stream;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;

/** Review K5 round 2 (6, 7): the same parent's neutral stream adopting the running game. */
public class RetainedRunningAdoptionTest {
    private static final String TOKEN =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    private static class Adoption implements RetainedRunningAdoption.Actions {
        final List<String> calls = new ArrayList<>();
        boolean current = true, cancelled, ownerFree = true, sameProcess = true, modelAccepts = true;
        String ownerToken = "";
        int ownerPid;
        Runnable afterConfirm = () -> { };

        public boolean current() { return current; }
        public boolean cancelled() { return cancelled; }
        public boolean reserveOwner(String processToken, int processId) {
            calls.add("reserve");
            if (!ownerFree) return false;
            ownerToken = processToken;
            ownerPid = processId;
            return true;
        }
        public void confirmProcess(String processToken, int processId, Consumer<Boolean> done) {
            calls.add("confirm");
            afterConfirm.run();
            done.accept(sameProcess && processToken.equals(ownerToken) && processId == ownerPid);
        }
        public void commitGame(Consumer<Boolean> done) {
            calls.add("commit");
            done.accept(modelAccepts);
        }
        public void present() { calls.add("present"); }
        public void release() { calls.add("release"); }
        public void recover(boolean cancelled, String reason) {
            calls.add("recover:" + (cancelled ? "cancelled" : reason));
        }
    }

    private static Adoption adopt(Adoption adoption) {
        RetainedRunningAdoption.adopt(adoption, TOKEN, 7368);
        return adoption;
    }

    @Test public void adoptionObservesTheVerifiedProcessWithoutAnyHostEffect() {
        Adoption adoption = adopt(new Adoption());
        // The Actions offer no stop, start or reauthorization: none can run for an adoption.
        assertEquals(List.of("reserve", "confirm", "commit", "present"), adoption.calls);
        // The owner carries the decided process: a later same-game handoff has its identity.
        assertEquals(TOKEN, adoption.ownerToken);
        assertEquals(7368, adoption.ownerPid);
    }

    @Test public void cancellationBeforeOrDuringTheReadRecoversWithoutCommitting() {
        Adoption early = new Adoption();
        early.cancelled = true;
        assertEquals(List.of("recover:cancelled"), adopt(early).calls);

        Adoption during = new Adoption();
        during.afterConfirm = () -> during.cancelled = true;
        assertEquals(List.of("reserve", "confirm", "release", "recover:cancelled"),
                adopt(during).calls);
    }

    @Test public void ownerConflictRecovers() {
        Adoption adoption = new Adoption();
        adoption.ownerFree = false;
        assertEquals(List.of("reserve", "release",
                "recover:" + RetainedRunningAdoption.OWNER_CHANGED), adopt(adoption).calls);
    }

    @Test public void processChangedSinceTheDecisionRecoversBeforeTheModelCommits() {
        Adoption adoption = new Adoption();
        adoption.sameProcess = false;
        assertEquals(List.of("reserve", "confirm", "release",
                "recover:" + RetainedRunningAdoption.PROCESS_CHANGED), adopt(adoption).calls);
    }

    @Test public void modelUpdateRejectedRecovers() {
        Adoption adoption = new Adoption();
        adoption.modelAccepts = false;
        assertEquals(List.of("reserve", "confirm", "commit", "release",
                "recover:" + RetainedRunningAdoption.SESSION_CHANGED), adopt(adoption).calls);
    }

    @Test public void aSwitchNoLongerCurrentOnlyReleasesItsOwner() {
        Adoption adoption = new Adoption();
        adoption.afterConfirm = () -> adoption.current = false;
        assertEquals(List.of("reserve", "confirm", "release"), adopt(adoption).calls);
    }
}
