package com.limelight.session;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Dedicated model thread: replies, effects, re-entrancy, ordering and stale results. */
public class SessionModelAsyncTest {
    private final SessionModelDriver driver = new SessionModelDriver();
    private final SessionModel model = driver.model;
    private final List<String> trace = new ArrayList<>();

    private class Owner implements SessionModel.Controller {
        boolean parkResult = true;
        boolean completeSwitchInline;
        SessionModel.SwitchCallback switchCompletion;

        @Override public String getExecutionProfileId() { return "parent"; }
        @Override public boolean isRetainedTransportLive() { return true; }
        @Override public SessionModel.ParkOutcome parkRetainedTransport() {
            trace.add("owner.park");
            return parkResult ? SessionModel.ParkOutcome.PARKED : SessionModel.ParkOutcome.STOPPED;
        }
        @Override public void disconnectRetainedStream(
                SessionModel.TerminationCallback completion) { }
        @Override public void terminateRetainedSession(
                SessionModel.TerminationCallback completion) { }
        @Override public void switchGame(SessionModel.SwitchRequest request,
                                         SessionModel.SwitchCallback completion) {
            trace.add("owner.switch");
            switchCompletion = completion;
            // Like StreamRuntime without a presentation: completes before returning.
            if (completeSwitchInline) {
                completion.complete(SessionModel.SwitchOutcome.FAILED, "presentation_detached");
            }
        }
    }

    private void runModel() {
        while (!driver.modelQueue.isEmpty()) driver.modelQueue.poll().run();
    }

    private void runMainOnce() {
        driver.mainQueue.poll().run();
    }

    private Owner liveHome(String sessionId) {
        Owner owner = new Owner();
        driver.enterHome(owner, sessionId, "host", "parent", 7, "game-a");
        return owner;
    }

    @Test public void replyArrivesOnTheMainThreadAfterItsSnapshotWasPublished() {
        Owner owner = new Owner();
        SessionModel.State[] seen = new SessionModel.State[1];
        model.dispatch(new SessionEvent.PreparingBegun(owner, "s", "host", "parent", 7, "",
                "t", 1L), begun -> seen[0] = model.snapshot().state);

        runModel();
        assertNull(seen[0]);                       // nothing runs on the model thread for the caller
        assertEquals(SessionModel.State.PREPARING, model.snapshot().state);
        while (!driver.mainQueue.isEmpty()) runMainOnce();
        assertEquals(SessionModel.State.PREPARING, seen[0]);
    }

    @Test public void readersSeeTheLastPublishedStateUntilTheModelAppliesAnEvent() {
        liveHome("s");
        model.post(new SessionEvent.Parked("s"));
        assertEquals(SessionModel.State.HOME_LIVE, model.snapshot().state);
        runModel();
        assertEquals(SessionModel.State.PARKED_LIVE, model.snapshot().state);
    }

    @Test public void synchronousOwnerSwitchCompletionIsPostedBackNotRunInline() {
        Owner owner = liveHome("s");
        owner.completeSwitchInline = true;
        List<String> caller = new ArrayList<>();
        model.dispatch(new SessionEvent.SwitchRequested("host", "parent", "parent", false, 7,
                "game-b", "B", "Stream", "", 0L, () -> false,
                (outcome, error) -> caller.add("completion:" + outcome)),
                started -> caller.add("reply:" + started));

        runModel();
        assertTrue(caller.isEmpty());
        assertTrue(trace.isEmpty());               // the owner effect runs on main, not the model
        driver.drain();

        assertEquals(List.of("reply:STARTED", "completion:FAILED"), caller);
        assertEquals(List.of("owner.switch"), trace);
        assertTrue(driver.canSwitchGame("host", "parent", 7));   // the lock was released
    }

    @Test public void lateSwitchCompletionCannotReleaseANewerSwitchLock() {
        Owner owner = liveHome("s");
        driver.switchGame("host", "parent", "parent", false, 7, "game-b", "B", "Stream", "",
                () -> false, (outcome, error) -> { });
        SessionModel.SwitchCallback first = owner.switchCompletion;
        first.complete(SessionModel.SwitchOutcome.FAILED, "");
        driver.drain();
        driver.switchGame("host", "parent", "parent", false, 7, "game-c", "C", "Stream", "",
                () -> false, (outcome, error) -> { });

        first.complete(SessionModel.SwitchOutcome.FAILED, "late");
        driver.drain();

        assertFalse(driver.canSwitchGame("host", "parent", 7));  // the newer lock is held
    }

    @Test public void parkResultAfterANewerSessionIsRejectedInTheTransition() {
        Owner owner = liveHome("s1");
        Boolean[] parked = new Boolean[1];
        model.dispatch(new SessionEvent.ParkRequested("s1", 0L), result -> parked[0] = result);
        runModel();                                 // effect queued on main, not yet run
        driver.enterHome(new Owner(), "s2", "host", "parent", 7, "game-b");

        driver.drain();                             // owner.park ran; its result is stale

        assertEquals(Boolean.FALSE, parked[0]);
        assertEquals("s2", model.snapshot().streamSessionId);
        assertEquals(SessionModel.State.HOME_LIVE, model.snapshot().state);
        assertTrue(trace.contains("owner.park"));
    }

    @Test public void reauthorizationCommitsOnlyWhenTheConnectionResultMatchesCurrentBindings() {
        Owner owner = liveHome("s");
        driver.bindControlGeneration(owner, "s", 4L);
        AtomicInteger connection = new AtomicInteger(4);
        Boolean[] committed = new Boolean[1];
        model.dispatch(new SessionEvent.Reauthorize(owner, "s", "parent", "child", 4L, 5L,
                () -> connection.compareAndSet(4, 5)), result -> committed[0] = result);
        runModel();
        assertEquals(4, connection.get());          // the connection commit is a main effect
        assertEquals("parent", model.snapshot().profileId);

        // A newer transition lands between the effect request and its result.
        model.post(new SessionEvent.TransportLost("s"));
        driver.drain();

        assertEquals(5, connection.get());          // the transport side committed
        assertEquals(Boolean.FALSE, committed[0]);  // the model rejected the stale result
        assertEquals(4L, model.snapshot().controlGeneration);
        assertEquals(SessionModel.State.RECONNECT_REQUIRED, model.snapshot().state);
    }

    @Test public void reauthorizationWithUnchangedBindingsCommitsBothOwners() {
        Owner owner = liveHome("s");
        driver.bindControlGeneration(owner, "s", 4L);
        AtomicInteger connection = new AtomicInteger(4);

        assertTrue(driver.commitReauthorization(owner, "s", "parent", "child", 4L, 5L,
                () -> connection.compareAndSet(4, 5)));
        assertEquals(5, connection.get());
        assertEquals(5L, model.snapshot().controlGeneration);
        assertEquals("child", model.snapshot().profileId);
        assertFalse(driver.commitReauthorization(owner, "s", "parent", "child", 4L, 6L,
                () -> { fail("stale generation reached the connection"); return true; }));
    }

    @Test public void homeFrameResultForAReplacedAttemptIsNotAccepted() {
        Owner owner = new Owner() {
            @Override public boolean preparingHomeFrameSubmitted(SessionModel.Snapshot preparing) {
                return true;
            }
        };
        assertTrue(driver.beginPreparing(owner, "s", "host", "parent", 7, "", "t1", 1L));
        SessionModel.Snapshot preparing = driver.snapshot();
        Boolean[] accepted = new Boolean[1];
        model.dispatch(new SessionEvent.HomeFrameSubmitted(preparing),
                result -> accepted[0] = result);
        runModel();
        model.post(new SessionEvent.PreparingCancelled(owner, preparing));
        runModel();
        driver.drain();

        assertEquals(Boolean.FALSE, accepted[0]);
        assertFalse(model.isPreparingHomeFrameAccepted("s", "host", 7, "", "t1", 1L));
    }

    @Test public void terminationCompletionReachesTheCallerOnlyAfterTheModelCleared() {
        SessionModel.TerminationCallback[] owned = new SessionModel.TerminationCallback[1];
        Owner owner = new Owner() {
            @Override public void terminateRetainedSession(
                    SessionModel.TerminationCallback completion) {
                owned[0] = completion;
            }
        };
        driver.enterHome(owner, "s", "host", "parent", 7, "game");
        SessionModel.State[] atCompletion = new SessionModel.State[1];
        assertEquals(SessionModel.TerminationResult.STARTED, driver.terminate("s",
                success -> atCompletion[0] = model.snapshot().state));

        owned[0].complete(true);
        assertNull(atCompletion[0]);
        driver.drain();
        assertEquals(SessionModel.State.NONE, atCompletion[0]);
    }

    @Test public void awaitIsRefusedOnTheMainThread() {
        SessionModel onMain = new SessionModel(Runnable::run, Runnable::run, () -> true,
                new MemoryPreferences(), new MemoryPreferences(), new MemoryPreferences());
        try {
            onMain.await(new SessionEvent.Cleared("s"));
            fail("the main thread must never wait for the model");
        } catch (IllegalStateException expected) {
            // Main-thread callers use dispatch with a reply instead.
        }
    }

    @Test public void reconnectRecordAfterProcessDeathIsNotALiveConnection() {
        driver.resume.values.put("pending", true);
        driver.resume.values.put("profileId", "parent");
        driver.resume.values.put("pcUuid", "host");
        driver.resume.values.put("appId", 7);
        driver.resume.values.put("streamSessionId", "s");
        SessionModel restarted = new SessionModel(Runnable::run, Runnable::run, () -> false,
                driver.resume, driver.suspended, driver.sleep);

        assertEquals("s", restarted.records().pending.streamSessionId);
        assertEquals(SessionModel.State.NONE, restarted.state());
        assertFalse(restarted.hasRetainedSession());
        assertFalse(restarted.canResumeInstantly());
    }

    @Test public void reconnectRelinquishedClearsOnlyTheCancelledAttempt() {
        Owner owner = new Owner();
        assertTrue(driver.beginPreparing(owner, "s", "host", "parent", 7, "", "t", 1L));
        SessionModel.Snapshot preparing = driver.snapshot();
        assertTrue(driver.markPreparingReconnectRequired(owner, "s", "host", 7, "t", 1L));

        driver.enterHome(owner, "s", "host", "parent", 8, "other");
        assertFalse(driver.call(new SessionEvent.ReconnectRelinquished(preparing)));
        assertEquals(SessionModel.State.HOME_LIVE, driver.state());
    }

    /** A transition that throws, as a model bug would. */
    private static final class Throwing extends SessionEvent.BooleanEvent {
        @Override void apply(SessionModel m, SessionModel.Reply<Boolean> reply) {
            throw new IllegalStateException("model bug");
        }
    }

    @Test public void aThrowingTransitionFailsItsOperationInsteadOfLeavingTheCallerWaiting() {
        Boolean[] answer = new Boolean[1];
        model.dispatch(new Throwing(), result -> answer[0] = result);
        driver.drain();
        assertEquals(Boolean.FALSE, answer[0]);
        assertEquals(SessionModel.TerminationResult.NO_CONTROLLER,
                new SessionEvent.TerminateRequested("s", null).failure());
        assertEquals(SessionModel.SwitchResult.NOT_ELIGIBLE, new SessionEvent.SwitchRequested(
                "h", "p", "p", false, 1, "g", "", "", "", 0L, null, null).failure());
    }

    @Test public void awaitingWorkerIsReleasedWhenTheTransitionThrows() throws Exception {
        java.util.concurrent.ExecutorService thread =
                java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            SessionModel threaded = new SessionModel(thread::execute, Runnable::run, () -> false,
                    new MemoryPreferences(), new MemoryPreferences(), new MemoryPreferences());
            java.util.concurrent.Future<Boolean> worker = java.util.concurrent.Executors
                    .newSingleThreadExecutor().submit(() -> threaded.await(new Throwing()));
            assertEquals(Boolean.FALSE, worker.get(5, java.util.concurrent.TimeUnit.SECONDS));

            // Awaiting from inside the model thread is refused and fails that operation.
            Boolean[] nested = new Boolean[1];
            java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
            threaded.dispatch(new SessionEvent.BooleanEvent() {
                @Override void apply(SessionModel m, SessionModel.Reply<Boolean> reply) {
                    reply.accept(m.await(new SessionEvent.Cleared("s")));
                }
            }, result -> { nested[0] = result; done.countDown(); });
            assertTrue(done.await(5, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(Boolean.FALSE, nested[0]);
        } finally {
            thread.shutdownNow();
        }
    }

    @Test public void throwingOwnerEffectAnswersFalse() {
        Owner owner = new Owner() {
            @Override public SessionModel.ParkOutcome parkRetainedTransport() {
                throw new IllegalStateException("owner bug");
            }
        };
        driver.enterHome(owner, "s", "host", "parent", 7, "game");
        assertFalse(driver.parkForBackground("s"));
        assertEquals(SessionModel.State.RECONNECT_REQUIRED, driver.state());
    }

    @Test public void restoredParkedStreamIsParkedAgainOnTheNextBackgroundRequest() {
        Owner owner = liveHome("s");
        assertTrue(driver.parkForBackground("s"));
        assertEquals(SessionModel.State.PARKED_LIVE, driver.state());
        assertEquals(List.of("owner.park"), trace);

        // The Game restored the decoder: the stream is shown again, not parked.
        driver.post(new SessionEvent.PresentationRestored(owner, "s"));
        assertEquals(SessionModel.State.HOME_LIVE, driver.state());

        assertTrue(driver.parkForBackground("s"));
        assertEquals(List.of("owner.park", "owner.park"), trace);
        assertEquals(SessionModel.State.PARKED_LIVE, driver.state());
    }

    @Test public void parkedStateStillAsksItsOwnerToPark() {
        liveHome("s");
        driver.markParked("s");
        assertTrue(driver.parkForBackground("s"));
        assertEquals(List.of("owner.park"), trace);
    }

    @Test public void restoreForAnotherSessionOrOwnerDoesNotUnpark() {
        Owner owner = liveHome("s");
        driver.markParked("s");
        driver.post(new SessionEvent.PresentationRestored(owner, "other"));
        driver.post(new SessionEvent.PresentationRestored(new Owner(), "s"));
        assertEquals(SessionModel.State.PARKED_LIVE, driver.state());
        assertEquals(0L, driver.snapshot().presentationEpoch);
    }

    @Test public void lateBeginForAGoneGameIsCancelledSoTheHostIsNotBlocked() {
        Owner stopped = new Owner() {
            @Override public boolean isRetainedTransportLive() { return false; }
        };
        assertTrue(driver.beginPreparing(stopped, "s", "host", "parent", 7, "", "t", 1L));
        // The Game was destroyed before the reply: it cancels its own begun attempt.
        driver.post(new SessionEvent.PreparingCancelled(stopped, driver.snapshot()));
        assertEquals(SessionModel.State.NONE, driver.state());
        assertTrue(driver.beginPreparing(new Owner(), "s2", "host", "parent", 7, "", "t2", 1L));
    }

    @Test public void anErrorInATransitionReleasesTheCallerAndIsRethrown() {
        Boolean[] answer = new Boolean[1];
        model.dispatch(new SessionEvent.BooleanEvent() {
            @Override void apply(SessionModel m, SessionModel.Reply<Boolean> reply) {
                m.effect(() -> trace.add("dropped effect"));
                throw new AssertionError("fatal");
            }
        }, result -> answer[0] = result);
        try {
            driver.drain();
            fail("the Error must propagate after the caller was answered");
        } catch (AssertionError expected) {
            assertEquals("fatal", expected.getMessage());
        }
        driver.drain();
        assertEquals(Boolean.FALSE, answer[0]);
        assertFalse(trace.contains("dropped effect"));
    }

    @Test public void homeStoppingAfterTheGameShowedTheStreamAgainDoesNotPark() {
        Owner owner = liveHome("s");
        long homeEpoch = driver.snapshot().presentationEpoch;   // Home visible
        assertTrue(driver.parkForBackground("s"));              // TV Home from Home parks
        driver.post(new SessionEvent.PresentationRestored(owner, "s"));   // back to the game
        trace.clear();

        assertFalse(driver.call(new SessionEvent.ParkRequested("s", homeEpoch)));  // Home.onStop
        assertTrue(trace.isEmpty());
        assertEquals(SessionModel.State.HOME_LIVE, driver.state());

        assertTrue(driver.parkForBackground("s"));              // TV Home from the game
        assertEquals(java.util.List.of("owner.park"), trace);
    }

    @Test public void ownerRefusalWhileShownKeepsTheLiveRetainedStream() {
        Owner shown = new Owner() {
            @Override public SessionModel.ParkOutcome parkRetainedTransport() {
                trace.add("owner.park");
                return SessionModel.ParkOutcome.REFUSED;
            }
        };
        driver.enterHome(shown, "s", "host", "parent", 7, "game");
        assertFalse(driver.parkForBackground("s"));
        assertEquals(SessionModel.State.HOME_LIVE, driver.state());
        assertTrue(driver.canResumeInstantly());
    }
}
