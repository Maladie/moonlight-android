package com.limelight.stream;

import com.limelight.session.SessionEvent;
import com.limelight.session.SessionModel;
import com.limelight.session.SessionModelDriver;

import com.limelight.nvstream.NvConnectionListener;
import com.limelight.nvstream.av.video.VideoDecoderRenderer;
import com.limelight.nvstream.input.KeyboardPacket;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class StreamRuntimeTest {
    private FakePlatform platform;
    private SessionModelDriver m;

    @Before public void reset() {
        StreamRuntime.resetForTests();
        TransportStopCoordinator.resetForTests();
        platform = new FakePlatform();
        m = platform.driver;
    }

    @After public void tearDown() {
        StreamRuntime.resetForTests();
        TransportStopCoordinator.resetForTests();
    }

    @Test public void wifiLocksAreHeldExactlyForTheTransportLifetime() {
        FakeTransport transport = new FakeTransport();
        StreamRuntime runtime = connected("s1", transport);
        assertEquals(1, platform.locksAcquired.get());
        assertEquals(0, platform.locksReleased.get());

        AtomicReference<TransportStopCoordinator.Result> result = new AtomicReference<>();
        runtime.stop(result::set);
        runtime.stop(r -> { });

        assertEquals(TransportStopCoordinator.Result.STOPPED, result.get());
        assertEquals(1, transport.stops.get());
        assertEquals(1, platform.locksReleased.get());
        assertNull(StreamRuntime.forSession("s1"));
    }

    @Test public void failedPredecessorStopNeverStartsTheSuccessorNative() {
        FakeTransport first = new FakeTransport();
        first.failStop = true;
        QueueExecutor stops = new QueueExecutor();
        platform.stopExecutor = stops;
        StreamRuntime old = connected("s1", first);
        assertNull("one live transport per process",
                StreamRuntime.create("s2", "p", new FakeTransport(), platform));

        FakeTransport second = new FakeTransport();
        old.stop(r -> { });
        StreamRuntime next = StreamRuntime.create("s2", "p", second, platform);
        assertNotNull(next);
        next.start();
        assertEquals("successor waits for the predecessor result", 0, second.starts.get());

        stops.runAll();
        assertEquals(1, first.stops.get());
        assertEquals(0, second.starts.get());
        assertEquals(2, platform.locksReleased.get());
    }

    @Test public void successorStartsOnlyAfterPredecessorStopped() {
        FakeTransport first = new FakeTransport();
        QueueExecutor stops = new QueueExecutor();
        platform.stopExecutor = stops;
        StreamRuntime old = connected("s1", first);
        old.stop(r -> { });
        FakeTransport second = new FakeTransport();
        StreamRuntime next = StreamRuntime.create("s2", "p", second, platform);
        next.start();
        assertEquals(0, second.starts.get());
        stops.runAll();
        assertEquals(1, first.stops.get());
        assertEquals(1, second.starts.get());
        assertSame(next, StreamRuntime.forSession("s2"));
    }

    @Test public void terminationWithoutPresentationRequiresReconnectAndEndsService() {
        FakeTransport transport = new FakeTransport();
        StreamRuntime runtime = connected("s1", transport);
        assertTrue(StreamRuntime.serviceEstablished("s1", runtime.generation()));
        m.enterHome(runtime, "s1", "host", "p", 7, "g");

        runtime.connectionTerminated(-1);

        assertEquals(SessionModel.State.RECONNECT_REQUIRED,
                m.state());
        assertEquals(Collections.singletonList("s1"), platform.reconnectSaved);
        assertEquals(1, transport.stops.get());
        assertEquals(1, platform.locksReleased.get());
        assertEquals(Collections.singletonList("lost:s1"), platform.serviceEnds);
    }

    @Test public void attachedPresentationReceivesTerminationInstead() {
        FakeTransport transport = new FakeTransport();
        StreamRuntime runtime = connected("s1", transport);
        FakePresentation presentation = new FakePresentation();
        runtime.attach(presentation);

        runtime.connectionTerminated(-1);

        assertEquals(1, presentation.terminated.get());
        assertEquals(0, transport.stops.get());
    }

    @Test public void foregroundServiceIsRequestedOnceOnlyWhileForeground() {
        platform.foreground = false;
        StreamRuntime late = connected("s1", new FakeTransport());
        assertEquals(0, platform.serviceRequests.get());
        assertEquals(StreamRuntime.ForegroundService.NONE, late.foregroundService());
        late.stop(r -> { });

        platform.foreground = true;
        StreamRuntime runtime = connected("s2", new FakeTransport());
        runtime.connectionStarted();
        assertEquals(1, platform.serviceRequests.get());
        assertFalse("stale generation", StreamRuntime.serviceEstablished("s2", 999L));
        assertTrue(StreamRuntime.serviceEstablished("s2", runtime.generation()));
        assertFalse("single acknowledgment", StreamRuntime.serviceEstablished(
                "s2", runtime.generation()));
    }

    @Test public void refusedServiceMakesParkAnHonestStop() {
        platform.serviceAccepted = false;
        FakeTransport transport = new FakeTransport();
        StreamRuntime runtime = connected("s1", transport);
        m.enterHome(runtime, "s1", "host", "p", 7, "g");

        assertFalse(m.parkForBackground("s1"));

        assertEquals(1, transport.stops.get());
        assertEquals(SessionModel.State.RECONNECT_REQUIRED,
                m.state());
        assertEquals(0, platform.parks.get());
        assertEquals(1, platform.locksReleased.get());
    }

    @Test public void honestStopFinishesAnAttachedPresentation() {
        platform.serviceAccepted = false;
        FakeTransport transport = new FakeTransport();
        StreamRuntime runtime = connected("s1", transport);
        FakePresentation presentation = new FakePresentation();
        runtime.attach(presentation);
        m.enterHome(runtime, "s1", "host", "p", 7, "g");

        assertFalse(m.parkForBackground("s1"));

        assertEquals(1, transport.stops.get());
        assertEquals(1, presentation.disconnected.get());
    }

    @Test public void parkWithEstablishedServiceKeepsTransport() {
        FakeTransport transport = new FakeTransport();
        StreamRuntime runtime = connected("s1", transport);
        assertTrue(StreamRuntime.serviceEstablished("s1", runtime.generation()));
        m.enterHome(runtime, "s1", "host", "p", 7, "g");

        assertTrue(m.parkForBackground("s1"));

        assertEquals(0, transport.stops.get());
        assertEquals(1, platform.parks.get());
        assertEquals(1, transport.decoderDetaches.get());
        assertEquals(0f, transport.volume, 0f);
        assertEquals(0, platform.serviceRequests.get() - 1);
        assertEquals(SessionModel.State.PARKED_LIVE,
                m.state());
    }

    @Test public void retainedTransportSurvivesPresentationLoss() {
        FakeTransport transport = new FakeTransport();
        StreamRuntime runtime = connected("s1", transport);
        assertTrue(StreamRuntime.serviceEstablished("s1", runtime.generation()));
        m.enterHome(runtime, "s1", "host", "p", 7, "g");
        StreamRuntime.Attachment attachment = runtime.attach(new FakePresentation());
        platform.input.runAll();

        runtime.presentationLost(attachment, false);
        platform.input.runAll();

        assertEquals(0, transport.stops.get());
        assertFalse(runtime.hasPresentation());
        assertFalse(m.hasAttachedPresentation());
        assertTrue(m.canResumeInstantly());
        assertSame(runtime, StreamRuntime.forSession("s1"));

        StreamRuntime.Attachment next = runtime.attach(new FakePresentation());
        assertTrue(runtime.isCurrent(next));
        assertFalse("old identity is fenced", runtime.attachDecoder(attachment, null));
    }

    @Test public void nonRetainedTransportStopsOnPresentationLoss() {
        FakeTransport transport = new FakeTransport();
        StreamRuntime runtime = connected("s1", transport);
        StreamRuntime.Attachment attachment = runtime.attach(new FakePresentation());

        runtime.presentationLost(attachment, false);

        assertEquals(1, transport.stops.get());
        assertTrue(platform.reconnectSaved.isEmpty());
    }

    @Test public void detachReturnsWithoutWaitingForABlockedSendAndReleasesHeldInput()
            throws Exception {
        FakeTransport transport = new FakeTransport();
        StreamRuntime runtime = connected("s1", transport);
        StreamRuntime.Attachment attachment = runtime.attach(new FakePresentation());
        platform.input.runAll();
        attachment.input.sendKeyboardInput((short) 0x41, KeyboardPacket.KEY_DOWN,
                (byte) 0, (byte) 0);
        attachment.input.sendMouseButtonDown((byte) 1);

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        transport.blockControllerSend = () -> {
            entered.countDown();
            await(release);
        };
        Thread sender = new Thread(() -> attachment.input.sendControllerInput(
                (short) 0, (short) 1, 0x1000, (byte) 0, (byte) 0,
                (short) 0, (short) 0, (short) 0, (short) 0));
        sender.start();
        assertTrue(entered.await(2, TimeUnit.SECONDS));

        runtime.detach(attachment);  // lifecycle thread: must not wait for the latch
        assertEquals(StreamRuntime.ReleaseBarrier.PENDING, runtime.releaseBarrier());
        attachment.input.sendControllerInput((short) 0, (short) 1, 0x2000, (byte) 0,
                (byte) 0, (short) 0, (short) 0, (short) 0, (short) 0);

        Thread releaser = new Thread(platform.input::runAll);
        releaser.start();
        release.countDown();
        sender.join(2000);
        releaser.join(2000);

        assertEquals(StreamRuntime.ReleaseBarrier.DONE, runtime.releaseBarrier());
        List<String> packets = transport.packets();
        int last = packets.size();
        assertTrue(packets.contains("controller:0:4096"));
        assertFalse("post-detach send rejected", packets.contains("controller:0:8192"));
        assertTrue(packets.subList(packets.indexOf("controller:0:4096") + 1, last)
                .containsAll(java.util.Arrays.asList("controller:0:0", "key-up:65",
                        "mouse-up:1")));
        assertEquals(packets.indexOf("controller:0:4096"),
                packets.lastIndexOf("controller:0:4096"));
    }

    @Test public void newAttachmentInputStaysClosedUntilReleaseCompletes() {
        FakeTransport transport = new FakeTransport();
        StreamRuntime runtime = connected("s1", transport);
        StreamRuntime.Attachment first = runtime.attach(new FakePresentation());
        platform.input.runAll();
        first.input.sendMouseButtonDown((byte) 1);

        runtime.detach(first);
        StreamRuntime.Attachment second = runtime.attach(new FakePresentation());
        second.input.sendMouseButtonDown((byte) 2);
        assertFalse(transport.packets().contains("mouse-down:2"));

        platform.input.runAll();
        second.input.sendMouseButtonDown((byte) 2);
        List<String> packets = transport.packets();
        assertTrue(packets.indexOf("mouse-up:1") < packets.indexOf("mouse-down:2"));
    }

    @Test public void stopWithStuckReleaseAbandonsTheBarrier() {
        FakeTransport transport = new FakeTransport();
        StreamRuntime runtime = connected("s1", transport);
        StreamRuntime.Attachment first = runtime.attach(new FakePresentation());
        platform.input.runAll();
        runtime.detach(first);
        StreamRuntime.Attachment second = runtime.attach(new FakePresentation());

        runtime.stop(r -> { });
        platform.input.runAll();

        assertEquals(StreamRuntime.ReleaseBarrier.ABANDONED, runtime.releaseBarrier());
        second.input.sendMouseButtonDown((byte) 3);
        assertFalse(transport.packets().contains("mouse-down:3"));
    }

    @Test public void headlessSwitchIsRejectedWithoutTouchingTheTransport() {
        FakeTransport transport = new FakeTransport();
        StreamRuntime runtime = connected("s1", transport);
        AtomicReference<String> error = new AtomicReference<>();
        runtime.switchGame(null, (outcome, reason) -> error.set(outcome + ":" + reason));
        assertEquals("FAILED:presentation_detached", error.get());
        assertEquals(0, transport.stops.get());
    }

    @Test public void serviceExpiryStopsOnlyTheExactGeneration() {
        FakeTransport transport = new FakeTransport();
        StreamRuntime runtime = connected("s1", transport);
        FakePresentation presentation = new FakePresentation();
        runtime.attach(presentation);
        StreamRuntime.serviceEnded("s1", runtime.generation() + 1);
        assertEquals(0, transport.stops.get());
        StreamRuntime.serviceEnded("s1", runtime.generation());
        assertEquals(1, transport.stops.get());
        assertEquals(1, presentation.disconnected.get());
        assertEquals(Collections.singletonList("s1"), platform.reconnectCleared);
    }

    @Test public void parkIsRefusedWhileThePresentationShowsTheStream() {
        FakeTransport transport = new FakeTransport();
        StreamRuntime runtime = connected("s1", transport);
        assertTrue(StreamRuntime.serviceEstablished("s1", runtime.generation()));
        FakePresentation game = new FakePresentation();
        game.showing = true;
        runtime.attach(game);
        m.enterHome(runtime, "s1", "host", "p", 7, "g");

        assertFalse(m.parkForBackground("s1"));             // Home.onStop over a shown game
        assertEquals(0, platform.parks.get());
        assertEquals(0, transport.decoderDetaches.get());
        assertFalse(runtime.isStopping());
        assertEquals(SessionModel.State.HOME_LIVE, m.state());

        game.showing = false;                                // TV Home: the game left the screen
        assertTrue(m.parkForBackground("s1"));
        assertEquals(1, platform.parks.get());
        assertEquals(SessionModel.State.PARKED_LIVE, m.state());
    }

    @Test public void stoppingTransportIsNeverReattachedOrUnmuted() {
        FakeTransport transport = new FakeTransport();
        platform.stopExecutor = action -> { };              // the stop stays in flight
        StreamRuntime runtime = connected("s1", transport);
        StreamRuntime.Attachment attachment = runtime.attach(new FakePresentation());
        assertTrue(runtime.surfaceLost(attachment));
        runtime.stop(result -> { });                         // termination began

        assertFalse(runtime.attachDecoder(attachment, null));
        runtime.setAudioVolume(attachment, 1f);
        assertEquals(0f, transport.volume, 0f);
    }

    @Test public void idrReachesTheCoreOnlyWhileThisGenerationIsConnected() {
        FakeTransport transport = new FakeTransport();
        StreamRuntime runtime = StreamRuntime.create("s1", "p", transport, platform);
        StreamRuntime.Attachment attachment = runtime.attach(new FakePresentation());
        runtime.requestIdrFrame(attachment);                 // connecting: no native control yet
        assertEquals(0, transport.idrRequests.get());

        runtime.start();
        runtime.connectionStarted();
        runtime.requestIdrFrame(attachment);
        assertEquals(1, transport.idrRequests.get());

        runtime.stop(result -> { });                          // after the stop: never again
        runtime.requestIdrFrame(attachment);
        assertFalse(runtime.reauthorizeControlGeneration(attachment, 1L, 2L));
        assertEquals(1, transport.idrRequests.get());
    }

    @Test public void nativeStopWaitsForAnInFlightControlCall() throws Exception {
        FakeTransport transport = new FakeTransport();
        java.util.concurrent.ExecutorService stopThread =
                java.util.concurrent.Executors.newSingleThreadExecutor();
        platform.stopExecutor = stopThread::execute;
        try {
            StreamRuntime runtime = connected("s1", transport);
            StreamRuntime.Attachment attachment = runtime.attach(new FakePresentation());
            transport.idrEntered = new CountDownLatch(1);
            transport.idrRelease = new CountDownLatch(1);
            Thread idr = new Thread(() -> runtime.requestIdrFrame(attachment));
            idr.start();
            assertTrue(transport.idrEntered.await(5, TimeUnit.SECONDS));

            CountDownLatch stopped = new CountDownLatch(1);
            runtime.stop(result -> stopped.countDown());
            assertFalse("the core stop ran during a native call",
                    stopped.await(200, TimeUnit.MILLISECONDS));
            assertEquals(0, transport.stops.get());

            transport.idrRelease.countDown();
            assertTrue(stopped.await(5, TimeUnit.SECONDS));
            assertEquals(1, transport.stops.get());
            idr.join(5_000L);
        } finally {
            stopThread.shutdownNow();
        }
    }

    @Test public void mainThreadCallsReturnAtOnceDuringASlowNativeStop() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.stopRelease = new CountDownLatch(1);
        java.util.concurrent.ExecutorService stopThread =
                java.util.concurrent.Executors.newSingleThreadExecutor();
        platform.stopExecutor = stopThread::execute;
        try {
            StreamRuntime runtime = connected("s1", transport);
            StreamRuntime.Attachment attachment = runtime.attach(new FakePresentation());
            platform.input.runAll();
            runtime.stop(result -> { });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (transport.stops.get() == 0 && System.nanoTime() < deadline) Thread.yield();
            assertEquals("the native stop is in flight", 1, transport.stops.get());

            long started = System.nanoTime();
            runtime.requestIdrFrame(attachment);
            assertFalse(runtime.reauthorizeControlGeneration(attachment, 1L, 2L));
            attachment.input.sendKeyboardInput((short) 4, KeyboardPacket.KEY_DOWN,
                    (byte) 0, (byte) 0);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            assertTrue("main-thread calls waited " + elapsedMs + " ms", elapsedMs < 200);
            assertEquals(0, transport.idrRequests.get());
            assertTrue(transport.packets().isEmpty());
        } finally {
            transport.stopRelease.countDown();
            stopThread.shutdownNow();
        }
    }

    @Test public void abandonedPreparingStopsWithoutSavingReconnectMaterial() {
        FakeTransport transport = new FakeTransport();
        StreamRuntime runtime = StreamRuntime.create("s1", "p", transport, platform);
        assertTrue(m.beginPreparing(runtime, "s1", "host", "p", 7, "", "t1", 1L));
        StreamRuntime.Attachment attachment = runtime.attach(new FakePresentation());
        SessionModel.Snapshot preparing = m.snapshot();
        // The presentation's cancel is still queued for the model when it goes away.
        m.model.post(new SessionEvent.PreparingCancelled(runtime, preparing));

        runtime.abandonPreparing(attachment);

        assertTrue(platform.reconnectSaved.isEmpty());
        assertNull(StreamRuntime.forSession("s1"));
        assertEquals(SessionModel.State.NONE, m.state());
        assertTrue(m.snapshot().records.pending == null);
    }

    @Test public void launchCompletingAfterCancelClosesTheWholeHostStream() {
        FakeTransport transport = new FakeTransport();
        StreamRuntime runtime = StreamRuntime.create("s1", "p", transport, platform);
        runtime.start();
        runtime.stop(r -> { });
        // The host granted the launch only after the cancel stopped this transport.
        transport.controlGeneration = 9L;
        runtime.stageComplete("app");

        assertEquals(Collections.singletonList("s1:p:9"), platform.abandonedCloses);
    }

    @Test public void cancelBeforeTheGrantHandsTheStartBackInsteadOfClosing() {
        FakeTransport transport = new FakeTransport();
        StreamRuntime runtime = StreamRuntime.create("s1", "p", transport, platform);
        runtime.start();
        // Not a cancel yet: a running start is never abandoned.
        runtime.abandonLaunch();
        assertEquals(0, transport.abandonedStarts.get());
        runtime.stop(r -> { });
        runtime.abandonLaunch();

        assertEquals(1, transport.abandonedStarts.get());
        assertEquals(Collections.emptyList(), platform.abandonedCloses);
        // The start had been sent after all, or the host found it started: closed once.
        transport.controlGeneration = 9L;
        runtime.stageComplete("app");
        assertEquals(Collections.singletonList("s1:p:9"), platform.abandonedCloses);
    }

    @Test public void startFoundOnTheHostAfterALostPermitIsClosedOnce() {
        FakeTransport transport = new FakeTransport();
        StreamRuntime runtime = StreamRuntime.create("s1", "p", transport, platform);
        runtime.start();
        // No cancel and no bound generation: the host named the one it found started.
        runtime.startLeftOnHost(9L);
        runtime.startLeftOnHost(9L);

        assertEquals(Collections.singletonList("s1:p:9"), platform.abandonedCloses);
    }

    @Test public void cancelAfterTheGrantClosesWithoutAbandoning() {
        FakeTransport transport = new FakeTransport();
        StreamRuntime runtime = StreamRuntime.create("s1", "p", transport, platform);
        runtime.start();
        transport.controlGeneration = 9L;
        runtime.stop(r -> { });
        runtime.abandonLaunch();

        assertEquals(0, transport.abandonedStarts.get());
        assertEquals(Collections.singletonList("s1:p:9"), platform.abandonedCloses);
    }

    @Test public void grantRacingTheCancelStopThroughTheLivePathIsClosedOnce() {
        FakeTransport transport = new FakeTransport();
        StreamRuntime runtime = StreamRuntime.create("s1", "p", transport, platform);
        runtime.start();
        // The cancel read no generation; the grant then completed before its stop.
        transport.controlGeneration = 9L;
        runtime.stageComplete("app");
        runtime.stop(r -> { });
        runtime.abandonLaunch();
        runtime.abandonLaunch();

        assertEquals(Collections.singletonList("s1:p:9"), platform.abandonedCloses);
    }

    @Test public void abandonedLaunchClosesOnceWhicheverPathSeesTheGrantFirst() {
        FakeTransport after = new FakeTransport();
        StreamRuntime runtime = StreamRuntime.create("s1", "p", after, platform);
        runtime.start();
        runtime.stop(r -> { });
        after.controlGeneration = 9L;
        runtime.stageComplete("app");
        runtime.abandonLaunch();
        assertEquals(Collections.singletonList("s1:p:9"), platform.abandonedCloses);

        // Abandoned before the grant, while the model still holds the cancelled session.
        platform.abandonedCloses.clear();
        FakeTransport before = new FakeTransport();
        StreamRuntime next = StreamRuntime.create("s2", "p", before, platform);
        assertTrue(m.beginPreparing(next, "s2", "host", "p", 7, "", "t2", 1L));
        next.start();
        next.stop(r -> { });
        next.abandonLaunch();
        assertTrue(platform.abandonedCloses.isEmpty());
        before.controlGeneration = 4L;
        next.stageComplete("app");
        next.stageComplete("app");
        assertEquals(Collections.singletonList("s2:p:4"), platform.abandonedCloses);
    }

    @Test public void keptLaunchRecordsALateGrantAndNeverClosesTheHostStream() {
        FakeTransport transport = new FakeTransport();
        StreamRuntime runtime = StreamRuntime.create("s1", "p", transport, platform);
        runtime.start();
        // A cancelled reconnect to a running game: the model holds nothing for this session.
        runtime.keepLaunch();
        runtime.stop(r -> { });
        transport.controlGeneration = 9L;
        runtime.stageComplete("app");

        assertTrue(platform.abandonedCloses.isEmpty());
        assertEquals(Collections.singletonList("s1:9"), platform.generationsBound);
    }

    @Test public void abandonedLaunchIsNotSavedForReconnectWhileTheModelStillHoldsIt() {
        FakeTransport transport = new FakeTransport();
        StreamRuntime runtime = connected("s1", transport);
        m.enterHome(runtime, "s1", "host", "p", 7, "g");
        StreamRuntime.Attachment attachment = runtime.attach(new FakePresentation());
        // The cancel's clear is still queued for the model when the Activity is destroyed.
        runtime.stop(r -> { });
        runtime.abandonLaunch();
        runtime.presentationLost(attachment, true);

        assertTrue(platform.reconnectSaved.isEmpty());
    }

    @Test public void liveRuntimeIsNeverAbandoned() {
        FakeTransport transport = new FakeTransport();
        StreamRuntime runtime = StreamRuntime.create("s1", "p", transport, platform);
        runtime.start();
        transport.controlGeneration = 9L;
        runtime.abandonLaunch();
        runtime.stageComplete("app");

        assertTrue(platform.abandonedCloses.isEmpty());
    }

    @Test public void liveOrRetainedLaunchIsNeverClosedAsAbandoned() {
        FakeTransport live = new FakeTransport();
        StreamRuntime runtime = StreamRuntime.create("s1", "p", live, platform);
        runtime.start();
        live.controlGeneration = 9L;
        runtime.stageComplete("app");
        runtime.stop(r -> { });

        FakeTransport retained = new FakeTransport();
        StreamRuntime next = StreamRuntime.create("s2", "p", retained, platform);
        assertTrue(m.beginPreparing(next, "s2", "host", "p", 7, "", "t2", 1L));
        next.start();
        next.stop(r -> { });
        retained.controlGeneration = 4L;
        next.stageComplete("app");

        assertTrue(platform.abandonedCloses.isEmpty());
    }

    @Test public void surfaceLossDetachesAndMutesOnlyAConnectedCurrentAttachment() {
        FakeTransport transport = new FakeTransport();
        StreamRuntime runtime = StreamRuntime.create("s1", "p", transport, platform);
        StreamRuntime.Attachment attachment = runtime.attach(new FakePresentation());
        assertFalse(runtime.surfaceLost(attachment));
        assertEquals(0, transport.decoderDetaches.get());

        runtime.start();
        runtime.connectionStarted();
        assertTrue(runtime.surfaceLost(attachment));

        assertEquals(1, transport.decoderDetaches.get());
        assertEquals(0f, transport.volume, 0f);
        assertEquals(SessionModel.State.NONE, m.state());
    }

    @Test public void preparingWithoutTransportIsStoppedHonestlyOnPresentationLoss() {
        FakeTransport transport = new FakeTransport();
        StreamRuntime runtime = StreamRuntime.create("s1", "p", transport, platform);
        assertTrue(m.beginPreparing(runtime, "s1", "host", "p",
                7, "", "t1", 1L));
        StreamRuntime.Attachment attachment = runtime.attach(new FakePresentation());

        runtime.presentationLost(attachment, false);

        assertEquals(SessionModel.State.RECONNECT_REQUIRED,
                m.state());
        assertEquals(Collections.singletonList("s1"), platform.reconnectSaved);
        assertEquals("never started: no native stop effect", 0, transport.stops.get());
        assertEquals(0, transport.starts.get());
        assertNull(StreamRuntime.forSession("s1"));
    }

    @Test public void finishingPresentationNeverLeavesAProtectedTransportRunning() {
        FakeTransport transport = new FakeTransport();
        StreamRuntime runtime = connected("s1", transport);
        assertTrue(StreamRuntime.serviceEstablished("s1", runtime.generation()));
        m.enterHome(runtime, "s1", "host", "p", 7, "g");
        StreamRuntime.Attachment attachment = runtime.attach(new FakePresentation());

        runtime.presentationLost(attachment, true);

        assertEquals(1, transport.stops.get());
        assertEquals(1, platform.locksReleased.get());
    }

    /**
     * TV 2026-10-14: a warm-up the user took over (HOME_LIVE) was closed on purpose. Without the
     * close recorded, the destroyed Activity's presentation loss marked it RECONNECT_REQUIRED;
     * with it (Game.closeStreamWithPrivacy) nothing is left to reconnect.
     */
    @Test public void intentionallyClosedRetainedStreamLeavesNoReconnectMarker() {
        for (boolean recorded : new boolean[] {false, true}) {
            StreamRuntime.resetForTests();
            TransportStopCoordinator.resetForTests();
            platform = new FakePlatform();
            m = platform.driver;
            StreamRuntime runtime = connected("s1", new FakeTransport());
            m.enterHome(runtime, "s1", "host", "p", 7, "steam:952060");
            m.seedReconnectRecord("s1");
            StreamRuntime.Attachment attachment = runtime.attach(new FakePresentation());

            AtomicReference<Boolean> closed = new AtomicReference<>();
            if (recorded) runtime.closeIntentionally(closed::set);
            else runtime.stop(r -> { });
            m.drain();
            // Game finishes in the close's answer: onDestroy's presentation loss comes after it.
            if (recorded) assertEquals(Boolean.TRUE, closed.get());
            runtime.presentationLost(attachment, true);

            assertEquals(recorded ? SessionModel.State.NONE
                    : SessionModel.State.RECONNECT_REQUIRED, m.state());
            assertEquals(recorded, platform.reconnectSaved.isEmpty());
            assertEquals(!recorded, m.hasReconnectRecord());
        }
    }

    /** A stop that did not complete leaves the transport live: its record and marker stay. */
    @Test public void failedIntentionalCloseKeepsTheLiveStreamsRecord() {
        FakeTransport transport = new FakeTransport();
        transport.failStop = true;
        StreamRuntime runtime = connected("s1", transport);
        m.enterHome(runtime, "s1", "host", "p", 7, "steam:952060");
        m.seedReconnectRecord("s1");

        AtomicReference<Boolean> closed = new AtomicReference<>();
        runtime.closeIntentionally(closed::set);
        m.drain();

        assertNull("the presentation is not finished", closed.get());
        assertEquals(SessionModel.State.HOME_LIVE, m.state());
        assertTrue(m.ownsLiveOrParkedSession(runtime, "s1", "host", 7));
        assertTrue(m.hasReconnectRecord());
    }


    @Test public void destroyExplicitStopAndNewStartShareOneStopResult() {
        QueueExecutor stops = new QueueExecutor();
        platform.stopExecutor = stops;
        FakeTransport old = new FakeTransport();
        StreamRuntime runtime = connected("s1", old);
        StreamRuntime.Attachment attachment = runtime.attach(new FakePresentation());
        List<TransportStopCoordinator.Result> results =
                Collections.synchronizedList(new ArrayList<>());

        runtime.stop(results::add);                         // explicit quit
        runtime.presentationLost(attachment, true);         // Activity destroyed meanwhile
        FakeTransport replacement = new FakeTransport();
        StreamRuntime next = StreamRuntime.create("s2", "p", replacement, platform);
        assertNotNull("a stopping generation does not block the next registration", next);
        next.start();                                       // new Play intent
        runtime.stop(results::add);
        assertEquals(0, replacement.starts.get());
        assertTrue(results.isEmpty());

        stops.runAll();

        assertEquals("one real stop", 1, old.stops.get());
        assertEquals(2, results.size());
        assertEquals(results.get(0), results.get(1));
        assertEquals(1, replacement.starts.get());
        assertFalse("old completion cannot touch the new generation", next.isStopping());
        assertSame(next, StreamRuntime.forSession("s2"));
    }

    @Test public void acceptedWarmUpSurvivesRepeatedRecreationWhileConnecting() {
        FakeTransport transport = new FakeTransport();
        StreamRuntime runtime = StreamRuntime.create("s1", "p", transport, platform);
        assertTrue(m.beginPreparing(runtime, "s1", "host", "p",
                7, "", "t1", 1L));
        FakePresentation first = new FakePresentation();
        first.acceptHomeFrame = true;
        StreamRuntime.Attachment firstAttachment = runtime.attach(first);
        assertTrue(m.preparingHomeFrameSubmitted(
                m.snapshot()));
        runtime.start();                                    // CONNECTING

        runtime.presentationLost(firstAttachment, false);   // recreation #1
        FakePresentation second = new FakePresentation();
        StreamRuntime.Attachment secondAttachment = runtime.attach(second);
        runtime.presentationLost(secondAttachment, false);  // recreation #2 before connect
        FakePresentation third = new FakePresentation();
        runtime.attach(third);
        runtime.connectionStarted();

        assertEquals("one native start", 1, transport.starts.get());
        assertEquals(0, transport.stops.get());
        assertEquals("one accepted Home frame", 1, first.homeFrames.get());
        assertEquals(0, second.homeFrames.get() + third.homeFrames.get());
        assertEquals(1, third.started.get());
        assertEquals(0, first.started.get() + second.started.get());
        assertEquals(SessionModel.State.PREPARING,
                m.state());
        assertTrue(m.isPreparingHomeFrameAccepted(
                "s1", "host", 7, "", "t1", 1L));
    }

    @Test public void lateObserversOfAFailedStopNeverSeeStopped() {
        FakeTransport transport = new FakeTransport();
        transport.failStop = true;
        StreamRuntime runtime = connected("s1", transport);
        AtomicReference<TransportStopCoordinator.Result> early = new AtomicReference<>();
        runtime.stop(early::set);
        AtomicReference<TransportStopCoordinator.Result> late = new AtomicReference<>();
        runtime.stop(late::set);

        assertEquals(TransportStopCoordinator.Result.FAILED, early.get());
        assertEquals(TransportStopCoordinator.Result.FAILED, late.get());

        FakeTransport successor = new FakeTransport();
        StreamRuntime next = StreamRuntime.create("s2", "p", successor, platform);
        next.start();
        AtomicReference<TransportStopCoordinator.Result> afterSuccessor = new AtomicReference<>();
        runtime.stop(afterSuccessor::set);
        assertEquals(TransportStopCoordinator.Result.FAILED, afterSuccessor.get());
        assertEquals("failed predecessor never starts the successor native", 0,
                successor.starts.get());
    }

    private StreamRuntime connected(String session, FakeTransport transport) {
        StreamRuntime runtime = StreamRuntime.create(session, "p", transport, platform);
        assertNotNull(runtime);
        runtime.start();
        runtime.connectionStarted();
        return runtime;
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(2, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        }
    }

    static final class QueueExecutor implements Executor {
        private final ArrayDeque<Runnable> queue = new ArrayDeque<>();

        @Override public synchronized void execute(Runnable command) { queue.add(command); }

        void runAll() {
            while (true) {
                Runnable next;
                synchronized (this) { next = queue.poll(); }
                if (next == null) return;
                next.run();
            }
        }
    }

    static final class FakePlatform implements StreamRuntime.Platform {
        final SessionModelDriver driver = new SessionModelDriver();
        @Override public SessionModel sessionModel() { return driver.model; }
        final AtomicInteger locksAcquired = new AtomicInteger();
        final AtomicInteger locksReleased = new AtomicInteger();
        final AtomicInteger serviceRequests = new AtomicInteger();
        final AtomicInteger parks = new AtomicInteger();
        final List<String> serviceEnds = Collections.synchronizedList(new ArrayList<>());
        final List<String> reconnectSaved = Collections.synchronizedList(new ArrayList<>());
        final List<String> reconnectCleared = Collections.synchronizedList(new ArrayList<>());
        final QueueExecutor input = new QueueExecutor();
        Executor stopExecutor = Runnable::run;
        volatile boolean foreground = true;
        boolean serviceAccepted = true;
        int retention = 30;

        @Override public void acquireWifiLocks() { locksAcquired.incrementAndGet(); }
        @Override public void releaseWifiLocks() { locksReleased.incrementAndGet(); }
        @Override public boolean requestForegroundService(String id, long generation) {
            serviceRequests.incrementAndGet();
            return serviceAccepted;
        }
        @Override public void parkService(String id, long generation, int minutes) {
            parks.incrementAndGet();
        }
        @Override public void resumeService(String id, long generation) { }
        @Override public void endService(String id, long generation, boolean lost) {
            serviceEnds.add((lost ? "lost:" : "end:") + id);
        }
        @Override public boolean appForeground() { return foreground; }
        @Override public int retentionMinutes() { return retention; }
        @Override public void saveReconnectRecord(String id) { reconnectSaved.add(id); }
        @Override public void clearReconnectRecord(String id) { reconnectCleared.add(id); }
        final List<String> generationsBound = Collections.synchronizedList(new ArrayList<>());
        @Override public void controlGenerationBound(String id, long generation) {
            generationsBound.add(id + ":" + generation);
        }
        @Override public void terminateHost(StreamRuntime runtime,
                                            SessionModel.Snapshot session, String gameId,
                                            SessionModel.TerminationCallback c) { }
        final List<String> abandonedCloses = Collections.synchronizedList(new ArrayList<>());
        @Override public void closeAbandonedLaunch(String id, String profile, long generation) {
            abandonedCloses.add(id + ":" + profile + ":" + generation);
        }
        @Override public void runtimeEnded() { }
        @Override public Executor stopExecutor() { return stopExecutor; }
        @Override public Executor mainExecutor() { return Runnable::run; }
        @Override public Executor inputExecutor() { return input; }
        @Override public void record(String level, String event, Object... fields) { }
    }

    static final class FakePresentation implements StreamRuntime.Presentation {
        final AtomicInteger terminated = new AtomicInteger();
        final AtomicInteger disconnected = new AtomicInteger();
        final AtomicInteger started = new AtomicInteger();
        final AtomicInteger homeFrames = new AtomicInteger();
        boolean acceptHomeFrame;
        boolean showing;

        @Override public boolean canPark() { return !showing; }

        @Override public boolean parkPreparingTransport(SessionModel.Snapshot s) { return false; }
        @Override public boolean cancelPreparingTransport(SessionModel.Snapshot s) { return false; }
        @Override public boolean preparingHomeFrameSubmitted(SessionModel.Snapshot s) {
            homeFrames.incrementAndGet();
            return acceptHomeFrame;
        }
        @Override public void switchGame(SessionModel.SwitchRequest r,
                                         SessionModel.SwitchCallback c) { }
        @Override public void terminateRetainedSession(String gameId, SessionModel.TerminationCallback c) { }
        @Override public void onTransportParked() { }
        @Override public void onRuntimeDisconnected() { disconnected.incrementAndGet(); }
        @Override public void onTransportStopping() { }
        @Override public void stageStarting(String stage) { }
        @Override public void stageComplete(String stage) { }
        @Override public void stageFailed(String stage, int portFlags, int errorCode) { }
        @Override public void controlRejected(String stage, String reason,
                                              boolean nothingStarted) { }
        @Override public void connectionStarted() { started.incrementAndGet(); }
        @Override public void connectionTerminated(int errorCode) { terminated.incrementAndGet(); }
        @Override public void connectionStatusUpdate(int connectionStatus) { }
        @Override public void displayMessage(String message) { }
        @Override public void displayTransientMessage(String message) { }
        @Override public void rumble(short c, short l, short h) { }
        @Override public void rumbleTriggers(short c, short l, short r) { }
        @Override public void setHdrMode(boolean enabled, byte[] metadata) { }
        @Override public void setMotionEventState(short c, byte t, short r) { }
        @Override public void setControllerLED(short c, byte r, byte g, byte b) { }
    }

    static final class FakeTransport implements StreamRuntime.Transport {
        final AtomicInteger starts = new AtomicInteger();
        final AtomicInteger stops = new AtomicInteger();
        final AtomicInteger decoderDetaches = new AtomicInteger();
        private final List<String> packets = new ArrayList<>();
        volatile Runnable blockControllerSend;
        volatile boolean failStop;
        volatile float volume = 1f;

        synchronized List<String> packets() { return new ArrayList<>(packets); }
        private synchronized void add(String packet) { packets.add(packet); }

        @Override public void start(NvConnectionListener listener) { starts.incrementAndGet(); }
        volatile CountDownLatch stopRelease;
        @Override public void stop() {
            stops.incrementAndGet();
            try {
                if (stopRelease != null) stopRelease.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            if (failStop) throw new IllegalStateException("stop failed");
        }
        volatile long controlGeneration;
        final AtomicInteger abandonedStarts = new AtomicInteger();
        @Override public long controlGeneration() { return controlGeneration; }
        @Override public void abandonStart() { abandonedStarts.incrementAndGet(); }
        @Override public boolean reauthorizeControlGeneration(long expected, long generation) { return true; }
        final AtomicInteger idrRequests = new AtomicInteger();
        volatile CountDownLatch idrEntered;
        volatile CountDownLatch idrRelease;
        @Override public void requestIdrFrame() {
            idrRequests.incrementAndGet();
            if (idrEntered != null) idrEntered.countDown();
            try {
                if (idrRelease != null) idrRelease.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        @Override public boolean attachDecoder(VideoDecoderRenderer decoder) { return true; }
        @Override public void detachDecoder() { decoderDetaches.incrementAndGet(); }
        @Override public void setAudioVolume(float volume) { this.volume = volume; }
        @Override public void setHdrMode(boolean enabled, byte[] metadata) { }

        @Override public void sendMouseMove(short x, short y) { add("move"); }
        @Override public void sendMousePosition(short x, short y, short w, short h) { add("pos"); }
        @Override public void sendMouseMoveAsMousePosition(short x, short y, short w, short h) { add("pos"); }
        @Override public void sendMouseButtonDown(byte b) { add("mouse-down:" + b); }
        @Override public void sendMouseButtonUp(byte b) { add("mouse-up:" + b); }
        @Override public void sendControllerInput(short n, short mask, int buttons, byte lt, byte rt,
                                                  short lx, short ly, short rx, short ry) {
            Runnable block = blockControllerSend;
            if (block != null && buttons != 0) {
                blockControllerSend = null;
                block.run();
            }
            add("controller:" + n + ":" + buttons);
        }
        @Override public void sendKeyboardInput(short key, byte direction, byte modifier, byte flags) {
            add((direction == KeyboardPacket.KEY_UP ? "key-up:" : "key-down:") + key);
        }
        @Override public void sendMouseScroll(byte c) { add("scroll"); }
        @Override public void sendMouseHScroll(byte c) { add("hscroll"); }
        @Override public void sendMouseHighResScroll(short a) { add("scroll"); }
        @Override public void sendMouseHighResHScroll(short a) { add("hscroll"); }
        @Override public int sendTouchEvent(byte t, int p, float x, float y, float pr, float ma,
                                            float mi, short r) { add("touch:" + t); return 0; }
        @Override public int sendPenEvent(byte t, byte tool, byte b, float x, float y, float pr,
                                          float ma, float mi, short r, byte tilt) {
            add("pen:" + t); return 0;
        }
        @Override public int sendControllerArrivalEvent(byte n, short mask, byte type, int flags,
                                                        short caps) { add("arrival:" + n); return 0; }
        @Override public int sendControllerTouchEvent(byte n, byte t, int p, float x, float y,
                                                      float pr) { return 0; }
        @Override public int sendControllerMotionEvent(byte n, byte t, float x, float y, float z) {
            return 0;
        }
        @Override public void sendControllerBatteryEvent(byte n, byte s, byte p) { }
        @Override public void sendUtf8Text(String text) { add("text"); }
    }
}
