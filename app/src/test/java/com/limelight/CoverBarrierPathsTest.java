package com.limelight;

import com.limelight.console.SessionOrchestrator;
import com.limelight.console.transition.LaunchTransitionController;
import com.limelight.console.transition.LaunchTransitionSpec;
import com.limelight.console.transition.LaunchTransitionState;
import com.limelight.console.transition.LaunchTransitionType;
import com.limelight.console.transition.PresentationBarrier;
import com.limelight.console.transition.PresentationBarrierTest;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static com.limelight.SourceText.assertOrdered;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Paths whose effect used to wait for two animation callbacks of a view that might never draw
 * again, and then silently dropped it: each now settles through the barrier and the controller.
 */
public class CoverBarrierPathsTest {
    private static final String HOST = "host-1";
    private static final String GAME = "11111111-2222-3333-4444-555555555555";

    private final PresentationBarrierTest.ManualTime time =
            new PresentationBarrierTest.ManualTime();
    private final PresentationBarrier cover = time.barrier();
    private final List<String> effects = new ArrayList<>();

    private static LaunchTransitionController begun(String id) {
        LaunchTransitionController controller = new LaunchTransitionController(null);
        controller.begin(new LaunchTransitionSpec(id, HOST, LaunchTransitionType.GAME, 42,
                GAME, 1000L));
        return controller;
    }

    private static LaunchTransitionController running(String id) {
        LaunchTransitionController controller = begun(id);
        controller.coverSettled(id, PresentationBarrier.Outcome.SUBMITTED);
        controller.surfaceReady(id);
        controller.inputPipelineReady(id);
        controller.streamConnected(id);
        controller.gatewayConnected(id, HOST);
        controller.targetProcessRunning(id, HOST, LaunchTransitionType.GAME, GAME);
        controller.targetWindowReady(id, HOST, LaunchTransitionType.GAME, GAME);
        controller.videoFrameRendered(id, controller.snapshot().frameEdge, System.nanoTime());
        controller.revealCompleted(id);
        return controller;
    }

    /** Game start: the connection starts although the cover never draws. */
    @Test public void startConnectsAfterTheDeadlineWhenTheCoverNeverDraws() {
        LaunchTransitionController controller = begun("start");
        cover.await("start", 1L, PresentationBarrier.COVER_DEADLINE_MS, outcome -> {
            if (controller.coverSettled("start", outcome)) effects.add("start-connection");
        });
        time.advance(PresentationBarrier.COVER_DEADLINE_MS);

        assertEquals(List.of("start-connection"), effects);
        assertTrue(controller.snapshot().operationAuthorized);
        assertTrue("an unavailable frame never reveals", controller.snapshot().overlayVisible);
    }

    /**
     * Game start on the TV: the first traversal creates the surface (surfaceReady) before that
     * frame's commit settles the start cover; the connection still starts.
     */
    @Test public void startConnectsWhenTheSurfaceComesBeforeTheCoverFrame() {
        LaunchTransitionController controller = begun("start");
        cover.await("start", 1L, PresentationBarrier.COVER_DEADLINE_MS, outcome -> {
            if (controller.coverSettled("start", outcome)
                    && controller.snapshot().operationAuthorized) {
                effects.add("start-connection");
            }
        });
        controller.surfaceReady("start");
        cover.onSignal("start", 1L);

        assertEquals(List.of("start-connection"), effects);
    }

    /** Hidden warm-up: the Home's frame comes up to its deadline after the surface. */
    @Test public void warmUpConnectsWhenTheHomeFrameComesLate() {
        LaunchTransitionController controller = begun("warm");
        cover.await("home", 1L, PresentationBarrier.HOME_DEADLINE_MS, outcome -> {
            // preparingHomeFrameSubmitted: the model accepted the Home's frame.
            if (controller.coverSettled("warm", PresentationBarrier.Outcome.SUBMITTED)
                    && controller.snapshot().operationAuthorized) {
                effects.add("start-connection");
            }
        });
        controller.surfaceReady("warm");
        controller.inputPipelineReady("warm");
        time.advance(PresentationBarrier.HOME_DEADLINE_MS - 1L);
        cover.onSignal("home", 1L);

        assertEquals(List.of("start-connection"), effects);
    }

    /**
     * onDestroy stops the cover view, settling its waits synchronously: the destroyed
     * Activity's effect (close, neutral stream, new coordinator) never runs, only bookkeeping.
     */
    @Test public void coverSettledOnADestroyedActivityRunsOnlyItsBookkeeping() {
        LaunchTransitionController controller = running("live");
        boolean[] destroyed = {false};
        cover.await("live", 1L, PresentationBarrier.COVER_DEADLINE_MS,
                Game.liveCoverEffect(() -> destroyed[0], () -> effects.add("switch-cancelled"),
                        outcome -> {
                            if (!controller.coverSettled("live", outcome)) return;
                            effects.add("close-runtime");
                            effects.add("new-coordinator");
                        }));
        destroyed[0] = true;
        cover.surfaceChanged(0L);

        assertEquals(List.of("switch-cancelled"), effects);
    }

    /** Game start: the user's Cancel during the wait wins; nothing starts. */
    @Test public void cancelDuringTheStartCoverStartsNothing() {
        LaunchTransitionController controller = begun("start");
        cover.await("start", 1L, PresentationBarrier.COVER_DEADLINE_MS, outcome -> {
            if (controller.coverSettled("start", outcome)) effects.add("start-connection");
            else effects.add("cancel-path");
        });
        controller.cancel("start");
        cover.cancel("start");
        cover.onSignal("start", 1L);
        time.advance(10_000L);

        assertEquals(List.of("cancel-path"), effects);
        assertFalse(controller.snapshot().operationAuthorized);
    }

    /**
     * Home return as Game executes it (coverThenShowHome): the controller covers, the Home
     * opens even on a detached view, and the cover comes off only once the user is back and a
     * fresh frame shows the stream.
     */
    @Test public void homeReturnSettlesWhenItsViewDetaches() {
        LaunchTransitionController controller = running("live");
        assertTrue(controller.coverForUser("live", HOME));
        assertFalse("a repeated Back starts no second Home return",
                controller.coverForUser("live", HOME));
        cover.await("live", 1L, PresentationBarrier.COVER_DEADLINE_MS, outcome -> {
            if (Game.homeReturnOpensHome(controller.coverSettled("live", outcome),
                    SessionOrchestrator.ActionKind.COVER_AND_SHOW_HOME)) {
                effects.add("home");
            } else {
                controller.releaseUserCover("live", HOME);
            }
        });
        cover.surfaceChanged(2L);

        assertEquals(List.of("home"), effects);
        assertTrue("covered while the Home shows", controller.snapshot().overlayVisible);
        // onResume: back from the Home.
        controller.releaseUserCover("live", HOME);
        controller.videoFrameRendered("live", controller.snapshot().frameEdge, System.nanoTime());
        assertFalse(controller.snapshot().overlayVisible);
        assertEquals(LaunchTransitionState.GAME_RUNNING, controller.snapshot().state);
    }

    /** A Home return the user cancelled (or decided otherwise) gives the stream back. */
    @Test public void cancelledHomeReturnRestoresTheStream() {
        LaunchTransitionController controller = running("live");
        controller.coverForUser("live", HOME);
        cover.await("live", 1L, PresentationBarrier.COVER_DEADLINE_MS, outcome -> {
            if (Game.homeReturnOpensHome(controller.coverSettled("live", outcome),
                    SessionOrchestrator.ActionKind.COVER_AND_SHOW_HOME)) {
                effects.add("home");
            } else {
                controller.releaseUserCover("live", HOME);
            }
        });
        cover.cancel("live");
        controller.videoFrameRendered("live", controller.snapshot().frameEdge, System.nanoTime());

        assertTrue(effects.isEmpty());
        assertFalse(controller.snapshot().overlayVisible);
        assertFalse(Game.homeReturnOpensHome(true, SessionOrchestrator.ActionKind.CANCEL_ON_COVER));
    }

    private static final LaunchTransitionController.UserCover HOME =
            LaunchTransitionController.UserCover.HOME_RETURN;


    /**
     * Retained switch: the old stream's cover without a surface still begins the switch, and a
     * Cancel ends it as cancelled; either way it never keeps owning the stream.
     */
    @Test public void switchNeverKeepsItsOwnerWhenTheCoverCannotDraw() {
        LaunchTransitionController controller = running("old");
        Object[] switchOwner = {new Object()};
        cover.await("old", 0L, PresentationBarrier.COVER_DEADLINE_MS, outcome -> {
            if (controller.coverSettled("old", outcome)) effects.add("provider-switch");
            else switchOwner[0] = null;
        });
        assertEquals(List.of("provider-switch"), effects);

        cover.await("old", 1L, PresentationBarrier.COVER_DEADLINE_MS, outcome -> {
            if (controller.coverSettled("old", outcome)) effects.add("provider-switch");
            else switchOwner[0] = null;
        });
        cover.cancel("old");
        assertEquals(List.of("provider-switch"), effects);
        assertEquals(null, switchOwner[0]);
    }

    /** End game: the stop runs although the cover never draws; the stopping state stays. */
    @Test public void endGameStopsAfterTheDeadlineAndKeepsItsStoppingCover() {
        LaunchTransitionController controller = running("live");
        assertTrue(controller.gameStopping("live", HOST, GAME));
        boolean[] endGameInFlight = {true};
        cover.await("live", 1L, PresentationBarrier.COVER_DEADLINE_MS, outcome -> {
            if (controller.coverSettled("live", outcome)) {
                effects.add("stop-game");
            } else {
                endGameInFlight[0] = false;
                controller.streamClosingFailed("live", "");
            }
        });
        time.advance(PresentationBarrier.COVER_DEADLINE_MS);

        assertEquals(List.of("stop-game"), effects);
        assertEquals(LaunchTransitionState.GAME_STOPPING, controller.snapshot().state);
        assertTrue(endGameInFlight[0]);
    }

    @Test public void cancelledEndGameClearsItsFlagAndShowsTheStreamAgain() {
        LaunchTransitionController controller = running("live");
        assertTrue(controller.gameStopping("live", HOST, GAME));
        boolean[] endGameInFlight = {true};
        cover.await("live", 1L, PresentationBarrier.COVER_DEADLINE_MS, outcome -> {
            if (controller.coverSettled("live", outcome)) {
                effects.add("stop-game");
            } else {
                endGameInFlight[0] = false;
                controller.streamClosingFailed("live", "");
            }
        });
        cover.cancel("live");

        assertTrue(effects.isEmpty());
        assertFalse(endGameInFlight[0]);
        assertEquals(LaunchTransitionState.GAME_RUNNING, controller.snapshot().state);
        assertFalse(controller.snapshot().overlayVisible);
    }

    /** Every former dropped-callback site now waits through the barrier and the controller. */
    @Test public void formerDroppedCallbackSitesSettleThroughTheBarrier() {
        String game = SourceText.main("Game.java");
        assertFalse(game.contains("doAfterNextFrame"));
        assertOrdered(game, "private void coverThenShowHome(",
                "transitionController.coverForUser(transitionId,",
                "awaitCover(transitionId, outcome -> {",
                "homeReturnOpensHome(transitionController.coverSettled(transitionId, outcome),",
                "transitionController.releaseUserCover(transitionId,");
        assertOrdered(game, "private void beginRetainedGameSwitch(",
                "presentSwitchCover(operation);",
                "Runnable cancelled = () -> completeRetainedSwitch(",
                "awaitCover(operation.oldSpec.id, cancelled, outcome -> {",
                "if (!transitionController.coverSettled(operation.oldSpec.id, outcome)) {",
                "cancelled.run();", "beginRetainedProviderSwitch(operation);");
        // Every cover wait goes through the one destroyed check.
        assertOrdered(game, "private void awaitCover(String fence, Runnable abandoned,",
                "liveCoverEffect(this::isDestroyed, abandoned, settled));");
        assertEquals(1, game.split("frames\\(\\)\\.await\\(", -1).length - 1);
        assertOrdered(game, "private void endManagedGameKeepingStream()",
                "providerEndGameInFlight = true;", "awaitCover(expected.id, outcome -> {",
                "if (!transitionController.coverSettled(expected.id, outcome)) {",
                "providerEndGameInFlight = false;",
                "runEndManagedGame(expected, actorContext, hostConfirmed);");
        // The window covers after its first frame through the barrier, never two animation
        // callbacks.
        assertOrdered(game, "new ViewFrameSource(getWindow().getDecorView()).await(\"window\",",
                "PresentationBarrier.COVER_DEADLINE_MS, outcome -> coverScreen());");

        assertOrdered(game, "private void endCancelledTransition(",
                "transitionController.cancel(transitionSpec.id);",
                "consoleLoadingView.frames().cancel(transitionSpec.id);");
        assertOrdered(game, "private void awaitCover(",
                "consoleLoadingView.frames().await(fence, PresentationBarrier.COVER_DEADLINE_MS,");

        String console = SourceText.main("console/ConsoleActivity.java");
        assertFalse(console.contains("doAfterNextFrame"));
        assertOrdered(console, "@Override public void showLoading(PlayIntent intent",
                "afterLoadingCover(opaqueFrameReady);");
        assertOrdered(console, "private void beginInstallationSupportStream(",
                "afterLoadingCover(startAfterOverlayFrame);");
        assertOrdered(console, "private void afterLoadingCover(Runnable effect) {",
                "streamLoadingView.frames().await(LOADING_COVER_FENCE,",
                "LaunchTransitionController.runUnlessCancelled(effect));");

        String view = SourceText.main("ui/ConsoleStreamLoadingView.java");
        assertFalse(view.contains("doAfterNextFrame"));
        assertFalse(view.contains("postOnAnimation"));
        assertOrdered(view, "public void showOpaque()", "revealGeneration++;",
                "frames.newPresentation();");
        assertOrdered(view, "private void stopAnimations()", "stopped = true;", "frames.stop();");
        assertOrdered(view, "public void revealStream(String fence, Runnable afterReveal)",
                "frames.await(fence, PresentationBarrier.COVER_DEADLINE_MS, outcome -> {",
                "LaunchTransitionController.coverAllowsEffect(outcome)", "afterReveal.run()");

        String home = SourceText.main("console/StreamHomeActivity.java");
        assertOrdered(home, "homeFrames = new ViewFrameSource(content);",
                "homeFrames.await(homeFrameFence(), PresentationBarrier.HOME_DEADLINE_MS,");
        assertOrdered(home, "private void cancelInitialCarouselFrame()",
                "homeFrames.cancel(homeFrameFence());", "abandonInitialCarouselFrame();");
    }
}
