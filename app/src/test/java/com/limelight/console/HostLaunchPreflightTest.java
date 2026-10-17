package com.limelight.console;

import com.limelight.diagnostics.MoonWakerDiagnostics;
import com.limelight.nvstream.http.NvApp;

import org.junit.Test;

import java.io.IOException;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class HostLaunchPreflightTest {
    @Test public void signInDiagnosticRecordsStateTransitionsWithoutAttemptId() throws Exception {
        Fake fake = new Fake();
        String attempt = "0123456789abcdef0123456789abcdef";
        fake.session = new HostLaunchPreflight.Session("pending", "none", attempt, 500);
        fake.sessionStatuses.add(new HostLaunchPreflight.Session(
                "credential_issued", "none", attempt, 500));
        fake.sessionStatuses.add(new HostLaunchPreflight.Session(
                "expired", "windows_sign_in_expired", attempt, 500));
        File directory = Files.createTempDirectory("preflight-sign-in").toFile();
        diagnosticsTestHook("resetForTests");
        try {
            MoonWakerDiagnostics.initialize(directory);
            fake.run(game(42), new AtomicBoolean());
            assertTrue((Boolean) diagnosticsTestHook("flushForTests"));
            String events = new String(Files.readAllBytes(
                    new File(directory, "events.jsonl").toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n");
            assertTrue(events.contains("\"event\":\"preflight.session_state\""));
            assertTrue(events.contains("\"state\":\"credential_issued\""));
            assertTrue(events.contains("\"state\":\"expired\""));
            assertFalse(events.contains(attempt));
        } finally {
            diagnosticsTestHook("resetForTests");
        }
    }

    private static Object diagnosticsTestHook(String name) throws Exception {
        java.lang.reflect.Method method = MoonWakerDiagnostics.class.getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(null);
    }

    @Test public void verifiedSwitchTargetSkipsAppListButNotGatewayOrCancellation() {
        Fake fake = new Fake();
        fake.retainedTarget = app("MoonWaker Stream", 77, PlayniteTargetResolver.MOONWAKER_STREAM_UUID);
        fake.refreshAction = () -> { throw new IllegalStateException("app list blocked by startup"); };
        HostLaunchPreflight.Request request = HostLaunchPreflight.Request.from(
                PlayIntent.playniteGame("host", 77, "Game", false, "steam:1", "game",
                        "", false, false),
                HostLaunchPreflight.Action.SWITCH_RETAINED);
        assertEquals(HostLaunchPreflight.Status.READY, fake.run(request, new AtomicBoolean()).status);
        assertEquals(0, fake.refreshes);
        assertEquals(1, fake.profileChecks);
        fake.profile = null;
        assertFailure(fake.run(request, new AtomicBoolean()), HostLaunchPreflight.Stage.GATEWAY_READY,
                HostLaunchPreflight.FailureReason.GATEWAY_UNAVAILABLE);
        assertEquals(1, fake.retainedChecks);
        fake.profile = profile(true, true, true);
        AtomicBoolean cancelled = new AtomicBoolean();
        fake.retainedAction = () -> cancelled.set(true);
        assertEquals(HostLaunchPreflight.Status.CANCELLED, fake.run(request, cancelled).status);
    }

    @Test public void launchAndReconnectStillRefreshEvenWhenRetainedTargetExists() {
        for (HostLaunchPreflight.Action action : new HostLaunchPreflight.Action[] {
                HostLaunchPreflight.Action.LAUNCH, HostLaunchPreflight.Action.RECONNECT}) {
            Fake fake = new Fake();
            fake.retainedTarget = app("MoonWaker Stream", 77, PlayniteTargetResolver.MOONWAKER_STREAM_UUID);
            fake.apps.add(Collections.singletonList(fake.retainedTarget));
            assertEquals(HostLaunchPreflight.Status.READY, fake.run(HostLaunchPreflight.Request.from(
                    PlayIntent.playniteGame("host", 77, "Game", false, "steam:1", "game",
                            "", false, false), action),
                    new AtomicBoolean()).status);
            assertEquals(1, fake.refreshes);
            assertEquals(0, fake.retainedChecks);
        }
    }

    @Test public void genericSunshineLaunchEnsuresProfileSessionButNeedsNoOptionalBridge() {
        Fake fake = new Fake();
        fake.apps.add(Collections.singletonList(app("Discord", 42, "")));

        HostLaunchPreflight.Result result = fake.run(sunshine(), new AtomicBoolean());

        assertEquals(HostLaunchPreflight.Status.READY, result.status);
        assertEquals(42, result.target.getAppId());
        assertEquals(1, fake.profileChecks);
        assertEquals(1, fake.sessionEnsureCalls);
    }

    @Test public void retainedSwitchCarriesNoWakeActionThroughNetworkGate() {
        Fake fake = new Fake();
        fake.apps.add(Collections.singletonList(app("MoonWaker Stream", 77,
                PlayniteTargetResolver.MOONWAKER_STREAM_UUID)));

        HostLaunchPreflight.Result result = fake.run(
                HostLaunchPreflight.Request.from(PlayIntent.playniteGame(
                        "host", 77, "Game", false, "game", "game"),
                        HostLaunchPreflight.Action.SWITCH_RETAINED),
                new AtomicBoolean());

        assertEquals(HostLaunchPreflight.Status.READY, result.status);
        assertEquals(HostLaunchPreflight.Action.SWITCH_RETAINED, fake.networkActionSeen);
        assertEquals(1, fake.profileChecks);
    }

    @Test public void warmUpUsesTheExistingNeutralTargetWithoutGatewayCalls() {
        Fake fake = new Fake();
        fake.apps.add(Arrays.asList(
                app("Desktop", 8, "desktop"),
                app("MoonWaker Stream", 77,
                        PlayniteTargetResolver.MOONWAKER_STREAM_UUID)));

        HostLaunchPreflight.Result result = fake.run(warmUp(), new AtomicBoolean());

        assertEquals(HostLaunchPreflight.Status.READY, result.status);
        assertEquals(77, result.target.getAppId());
        assertEquals(HostLaunchPreflight.Action.WARM_UP, fake.networkActionSeen);
        assertEquals(1, fake.profileChecks);
        assertEquals(1, fake.sessionEnsureCalls);
        assertEquals(1, fake.refreshes);
    }

    @Test public void warmUpDoesNotCreateAMissingOrImposterNeutralTarget() {
        Fake fake = new Fake();
        fake.apps.add(Arrays.asList(
                app("Desktop", 8, "desktop"),
                app("MoonWaker Stream", 9, "manual")));

        assertFailure(fake.run(warmUp(), new AtomicBoolean()),
                HostLaunchPreflight.Stage.TARGET_READY,
                HostLaunchPreflight.FailureReason.NEUTRAL_STREAM_UNAVAILABLE);
        assertEquals(1, fake.profileChecks);
        assertEquals(1, fake.sessionEnsureCalls);
        assertEquals(1, fake.refreshes);
    }

    @Test public void playniteWithoutGatewayFailsAtGatewayStage() {
        Fake fake = new Fake();
        fake.profile = null;

        assertFailure(fake.run(game(42), new AtomicBoolean()),
                HostLaunchPreflight.Stage.GATEWAY_READY,
                HostLaunchPreflight.FailureReason.GATEWAY_UNAVAILABLE);
    }

    @Test public void missingProfileIsDistinctFromOfflinePlayniteBridge() {
        Fake missing = new Fake();
        missing.profile = profile(false, true, true);
        assertFailure(missing.run(game(42), new AtomicBoolean()),
                HostLaunchPreflight.Stage.PROFILE_AUTHORIZED,
                HostLaunchPreflight.FailureReason.SELECTED_PROFILE_UNAVAILABLE);

        Fake offline = new Fake();
        offline.profile = profile(true, false, true);
        assertFailure(offline.run(game(42), new AtomicBoolean()),
                HostLaunchPreflight.Stage.PLAYNITE_READY,
                HostLaunchPreflight.FailureReason.PLAYNITE_BRIDGE_OFFLINE);
    }

    @Test public void disconnectedPlayniteConnectorIsDetected() {
        Fake fake = new Fake();
        fake.profile = profile(true, true, false);

        assertFailure(fake.run(game(42), new AtomicBoolean()),
                HostLaunchPreflight.Stage.PLAYNITE_READY,
                HostLaunchPreflight.FailureReason.PLAYNITE_CONNECTOR_DISCONNECTED);
    }

    @Test public void gameWithoutConnectorRequirementDoesNotNeedThePlayniteConnector() {
        Fake fake = new Fake();
        fake.profile = profile(true, true, false);
        fake.apps.add(Collections.singletonList(app("MoonWaker Stream", 77,
                PlayniteTargetResolver.MOONWAKER_STREAM_UUID)));

        HostLaunchPreflight.Result result = fake.run(providerGame(42), new AtomicBoolean());

        assertEquals(HostLaunchPreflight.Status.READY, result.status);
        assertEquals(77, result.target.getAppId());
    }

    @Test public void gameUsesTheNeutralStreamEvenWhenItsOwnSunshineAppExists() {
        Fake fake = new Fake();
        fake.apps.add(Arrays.asList(app("Game", 42, "exact"), app("MoonWaker Stream", 77,
                PlayniteTargetResolver.MOONWAKER_STREAM_UUID)));

        HostLaunchPreflight.Result result = fake.run(game(42), new AtomicBoolean());

        assertEquals(77, result.target.getAppId());
    }

    @Test public void gameWithoutASunshineAppOfItsOwnLaunchesThroughTheNeutralStream() {
        for (HostLaunchPreflight.Request request : Arrays.asList(game(0), providerGame(0))) {
            Fake fake = new Fake();
            fake.apps.add(Arrays.asList(app("Desktop", 8, "desktop"),
                    app("MoonWaker Stream", 77,
                            PlayniteTargetResolver.MOONWAKER_STREAM_UUID)));

            HostLaunchPreflight.Result result = fake.run(request, new AtomicBoolean());

            assertEquals(HostLaunchPreflight.Status.READY, result.status);
            assertEquals(77, result.target.getAppId());
        }
    }

    @Test public void retainedSwitchToAGameWithoutAVerifiedTargetRefreshesTheAppList() {
        Fake fake = new Fake();
        fake.apps.add(Collections.singletonList(app("MoonWaker Stream", 77,
                PlayniteTargetResolver.MOONWAKER_STREAM_UUID)));

        HostLaunchPreflight.Result result = fake.run(HostLaunchPreflight.Request.from(
                PlayIntent.playniteGame("host", 0, "Game", false, "steam:1", "game",
                        "", false, false),
                HostLaunchPreflight.Action.SWITCH_RETAINED), new AtomicBoolean());

        assertEquals(HostLaunchPreflight.Status.READY, result.status);
        assertEquals(1, fake.retainedChecks);
        assertEquals(1, fake.refreshes);
        assertEquals(77, result.target.getAppId());
    }

    @Test public void gameWithoutTheNeutralStreamFailsInsteadOfUsingAnotherSunshineApp() {
        for (HostLaunchPreflight.Request request : Arrays.asList(game(42), providerGame(42))) {
            Fake fake = new Fake();
            fake.profile = profile(true, true, true);
            fake.apps.add(Arrays.asList(app("Game", 42, "exact"),
                    app("Desktop", 8, "desktop"), app("MoonWaker Stream", 9, "manual")));

            assertFailure(fake.run(request, new AtomicBoolean()),
                    HostLaunchPreflight.Stage.TARGET_READY,
                    HostLaunchPreflight.FailureReason.NEUTRAL_STREAM_UNAVAILABLE);
            assertEquals(1, fake.refreshes);
        }
    }

    @Test public void preflightPinsGatewayWorkToTheIntentProfile() {
        Fake fake = new Fake();
        fake.apps.add(Collections.singletonList(app("MoonWaker Stream", 77,
                PlayniteTargetResolver.MOONWAKER_STREAM_UUID)));
        HostLaunchPreflight.Request request = HostLaunchPreflight.Request.from(
                PlayIntent.playniteGame("host", "Basia", 42, "Game", false,
                        "game", "game"), HostLaunchPreflight.Action.LAUNCH);
        fake.profile = new HostLaunchPreflight.Profile(
                "Basia", "Basia", "Basia", true, true, true);

        assertEquals(HostLaunchPreflight.Status.READY,
                fake.run(request, new AtomicBoolean()).status);
        assertEquals("Basia", fake.profileSeen);
        assertEquals("Basia", fake.sessionProfileSeen);
    }

    @Test public void cancellationDuringNetworkWaitStopsAllLaterWork() {
        Fake fake = new Fake();
        AtomicBoolean cancelled = new AtomicBoolean();
        fake.networkAction = () -> cancelled.set(true);

        HostLaunchPreflight.Result result = fake.run(game(42), cancelled);

        assertEquals(HostLaunchPreflight.Status.CANCELLED, result.status);
        assertEquals(0, fake.profileChecks);
        assertEquals(0, fake.refreshes);
    }

    @Test public void cancellationDuringGatewayCheckStopsTargetWork() {
        Fake fake = new Fake();
        AtomicBoolean cancelled = new AtomicBoolean();
        fake.profileAction = () -> cancelled.set(true);

        HostLaunchPreflight.Result result = fake.run(game(42), cancelled);

        assertEquals(HostLaunchPreflight.Status.CANCELLED, result.status);
        assertEquals(0, fake.refreshes);
    }

    @Test public void signInAttemptIsPinnedAndPolledBeforeProfileBridgeReadiness() {
        Fake fake = new Fake();
        String attempt = "0123456789abcdef0123456789abcdef";
        fake.profile = new HostLaunchPreflight.Profile(
                "Gry", "Gry", "Gry", true, true, true);
        fake.session = new HostLaunchPreflight.Session(
                "pending", "none", attempt, 500);
        fake.sessionStatuses.add(new HostLaunchPreflight.Session(
                "session_starting", "none", attempt, 500));
        fake.sessionStatuses.add(HostLaunchPreflight.Session.ready());
        fake.apps.add(Collections.singletonList(app("MoonWaker Stream", 77,
                PlayniteTargetResolver.MOONWAKER_STREAM_UUID)));
        HostLaunchPreflight.Request request = HostLaunchPreflight.Request.from(
                PlayIntent.playniteGame("host", "Gry", 42, "Game", false,
                        "game", "game"), HostLaunchPreflight.Action.LAUNCH, 17L);

        HostLaunchPreflight.Result result = fake.run(request, new AtomicBoolean());

        assertEquals(HostLaunchPreflight.Status.READY, result.status);
        assertEquals(2, fake.sessionStatusCalls);
        assertEquals("Gry", fake.sessionProfileSeen);
        assertEquals(request.requestId, fake.sessionRequestSeen);
        assertEquals(attempt, fake.sessionAttemptSeen);
        assertEquals(Arrays.asList(
                HostLaunchPreflight.Stage.NETWORK_READY,
                HostLaunchPreflight.Stage.GATEWAY_READY,
                HostLaunchPreflight.Stage.PROFILE_AUTHORIZED,
                HostLaunchPreflight.Stage.INTERACTIVE_SESSION_READY,
                HostLaunchPreflight.Stage.PROFILE_READY,
                HostLaunchPreflight.Stage.PLAYNITE_READY,
                HostLaunchPreflight.Stage.TARGET_READY), fake.stages);
    }

    @Test public void cancellingPendingSignInCancelsTheBoundBrokerAttempt() {
        Fake fake = new Fake();
        String attempt = "0123456789abcdef0123456789abcdef";
        fake.session = new HostLaunchPreflight.Session(
                "pending", "none", attempt, 500);
        AtomicBoolean cancelled = new AtomicBoolean();
        fake.waitAction = () -> cancelled.set(true);

        HostLaunchPreflight.Result result = fake.run(game(42), cancelled);

        assertEquals(HostLaunchPreflight.Status.CANCELLED, result.status);
        assertEquals(1, fake.sessionCancelCalls);
        assertEquals(attempt, fake.sessionAttemptSeen);
        assertEquals(0, fake.refreshes);
    }

    @Test public void timingOutPendingSignInFailsClosedAndCancelsTheBoundBrokerAttempt() {
        Fake fake = new Fake();
        String attempt = "0123456789abcdef0123456789abcdef";
        fake.session = new HostLaunchPreflight.Session(
                "pending", "none", attempt, 500);
        fake.waitStepMs = 60_000L;
        HostLaunchPreflight.Request request = game(42);

        HostLaunchPreflight.Result result = fake.run(request, new AtomicBoolean());

        assertFailure(result, HostLaunchPreflight.Stage.INTERACTIVE_SESSION_READY,
                HostLaunchPreflight.FailureReason.WINDOWS_SIGN_IN_TIMEOUT);
        assertEquals(1, fake.sessionCancelCalls);
        assertEquals(request.requestId, fake.sessionRequestSeen);
        assertEquals(attempt, fake.sessionAttemptSeen);
        assertEquals(0, fake.refreshes);
    }

    @Test public void remoteSignInDenialCannotDispatchAStreamTarget() {
        Fake fake = new Fake();
        fake.profile = new HostLaunchPreflight.Profile(
                "default", "default", "Gry", true, true, true);
        fake.session = new HostLaunchPreflight.Session(
                "action_required", "remote_sign_in_not_granted", "", 1_000);
        HostLaunchPreflight.Result result = fake.run(game(42), new AtomicBoolean());

        assertFailure(result, HostLaunchPreflight.Stage.INTERACTIVE_SESSION_READY,
                HostLaunchPreflight.FailureReason.REMOTE_SIGN_IN_NOT_GRANTED);
        assertEquals(0, fake.refreshes);
    }

    @Test public void missingOrRejectedCredentialsFailOnceWithoutRetrying() {
        for (String reason : Arrays.asList("credential_missing", "credential_rejected")) {
            Fake fake = new Fake();
            fake.session = new HostLaunchPreflight.Session(
                    "action_required", reason, "", 1_000);

            assertFailure(fake.run(game(42), new AtomicBoolean()),
                    HostLaunchPreflight.Stage.INTERACTIVE_SESSION_READY,
                    HostLaunchPreflight.FailureReason.CREDENTIAL_ACTION_REQUIRED);
            assertEquals(1, fake.sessionEnsureCalls);
            assertEquals(0, fake.sessionStatusCalls);
            assertEquals(0, fake.refreshes);
        }
    }

    @Test public void anotherActiveProfileStillBlocksTheLaunch() {
        Fake fake = new Fake();
        fake.session = new HostLaunchPreflight.Session(
                "failed", "other_profile_active", "", 1_000);

        HostLaunchPreflight.Result result = fake.run(game(42), new AtomicBoolean());

        assertFailure(result, HostLaunchPreflight.Stage.INTERACTIVE_SESSION_READY,
                HostLaunchPreflight.FailureReason.OTHER_PROFILE_ACTIVE);
        assertEquals(0, fake.refreshes);
    }

    @Test public void missingLoginBrokerCannotDispatchAStreamTarget() {
        Fake fake = new Fake();
        fake.session = new HostLaunchPreflight.Session(
                "failed", "broker_unavailable", "", 1_000);
        HostLaunchPreflight.Result result = fake.run(game(42), new AtomicBoolean());

        assertFailure(result, HostLaunchPreflight.Stage.INTERACTIVE_SESSION_READY,
                HostLaunchPreflight.FailureReason.LOGIN_BROKER_UNAVAILABLE);
        assertEquals(0, fake.refreshes);
    }

    @Test public void unreadableHostSessionStateHasItsOwnFailure() {
        Fake fake = new Fake();
        fake.session = new HostLaunchPreflight.Session(
                "action_required", "session_lock_state_unknown", "", 1_000);
        HostLaunchPreflight.Result result = fake.run(game(42), new AtomicBoolean());

        assertFailure(result, HostLaunchPreflight.Stage.INTERACTIVE_SESSION_READY,
                HostLaunchPreflight.FailureReason.HOST_SESSION_STATE_UNREADABLE);
        assertEquals(0, fake.refreshes);
    }

    @Test public void nullSessionResponseCannotMasqueradeAsReady() {
        Fake fake = new Fake();
        fake.session = null;

        assertFailure(fake.run(game(42), new AtomicBoolean()),
                HostLaunchPreflight.Stage.INTERACTIVE_SESSION_READY,
                HostLaunchPreflight.FailureReason.WINDOWS_SIGN_IN_FAILED);
        assertEquals(0, fake.refreshes);
    }

    @Test public void authenticatedProfileMustMatchTheRequestedActorAndExecutionIdentity() {
        Fake wrongActor = new Fake();
        wrongActor.profile = new HostLaunchPreflight.Profile(
                "other", "other", "Other", true, true, true);
        assertFailure(wrongActor.run(game(42), new AtomicBoolean()),
                HostLaunchPreflight.Stage.PROFILE_AUTHORIZED,
                HostLaunchPreflight.FailureReason.WINDOWS_IDENTITY_MISMATCH);
        assertEquals(0, wrongActor.sessionEnsureCalls);

        Fake wrongExecution = new Fake();
        wrongExecution.profile = new HostLaunchPreflight.Profile(
                "default", "parent", "Parent", true, true, true);
        wrongExecution.session = new HostLaunchPreflight.Session(
                "ready", "none", "", 1_000, "", "default", "other-parent");
        assertFailure(wrongExecution.run(game(42), new AtomicBoolean()),
                HostLaunchPreflight.Stage.INTERACTIVE_SESSION_READY,
                HostLaunchPreflight.FailureReason.WINDOWS_IDENTITY_MISMATCH);
        assertEquals(0, wrongExecution.refreshes);
    }

    @Test public void staleRequestReadyCannotContinue() {
        Fake fake = new Fake();
        fake.session = new HostLaunchPreflight.Session(
                "ready", "none", "", 1_000,
                "android-stale", "default", "default");

        assertFailure(fake.run(game(42), new AtomicBoolean()),
                HostLaunchPreflight.Stage.INTERACTIVE_SESSION_READY,
                HostLaunchPreflight.FailureReason.WINDOWS_IDENTITY_MISMATCH);
        assertEquals(0, fake.refreshes);
    }

    @Test public void cancellationWinsOverLateReadyForTheBoundAttempt() {
        Fake fake = new Fake();
        String attempt = "0123456789abcdef0123456789abcdef";
        fake.session = new HostLaunchPreflight.Session(
                "pending", "none", attempt, 500);
        fake.sessionStatuses.add(new HostLaunchPreflight.Session(
                "ready", "none", attempt, 500));
        AtomicBoolean cancelled = new AtomicBoolean();
        fake.sessionStatusAction = () -> cancelled.set(true);

        HostLaunchPreflight.Result result = fake.run(game(42), cancelled);

        assertEquals(HostLaunchPreflight.Status.CANCELLED, result.status);
        assertEquals(1, fake.sessionCancelCalls);
        assertEquals(0, fake.refreshes);
    }

    @Test public void gatewayAndPositiveIdentityProofAlwaysPrecedeStreamerRefresh() {
        Fake fake = new Fake();
        fake.apps.add(Collections.singletonList(app("MoonWaker Stream", 77,
                PlayniteTargetResolver.MOONWAKER_STREAM_UUID)));

        assertEquals(HostLaunchPreflight.Status.READY,
                fake.run(game(42), new AtomicBoolean()).status);

        assertTrue(fake.events.toString(), fake.events.containsAll(
                java.util.List.of("network", "profile", "session", "streamer")));
        assertTrue(fake.events.indexOf("network") < fake.events.indexOf("profile"));
        assertTrue(fake.events.indexOf("profile") < fake.events.indexOf("session"));
        assertTrue(fake.events.indexOf("session") < fake.events.indexOf("streamer"));
        assertFalse(fake.events.subList(0, fake.events.indexOf("session"))
                .contains("streamer"));
    }

    @Test public void wakingHostWaitsForGatewayWithoutProbingStreamerFirst() {
        Fake fake = new Fake();
        fake.profile = null;
        fake.profileAction = () -> {
            if (fake.profileChecks == 2) {
                fake.profile = profile(true, true, true);
            }
        };
        fake.apps.add(Collections.singletonList(app("MoonWaker Stream", 77,
                PlayniteTargetResolver.MOONWAKER_STREAM_UUID)));

        assertEquals(HostLaunchPreflight.Status.READY,
                fake.run(game(42), new AtomicBoolean()).status);
        assertEquals(2, fake.profileChecks);
        assertEquals(Arrays.asList("network", "profile", "profile", "session", "streamer"),
                fake.events);
    }

    private static HostLaunchPreflight.Profile profile(boolean selected,
                                                        boolean playnite,
                                                        boolean connector) {
        return new HostLaunchPreflight.Profile(selected, playnite, connector);
    }

    private static HostLaunchPreflight.Request sunshine() {
        return HostLaunchPreflight.Request.from(PlayIntent.sunshineApp(
                "host", 42, "Discord", false, ""), HostLaunchPreflight.Action.LAUNCH);
    }

    private static HostLaunchPreflight.Request warmUp() {
        return HostLaunchPreflight.Request.from(PlayIntent.autoWarmUp("host", "default"),
                HostLaunchPreflight.Action.WARM_UP);
    }

    private static HostLaunchPreflight.Request game(int appId) {
        return HostLaunchPreflight.Request.from(PlayIntent.playniteGame(
                "host", appId, "Game", false, "game", "game"),
                HostLaunchPreflight.Action.LAUNCH);
    }

    private static HostLaunchPreflight.Request providerGame(int appId) {
        return HostLaunchPreflight.Request.from(PlayIntent.playniteGame(
                "host", appId, "Game", false, "steam:289070", "steam:289070",
                "", false, false),
                HostLaunchPreflight.Action.LAUNCH);
    }

    private static NvApp app(String name, int id, String uuid) {
        NvApp app = new NvApp(name, id, false);
        app.setAppUuid(uuid);
        return app;
    }

    private static void assertFailure(HostLaunchPreflight.Result result,
                                      HostLaunchPreflight.Stage stage,
                                      HostLaunchPreflight.FailureReason reason) {
        assertEquals(HostLaunchPreflight.Status.FAILED, result.status);
        assertEquals(stage, result.failure.stage);
        assertEquals(reason, result.failure.reason);
        assertNull(result.target);
    }

    private static final class Fake implements HostLaunchPreflight.Network,
            HostLaunchPreflight.Gateway, HostLaunchPreflight.Sunshine,
            HostLaunchPreflight.Clock, HostLaunchPreflight.Waiter {
        final Deque<List<NvApp>> apps = new ArrayDeque<>();
        final List<HostLaunchPreflight.Stage> stages = new ArrayList<>();
        HostLaunchPreflight.Profile profile = HostLaunchPreflightTest.profile(
                true, true, true);
        HostLaunchPreflight.Session session = HostLaunchPreflight.Session.ready();
        final Deque<HostLaunchPreflight.Session> sessionStatuses = new ArrayDeque<>();
        Runnable networkAction;
        Runnable profileAction;
        Runnable refreshAction;
        Runnable retainedAction;
        Runnable waitAction;
        Runnable sessionStatusAction;
        final List<String> events = new ArrayList<>();
        NvApp retainedTarget;
        int retainedChecks;
        HostLaunchPreflight.Action networkActionSeen;
        int profileChecks;
        int sessionEnsureCalls;
        int sessionStatusCalls;
        int sessionCancelCalls;
        int refreshes;
        String profileSeen;
        String sessionProfileSeen;
        String sessionRequestSeen;
        String sessionAttemptSeen;
        long now;
        long waitStepMs = 10_000L;

        HostLaunchPreflight.Result run(HostLaunchPreflight.Request request,
                                       AtomicBoolean cancelled) {
            return new HostLaunchPreflight(this, this, this, this, this)
                    .run(request, cancelled::get, stages::add);
        }

        @Override public boolean prepare(String hostId, HostLaunchPreflight.Action action,
                                         java.util.function.BooleanSupplier cancelled) {
            networkActionSeen = action;
            events.add("network");
            if (networkAction != null) networkAction.run();
            return !cancelled.getAsBoolean();
        }

        @Override public HostLaunchPreflight.Profile profile(String hostId, String profileId) {
            profileChecks++;
            events.add("profile");
            profileSeen = profileId;
            if (profileAction != null) profileAction.run();
            return profile;
        }

        @Override public HostLaunchPreflight.Session ensureSession(
                String hostId, String profileId, String requestId) {
            sessionEnsureCalls++;
            events.add("session");
            sessionProfileSeen = profileId;
            sessionRequestSeen = requestId;
            return session;
        }

        @Override public HostLaunchPreflight.Session sessionStatus(
                String hostId, String profileId, String requestId, String attemptId) {
            sessionStatusCalls++;
            events.add("session-status");
            if (sessionStatusAction != null) sessionStatusAction.run();
            sessionProfileSeen = profileId;
            sessionRequestSeen = requestId;
            sessionAttemptSeen = attemptId;
            return sessionStatuses.isEmpty() ? session : sessionStatuses.removeFirst();
        }

        @Override public void cancelSession(String hostId, String profileId,
                                            String requestId, String attemptId) {
            sessionCancelCalls++;
            sessionProfileSeen = profileId;
            sessionRequestSeen = requestId;
            sessionAttemptSeen = attemptId;
        }

        @Override public List<NvApp> refreshApps(String hostId) {
            refreshes++;
            events.add("streamer");
            if (refreshAction != null) refreshAction.run();
            return apps.isEmpty() ? Collections.emptyList() : apps.removeFirst();
        }

        @Override public NvApp verifiedRetainedTarget(HostLaunchPreflight.Request request) {
            retainedChecks++;
            if (retainedAction != null) retainedAction.run();
            return retainedTarget;
        }

        @Override public long now() { return now; }

        @Override public boolean await(long millis,
                                       java.util.function.BooleanSupplier cancelled) {
            now += waitStepMs;
            if (waitAction != null) waitAction.run();
            return !cancelled.getAsBoolean();
        }
    }
}
